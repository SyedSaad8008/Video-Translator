package com.example.videotranslator.ui.player

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.videotranslator.audio.AudioExtractor
import com.example.videotranslator.audio.InstrumentalPlayer
import com.example.videotranslator.cache.SegmentCache
import com.example.videotranslator.model.Language
import com.example.videotranslator.model.ProcessingState
import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.stt.VoskSpeechRecognizer
import com.example.videotranslator.translation.TranslationManager
import com.example.videotranslator.tts.TtsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "VideoPlayerVM"

// ── Polling ────────────────────────────────────────────────────────────────────
private const val POLL_INTERVAL_MS    = 100L
private const val TRIGGER_TOLERANCE_MS = 200L

// ── Volume levels ──────────────────────────────────────────────────────────────
/** Hindi — full original audio */
private const val EXOPLAYER_FULL   = 1.0f
/** Translated — mute original audio completely */
private const val EXOPLAYER_MUTED  = 0.0f

class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    // ── Dependencies ──────────────────────────────────────────────────────────
    private val cache              = SegmentCache(application)
    private val audioExtractor     = AudioExtractor(application)
    private val voskRecognizer     = VoskSpeechRecognizer(application)
    private val translationManager = TranslationManager()
    val ttsManager                 = TtsManager(application)
    private val instrumental       = InstrumentalPlayer(viewModelScope)

    // ── UI State ──────────────────────────────────────────────────────────────
    private val _processingState  = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()

    private val _currentLanguage  = MutableStateFlow(Language.HINDI)
    val currentLanguage: StateFlow<Language> = _currentLanguage.asStateFlow()

    private val _missingVoiceWarning = MutableStateFlow(false)
    val missingVoiceWarning: StateFlow<Boolean> = _missingVoiceWarning.asStateFlow()

    private val _videoUri = MutableStateFlow<Uri?>(null)
    val videoUri: StateFlow<Uri?> = _videoUri.asStateFlow()

    // ── ExoPlayer ─────────────────────────────────────────────────────────────
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(application).build().apply {
        repeatMode    = Player.REPEAT_MODE_OFF
        playWhenReady = false
    }

    private var ttsPollingJob: Job? = null
    private var pipelineJob:   Job? = null
    private var prewarmJob:    Job? = null   // pre-warm Vosk + ML Kit before video pick
    private var lastSpokenIndex = -1

    // ── Player.Listener ───────────────────────────────────────────────────────
    /**
     * This listener is the KEY fix for TTS sync:
     *  - Pause  → immediately stop TTS and instrumental (no lag)
     *  - Resume → reset segment index; resume instrumental from exact position
     *  - Seek   → stop TTS, reset index, jump instrumental to new position
     *  - End    → clean up TTS and instrumental
     */
    private val playerListener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                // ── VIDEO RESUMED ─────────────────────────────────────────
                lastSpokenIndex = -1     // re-evaluate segments from current position
                val lang = _currentLanguage.value
                if (lang != Language.HINDI && instrumental.isLoaded) {
                    instrumental.resumeFrom(exoPlayer.currentPosition)
                }
                Log.d(TAG, "Resumed at ${exoPlayer.currentPosition}ms")
            } else {
                // ── VIDEO PAUSED ──────────────────────────────────────────
                ttsManager.stop()        // kill TTS immediately — no lag
                instrumental.pause()
                Log.d(TAG, "Paused → TTS + instrumental stopped")
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // ── SEEK ──────────────────────────────────────────────────────
            ttsManager.stop()
            lastSpokenIndex = -1
            if (_currentLanguage.value != Language.HINDI && instrumental.isLoaded) {
                instrumental.seekTo(newPosition.positionMs)
            }
            Log.d(TAG, "Seek → ${newPosition.positionMs}ms")
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                ttsManager.stop()
                instrumental.stop()
                Log.d(TAG, "Video ended → TTS + instrumental stopped")
            }
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────
    init {
        // Attach listener BEFORE prepare so we never miss events
        exoPlayer.addListener(playerListener)

        viewModelScope.launch {
            val ok = ttsManager.initialise()
            if (!ok) {
                _processingState.value = ProcessingState.Error("TTS engine failed to initialise.")
                return@launch
            }
            ttsManager.setLanguage(Language.HINDI)

            // TTS callbacks (no volume ducking needed — original is fully muted)
            ttsManager.onSpeakStart = { /* nothing — original muted */ }
            ttsManager.onSpeakDone  = { /* nothing — original muted */ }

            _processingState.value = ProcessingState.Idle
        }

        // ══ PRE-WARM: load Vosk model + ML Kit translation models in background ═════════
        // This runs as soon as the ViewModel is created (app launch), so by the time
        // the user picks a video, both heavy models are already in memory.
        prewarmJob = viewModelScope.launch {
            try {
                // Run both model loads in parallel
                val voskLoad = launch {
                    try { voskRecognizer.loadModel() }
                    catch (e: Exception) { Log.w(TAG, "Vosk prewarm failed (will retry): ${e.message}") }
                }
                val mlKitLoad = launch {
                    try { translationManager.downloadModels() }
                    catch (e: Exception) { Log.w(TAG, "ML Kit prewarm failed (will retry): ${e.message}") }
                }
                voskLoad.join()
                mlKitLoad.join()
                Log.d(TAG, "Pre-warm complete ✔")
            } catch (e: CancellationException) { throw e }
        }
    }

    // ── Video picking ─────────────────────────────────────────────────────────
    fun onVideoPicked(uri: Uri) {
        Log.d(TAG, "Video picked: $uri")
        pipelineJob?.cancel()
        ttsPollingJob?.cancel()
        ttsManager.stop()
        instrumental.stop()
        lastSpokenIndex = -1
        _videoUri.value = uri
        _processingState.value = ProcessingState.Idle

        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        applyVolumeForLanguage(_currentLanguage.value)

        pipelineJob = viewModelScope.launch { runPipeline(uri) }
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────
    private suspend fun runPipeline(uri: Uri) {
        try {
            // ── Cache hit ───────────────────────────────────────────────
            if (cache.isCached(uri)) {
                cache.load(uri)?.let { cached ->
                    Log.d(TAG, "Segments from cache: ${cached.size}")
                    loadInstrumentalIfAvailable(uri)
                    _processingState.value = ProcessingState.Ready
                    startTtsPolling()
                    return
                }
            }

            // ── 1. Wait for pre-warm to complete (usually already done) ────────
            prewarmJob?.join()   // no-op if already finished

            // ── 2. Extract audio (mono + instrumental in one pass) ──────────
            _processingState.value = ProcessingState.Loading("Extracting audio…", 0.10f)
            val monoFile         = cache.pcmFileFor(uri)
            val instrumentalFile = cache.instrumentalFileFor(uri)

            val result = if (monoFile.exists()) {
                val mono  = audioExtractor.loadMonoFromCache(monoFile)
                val instr = if (instrumentalFile.exists())
                    audioExtractor.loadInstrumentalFromCache(instrumentalFile) else null
                AudioExtractor.ExtractionResult(mono, instr)
            } else {
                audioExtractor.extractToFiles(uri, monoFile, instrumentalFile)
            }

            if (result.instrumental != null) instrumental.loadFromFile(instrumentalFile)

            // ── 3. Transcribe (VAD-filtered) ──────────────────────────────
            _processingState.value = ProcessingState.Loading("Transcribing Hindi audio…", 0.40f)
            val rawSegments = voskRecognizer.recognise(result.mono)
            Log.d(TAG, "Vosk: ${rawSegments.size} segments")


            // ── 4. Download ML Kit translation models ─────────────────────
            _processingState.value = ProcessingState.Loading("Downloading translation models…", 0.60f)
            translationManager.downloadModels()

            // ── 5. Sentence-level translation ─────────────────────────────
            _processingState.value = ProcessingState.Loading("Translating…", 0.80f)
            val translated = translationManager.translate(rawSegments)

            // ── 6. Cache + publish ────────────────────────────────────────
            _processingState.value = ProcessingState.Loading("Saving…", 0.97f)
            cache.save(uri, translated)
            _processingState.value = ProcessingState.Ready
            startTtsPolling()

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline failed", e)
            _processingState.value = ProcessingState.Error(e.message ?: "Unknown error")
        }
    }

    /** Load the cached instrumental file into the player (if it exists). */
    private fun loadInstrumentalIfAvailable(uri: Uri) {
        val file = cache.instrumentalFileFor(uri)
        if (file.exists()) instrumental.loadFromFile(file)
    }

    // ── Language switching ────────────────────────────────────────────────────
    fun switchLanguage(language: Language) {
        if (_currentLanguage.value == language) return
        _currentLanguage.value = language
        ttsManager.stop()
        ttsManager.setLanguage(language)
        _missingVoiceWarning.value = ttsManager.isMissingVoice
        applyVolumeForLanguage(language)
        lastSpokenIndex = -1
        if (_processingState.value == ProcessingState.Ready) startTtsPolling()
    }

    /**
     * Volume strategy:
     *  - Hindi  → ExoPlayer full (1.0) — hear original
     *  - Other  → ExoPlayer muted (0.0) — only hear TTS voice
     */
    private fun applyVolumeForLanguage(lang: Language) {
        if (lang == Language.HINDI) {
            exoPlayer.volume = EXOPLAYER_FULL
            instrumental.stop()
        } else {
            exoPlayer.volume = EXOPLAYER_MUTED
            instrumental.stop()
        }
    }

    // ── TTS polling ───────────────────────────────────────────────────────────
    /**
     * Polls every 100ms to dispatch TTS for the segment at the current video position.
     * The Player.Listener guarantees TTS is stopped when the video is not playing,
     * so this loop only fires TTS when `exoPlayer.isPlaying == true`.
     */
    private fun startTtsPolling() {
        ttsPollingJob?.cancel()
        ttsPollingJob = viewModelScope.launch {
            while (isActive) {
                val lang = _currentLanguage.value
                if (lang != Language.HINDI && exoPlayer.isPlaying) {
                    dispatchTts(exoPlayer.currentPosition, lang)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun dispatchTts(posMs: Long, language: Language) {
        val segs = cache.lastLoaded ?: return
        if (segs.isEmpty()) return
        val idx = segs.indexOfFirst { posMs >= (it.startMs - TRIGGER_TOLERANCE_MS) && posMs <= it.endMs }
        if (idx < 0 || idx == lastSpokenIndex || ttsManager.isSpeaking()) return
        lastSpokenIndex = idx
        val seg = segs[idx]
        val text = when (language) {
            Language.ENGLISH -> seg.english
            Language.TELUGU  -> seg.telugu
            Language.HINDI   -> seg.hindi
        }
        if (text.isNotBlank()) {
            val segDuration = (seg.endMs - seg.startMs).coerceAtLeast(300L)
            val isIndic = language == Language.TELUGU
            ttsManager.speakTimed(text, segDuration, isIndic)
        }
    }

    // ── Reprocess ─────────────────────────────────────────────────────────────
    fun reprocess() {
        val uri = _videoUri.value ?: return
        ttsPollingJob?.cancel()
        cache.clearFor(uri)
        cache.instrumentalFileFor(uri).delete()
        _processingState.value = ProcessingState.Idle
        lastSpokenIndex = -1
        instrumental.stop()
        pipelineJob = viewModelScope.launch { runPipeline(uri) }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCleared() {
        super.onCleared()
        prewarmJob?.cancel()
        pipelineJob?.cancel()
        ttsPollingJob?.cancel()
        ttsManager.shutdown()
        voskRecognizer.close()
        translationManager.close()
        instrumental.release()
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
    }
}
