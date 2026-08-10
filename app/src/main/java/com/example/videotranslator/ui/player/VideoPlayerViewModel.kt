package com.example.videotranslator.ui.player

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.videotranslator.R
import com.example.videotranslator.audio.AudioExtractor
import com.example.videotranslator.audio.GenderDetector
import com.example.videotranslator.audio.InstrumentalPlayer
import com.example.videotranslator.audio.SegmentAudioPlayer
import com.example.videotranslator.cache.SegmentCache
import com.example.videotranslator.model.Gender
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
import java.io.File

private const val TAG = "VideoPlayerVM"

// ── Polling ────────────────────────────────────────────────────────────────────
private const val POLL_INTERVAL_MS     = 100L
private const val TRIGGER_TOLERANCE_MS = 200L

// ── Volume levels ──────────────────────────────────────────────────────────────
private const val EXOPLAYER_FULL  = 1.0f
private const val EXOPLAYER_MUTED = 0.0f

class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    // ── Dependencies ──────────────────────────────────────────────────────────
    private val cache              = SegmentCache(application)
    private val audioExtractor     = AudioExtractor(application)
    private val genderDetector     = GenderDetector()
    private val voskRecognizer     = VoskSpeechRecognizer(application)
    private val translationManager = TranslationManager()
    val ttsManager                 = TtsManager(application)
    private val segmentAudioPlayer = SegmentAudioPlayer()
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

    private val _detectedGender = MutableStateFlow(Gender.UNKNOWN)
    val detectedGender: StateFlow<Gender> = _detectedGender.asStateFlow()

    // ── ExoPlayer ─────────────────────────────────────────────────────────────
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(application).build().apply {
        repeatMode    = Player.REPEAT_MODE_OFF
        playWhenReady = false
    }

    private var ttsPollingJob: Job? = null
    private var pipelineJob:   Job? = null
    private var prewarmJob:    Job? = null
    private var lastSpokenIndex = -1

    // ── Player.Listener ───────────────────────────────────────────────────────
    private val playerListener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                lastSpokenIndex = -1
                val lang = _currentLanguage.value
                if (lang != Language.HINDI) {
                    segmentAudioPlayer.resume()
                    if (instrumental.isLoaded) instrumental.resumeFrom(exoPlayer.currentPosition)
                }
                Log.d(TAG, "Resumed at ${exoPlayer.currentPosition}ms")
            } else {
                segmentAudioPlayer.pause()
                instrumental.pause()
                Log.d(TAG, "Paused -> Segment audio + instrumental paused")
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            segmentAudioPlayer.stop()
            lastSpokenIndex = -1
            if (_currentLanguage.value != Language.HINDI && instrumental.isLoaded) {
                instrumental.seekTo(newPosition.positionMs)
            }
            Log.d(TAG, "Seek -> ${newPosition.positionMs}ms")
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                segmentAudioPlayer.stop()
                instrumental.stop()
                Log.d(TAG, "Video ended -> Segment audio + instrumental stopped")
            }
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────
    init {
        exoPlayer.addListener(playerListener)

        viewModelScope.launch {
            val ok = ttsManager.initialise()
            if (!ok) {
                _processingState.value = ProcessingState.Error("TTS engine failed to initialise.")
                return@launch
            }
            _processingState.value = ProcessingState.Idle

            val sampleUri = Uri.parse("android.resource://${getApplication<Application>().packageName}/${R.raw.sample_video}")
            onVideoPicked(sampleUri)
        }

        prewarmJob = viewModelScope.launch {
            try {
                val voskLoad = launch {
                    try { voskRecognizer.loadModel() }
                    catch (e: Exception) { Log.w(TAG, "Vosk prewarm failed: ${e.message}") }
                }
                val mlKitLoad = launch {
                    try { translationManager.downloadModels() }
                    catch (e: Exception) { Log.w(TAG, "ML Kit prewarm failed: ${e.message}") }
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
        segmentAudioPlayer.stop()
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
            // Force clean pipeline run for diagnostic evaluation
            cache.clearFor(uri)

            _processingState.value = ProcessingState.Loading("Initializing AI models…", 0.05f)
            prewarmJob?.join()

            // ── 2. Extract audio (mono + instrumental) ────────────────
            _processingState.value = ProcessingState.Loading("Extracting audio from video…", 0.15f)
            val monoFile         = cache.pcmFileFor(uri)
            val instrumentalFile = cache.instrumentalFileFor(uri)

            val result = audioExtractor.extractToFiles(uri, monoFile, instrumentalFile)
            if (result.instrumental != null) instrumental.loadFromFile(instrumentalFile)

            // ── DIAGNOSTIC STEP 1: Pitch Estimation & Gender Detection ────────
            _processingState.value = ProcessingState.Loading("Detecting speaker voice pitch & gender…", 0.28f)
            val genderResult = genderDetector.detectGender(result.mono)
            _detectedGender.value = genderResult.gender

            Log.d(TAG, "================ DIAGNOSTIC STEP 1 REPORT ================")
            Log.d(TAG, "Source Audio Voiced Frames: ${genderResult.totalVoicedFrames}")
            Log.d(TAG, "Computed Median F0 Pitch:   ${"%.2f".format(genderResult.medianF0)} Hz")
            Log.d(TAG, "Classified Speaker Gender:  ${genderResult.gender}")
            Log.d(TAG, "==========================================================")

            // ── DIAGNOSTIC STEP 3: Test Male vs Female Voice Selection Side-by-Side ─
            Log.d(TAG, "================ DIAGNOSTIC STEP 3 SIDE-BY-SIDE EVALUATION ================")
            ttsManager.selectVoiceForGender(Language.ENGLISH, Gender.MALE)
            val maleEnVoice = ttsManager.selectedVoiceName
            ttsManager.selectVoiceForGender(Language.ENGLISH, Gender.FEMALE)
            val femaleEnVoice = ttsManager.selectedVoiceName

            ttsManager.selectVoiceForGender(Language.TELUGU, Gender.MALE)
            val maleTeVoice = ttsManager.selectedVoiceName
            ttsManager.selectVoiceForGender(Language.TELUGU, Gender.FEMALE)
            val femaleTeVoice = ttsManager.selectedVoiceName

            Log.d(TAG, "English (en-US) Male Voice:   '$maleEnVoice'")
            Log.d(TAG, "English (en-US) Female Voice: '$femaleEnVoice'")
            Log.d(TAG, "English Voices Distinct:       ${maleEnVoice != femaleEnVoice}")

            Log.d(TAG, "Telugu (te-IN) Male Voice:    '$maleTeVoice'")
            Log.d(TAG, "Telugu (te-IN) Female Voice:  '$femaleTeVoice'")
            Log.d(TAG, "Telugu Voices Distinct:        ${maleTeVoice != femaleTeVoice}")
            Log.d(TAG, "==========================================================================")

            // ── 3. Transcribe (Vosk) ───────────────────────────────────
            _processingState.value = ProcessingState.Loading("Transcribing Hindi speech…", 0.42f)
            val rawSegments = voskRecognizer.recognise(result.mono)

            // ── 4. Download ML Kit translation models ─────────────────
            _processingState.value = ProcessingState.Loading("Loading translation models…", 0.58f)
            translationManager.downloadModels()

            // ── 5. Sentence-level translation ─────────────────────────
            _processingState.value = ProcessingState.Loading("Translating speech into English & Telugu…", 0.72f)
            val translatedSegments = translationManager.translate(rawSegments)

            // ── 5b. PART B: Pre-Render Segment Audio & Duration Matching ─────────
            val renderedDir = cache.renderedAudioDir(uri)
            val finalProcessedSegments = mutableListOf<TranslationSegment>()
            val totalSegs = translatedSegments.size.coerceAtLeast(1)

            for (idx in translatedSegments.indices) {
                val seg = translatedSegments[idx]
                val progressStep = 0.72f + (0.24f * (idx.toFloat() / totalSegs.toFloat()))
                _processingState.value = ProcessingState.Loading(
                    "Pre-rendering audio (Segment ${idx + 1}/$totalSegs)…",
                    progressStep
                )

                val targetDurationMs = (seg.endMs - seg.startMs).coerceAtLeast(300L)

                // Render English audio
                val enFile = File(renderedDir, "seg_${idx}_en.wav")
                ttsManager.selectVoiceForGender(Language.ENGLISH, genderResult.gender)
                val enRenderedMs = ttsManager.synthesizeToFile(seg.english, enFile)
                val enSpeedRatio = if (enRenderedMs > 0) {
                    (enRenderedMs.toFloat() / targetDurationMs.toFloat()).coerceIn(0.75f, 1.5f)
                } else 1.0f

                // Render Telugu audio
                val teFile = File(renderedDir, "seg_${idx}_te.wav")
                ttsManager.selectVoiceForGender(Language.TELUGU, genderResult.gender)
                val teRenderedMs = ttsManager.synthesizeToFile(seg.telugu, teFile)
                val teSpeedRatio = if (teRenderedMs > 0) {
                    (teRenderedMs.toFloat() / targetDurationMs.toFloat()).coerceIn(0.75f, 1.5f)
                } else 1.0f

                finalProcessedSegments.add(
                    seg.copy(
                        englishAudioPath = enFile.absolutePath,
                        englishSpeedRatio = enSpeedRatio,
                        teluguAudioPath = teFile.absolutePath,
                        teluguSpeedRatio = teSpeedRatio
                    )
                )
            }

            // ── 6. Cache + publish ────────────────────────────────────
            _processingState.value = ProcessingState.Loading("Saving cached audio…", 0.98f)
            cache.save(uri, finalProcessedSegments)
            _processingState.value = ProcessingState.Ready
            startTtsPolling()

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline failed", e)
            _processingState.value = ProcessingState.Error(e.message ?: "Unknown error")
        }
    }

    private fun loadInstrumentalIfAvailable(uri: Uri) {
        val file = cache.instrumentalFileFor(uri)
        if (file.exists()) instrumental.loadFromFile(file)
    }

    // ── Language switching ────────────────────────────────────────────────────
    fun switchLanguage(language: Language) {
        if (_currentLanguage.value == language) return
        _currentLanguage.value = language
        segmentAudioPlayer.stop()
        ttsManager.selectVoiceForGender(language, _detectedGender.value)
        _missingVoiceWarning.value = ttsManager.isMissingVoice
        applyVolumeForLanguage(language)
        lastSpokenIndex = -1
        if (_processingState.value == ProcessingState.Ready) startTtsPolling()
    }

    private fun applyVolumeForLanguage(lang: Language) {
        if (lang == Language.HINDI) {
            exoPlayer.volume = EXOPLAYER_FULL
            instrumental.stop()
        } else {
            exoPlayer.volume = EXOPLAYER_MUTED
            instrumental.stop()
        }
    }

    // ── TTS / Audio segment polling ───────────────────────────────────────────
    private fun startTtsPolling() {
        ttsPollingJob?.cancel()
        ttsPollingJob = viewModelScope.launch {
            while (isActive) {
                val lang = _currentLanguage.value
                if (lang != Language.HINDI && exoPlayer.isPlaying) {
                    dispatchSegmentAudio(exoPlayer.currentPosition, lang)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun dispatchSegmentAudio(posMs: Long, language: Language) {
        val segs = cache.lastLoaded ?: return
        if (segs.isEmpty()) return
        val idx = segs.indexOfFirst { posMs >= (it.startMs - TRIGGER_TOLERANCE_MS) && posMs <= it.endMs }
        if (idx < 0 || idx == lastSpokenIndex || segmentAudioPlayer.isPlaying()) return
        lastSpokenIndex = idx
        val seg = segs[idx]

        val (audioPath, speedRatio) = when (language) {
            Language.ENGLISH -> Pair(seg.englishAudioPath, seg.englishSpeedRatio)
            Language.TELUGU  -> Pair(seg.teluguAudioPath, seg.teluguSpeedRatio)
            Language.HINDI   -> Pair("", 1.0f)
        }

        if (audioPath.isNotBlank()) {
            val file = File(audioPath)
            if (file.exists() && file.length() > 0) {
                segmentAudioPlayer.playSegment(file, speedRatio)
            }
        }
    }

    // ── Reprocess ─────────────────────────────────────────────────────────────
    fun reprocess() {
        val uri = _videoUri.value ?: return
        ttsPollingJob?.cancel()
        segmentAudioPlayer.stop()
        cache.clearFor(uri)
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
        segmentAudioPlayer.release()
        ttsManager.shutdown()
        voskRecognizer.close()
        translationManager.close()
        instrumental.release()
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
    }
}
