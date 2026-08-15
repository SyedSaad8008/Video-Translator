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
import com.example.videotranslator.audio.GenderDetector
import com.example.videotranslator.audio.InstrumentalPlayer
import com.example.videotranslator.audio.NoiseSuppressor
import com.example.videotranslator.audio.SegmentAudioPlayer
import com.example.videotranslator.cache.SegmentCache
import com.example.videotranslator.model.Gender
import com.example.videotranslator.model.Language
import com.example.videotranslator.model.ProcessingState
import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.stt.VoskSpeechRecognizer
import com.example.videotranslator.translation.TranslationManager
import com.example.videotranslator.tts.TtsManager
import com.example.videotranslator.tts.VoiceAvailabilityStatus
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

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
    private val noiseSuppressor    = NoiseSuppressor()
    private val voskRecognizer     = VoskSpeechRecognizer(application)
    private val translationManager = TranslationManager()
    private val genderDetector     = GenderDetector()
    val ttsManager                 = TtsManager(application)
    private val segmentAudioPlayer = SegmentAudioPlayer()
    private val instrumental       = InstrumentalPlayer(viewModelScope)

    // ── State Flows ───────────────────────────────────────────────────────────
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(application).build()

    private val _currentLanguage = MutableStateFlow(Language.HINDI)
    val currentLanguage: StateFlow<Language> = _currentLanguage.asStateFlow()

    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()

    private val _detectedGender = MutableStateFlow(Gender.MALE)
    val detectedGender: StateFlow<Gender> = _detectedGender.asStateFlow()

    private val _missingVoiceWarning = MutableStateFlow(false)
    val missingVoiceWarning: StateFlow<Boolean> = _missingVoiceWarning.asStateFlow()

    private val _voiceAvailabilityStatus = MutableStateFlow(
        VoiceAvailabilityStatus(
            language = Language.HINDI,
            locale = Locale.ENGLISH,
            isLanguageSupported = false,
            totalVoicesCount = 0,
            hasMaleVoice = false,
            hasFemaleVoice = false,
            hasGenderMatchedVoices = false,
            isSingleVoiceOnly = false,
            message = "Initializing..."
        )
    )
    val voiceAvailabilityStatus: StateFlow<VoiceAvailabilityStatus> = _voiceAvailabilityStatus.asStateFlow()

    private val _videoUri = MutableStateFlow<Uri?>(null)
    val videoUri: StateFlow<Uri?> = _videoUri.asStateFlow()

    private var pipelineJob: Job? = null
    private var ttsPollingJob: Job? = null
    private var prewarmJob: Job? = null
    private var lastSpokenIndex = -1

    init {
        DiagnosticLogger.init(application)
        DiagnosticLogger.log(TAG, "VideoPlayerViewModel initialized.")

        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && _currentLanguage.value != Language.HINDI) {
                    instrumental.play(exoPlayer.currentPosition)
                } else {
                    instrumental.pause()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                instrumental.seekTo(newPosition.positionMs)
                lastSpokenIndex = -1
                segmentAudioPlayer.stop()
            }
        })

        // Pre-warm Vosk model & check ML Kit on app start asynchronously
        prewarmJob = viewModelScope.launch {
            try {
                val voskLoad  = launch { voskRecognizer.loadModel() }
                val mlKitLoad = launch {
                    try { translationManager.downloadModels() }
                    catch (e: Exception) { Log.w(TAG, "ML Kit prewarm failed: ${e.message}") }
                }
                voskLoad.join()
                mlKitLoad.join()
                DiagnosticLogger.log(TAG, "Pre-warm complete ✓")
            } catch (e: CancellationException) { throw e }
        }
    }

    // ── Video picking ─────────────────────────────────────────────────────────
    fun onVideoPicked(uri: Uri) {
        DiagnosticLogger.log(TAG, "Video picked: $uri")
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

    fun retryPipeline() {
        val uri = _videoUri.value ?: return
        DiagnosticLogger.log(TAG, "User triggered retry for video: $uri")
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch { runPipeline(uri) }
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────
    private suspend fun runPipeline(uri: Uri) {
        val pipelineStart = System.currentTimeMillis()
        DiagnosticLogger.log(TAG, "Starting full pipeline execution for $uri")
        try {
            // Check cache
            if (cache.isCached(uri)) {
                cache.load(uri)?.let { cached ->
                    val isValidCache = cached.isNotEmpty() && cached.all { seg ->
                        seg.englishAudioPath.isNotBlank() && File(seg.englishAudioPath).exists() &&
                        seg.teluguAudioPath.isNotBlank() && File(seg.teluguAudioPath).exists()
                    }

                    if (isValidCache) {
                        DiagnosticLogger.log(TAG, "Valid pre-rendered segment cache loaded: ${cached.size} segments")
                        loadInstrumentalIfAvailable(uri)
                        _processingState.value = ProcessingState.Ready
                        startTtsPolling()
                        return
                    } else {
                        cache.clearFor(uri)
                    }
                }
            }

            // ── 1. Wait for pre-warm ──────────────────────────────────
            _processingState.value = ProcessingState.Loading("Initializing AI models…", 0.05f)
            prewarmJob?.join()

            // ── 2. Extract audio (mono + instrumental) ────────────────
            _processingState.value = ProcessingState.Loading("Extracting audio from video…", 0.15f)
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

            // ── 2b. Multi-Segment Adaptive DSP Noise Suppression ─────────
            _processingState.value = ProcessingState.Loading("Applying adaptive DSP noise suppression…", 0.22f)
            val cleanedMono = noiseSuppressor.suppressNoise(result.mono)

            // ── 2c. Global Voice Gender Detection (Baseline) ──────────────
            _processingState.value = ProcessingState.Loading("Analyzing global audio pitch…", 0.28f)
            val globalGenderResult = genderDetector.detectGender(cleanedMono)
            _detectedGender.value = globalGenderResult.gender
            DiagnosticLogger.log(TAG, "Global Audio Gender Baseline: ${globalGenderResult.gender} (Median F0 = ${"%.1f".format(globalGenderResult.medianF0)} Hz)")

            // ── 3. Transcribe (Vosk) on Cleaned Audio ─────────────────────
            _processingState.value = ProcessingState.Loading("Transcribing Hindi speech (High-Precision STT)…", 0.42f)
            val rawSegments = voskRecognizer.recognise(cleanedMono)
            DiagnosticLogger.log(TAG, "Vosk recognized ${rawSegments.size} Hindi segments")

            if (rawSegments.isEmpty()) {
                val msg = "No spoken Hindi speech detected in this video. Please select a video with audible Hindi speech."
                DiagnosticLogger.log(TAG, "Pipeline Stopped: $msg")
                _processingState.value = ProcessingState.Error(msg)
                return
            }

            // ── 4. Download ML Kit translation models ─────────────────
            _processingState.value = ProcessingState.Loading("Loading/Downloading neural translation models…", 0.58f)
            val modelResult = translationManager.downloadModels()
            if (modelResult.isFailure) {
                val err = modelResult.exceptionOrNull()?.message ?: "Translation model download failed."
                DiagnosticLogger.log(TAG, "Pipeline Error: $err")
                _processingState.value = ProcessingState.Error(err)
                return
            }

            // ── 5. Two-Tier Contextual Sentence Translation ───────────
            _processingState.value = ProcessingState.Loading("Translating full sentence context into English & Telugu…", 0.72f)
            val translatedSegments = translationManager.translate(rawSegments)

            // ── 5b. Multi-Pass Gender Analysis & Duration-Matched TTS Pre-Rendering ─
            val renderedDir = cache.renderedAudioDir(uri)
            val finalProcessedSegments = mutableListOf<TranslationSegment>()
            val totalSegs = translatedSegments.size.coerceAtLeast(1)

            var runningGender = globalGenderResult.gender

            for (idx in translatedSegments.indices) {
                val seg = translatedSegments[idx]
                val progressStep = 0.72f + (0.24f * (idx.toFloat() / totalSegs.toFloat()))
                _processingState.value = ProcessingState.Loading(
                    "Analyzing speaker tone & pre-rendering audio (${idx + 1}/$totalSegs)…",
                    progressStep
                )

                // Extract specific PCM audio slice for this segment from cleanedMono
                val startSample = ((seg.startMs * 16000) / 1000).toInt().coerceIn(0, cleanedMono.size)
                val endSample   = ((seg.endMs * 16000) / 1000).toInt().coerceIn(startSample, cleanedMono.size)
                val segPcm      = if (endSample > startSample) cleanedMono.copyOfRange(startSample, endSample) else ShortArray(0)

                val prevEndMs = if (idx > 0) translatedSegments[idx - 1].endMs else 0L

                // Detect gender specifically for this sentence segment with Multi-Pass analysis & pause boundary clamping
                val segGenderRes = genderDetector.detectGender(
                    pcmMono = segPcm,
                    fallbackGender = runningGender,
                    fullPcmMono = cleanedMono,
                    segmentStartMs = seg.startMs,
                    segmentEndMs = seg.endMs,
                    previousSegmentEndMs = prevEndMs
                )
                val segmentGender = segGenderRes.gender
                runningGender = segmentGender

                DiagnosticLogger.log(TAG, "SEGMENT GENDER LOG [$idx] (${seg.startMs}ms -> ${seg.endMs}ms):\n" +
                        "   Voiced Frames: ${segGenderRes.totalVoicedFrames}\n" +
                        "   Confidence:    ${"%.2f".format(segGenderRes.confidenceScore)}${if (segGenderRes.isPass2Triggered) " (Pass 2 Clamped Expansion Triggered)" else ""}\n" +
                        "   Computed F0:   ${if (segGenderRes.isCarriedOver) "Insufficient data (Carried Over from $segmentGender)" else "${"%.1f".format(segGenderRes.medianF0)} Hz"}\n" +
                        "   Resulting Gender: $segmentGender")

                val targetDurationMs = (seg.endMs - seg.startMs).coerceAtLeast(300L)

                // Pre-render English audio with segment-matched gender voice
                val enFile = File(renderedDir, "seg_${idx}_en.wav")
                ttsManager.selectVoiceForGender(Language.ENGLISH, segmentGender)
                val enRenderedMs = ttsManager.synthesizeToFile(seg.english, enFile)
                val enSpeedRatio = if (enRenderedMs > 0) {
                    (enRenderedMs.toFloat() / targetDurationMs.toFloat()).coerceIn(0.75f, 1.5f)
                } else 1.0f

                // Pre-render Telugu audio with segment-matched gender voice
                val teFile = File(renderedDir, "seg_${idx}_te.wav")
                ttsManager.selectVoiceForGender(Language.TELUGU, segmentGender)
                val teRenderedMs = ttsManager.synthesizeToFile(seg.telugu, teFile)
                val teSpeedRatio = if (teRenderedMs > 0) {
                    (teRenderedMs.toFloat() / targetDurationMs.toFloat()).coerceIn(0.75f, 1.5f)
                } else 1.0f

                finalProcessedSegments.add(
                    seg.copy(
                        gender = segmentGender,
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

            val totalPipelineMs = System.currentTimeMillis() - pipelineStart
            DiagnosticLogger.log(TAG, "================ TOTAL PIPELINE EXECUTION TIME ================")
            DiagnosticLogger.log(TAG, "Completed full Two-Tier pipeline in ${"%.2f".format(totalPipelineMs / 1000.0)}s")
            DiagnosticLogger.log(TAG, "================================================================")

            startTtsPolling()

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "Pipeline Exception", e)
            _processingState.value = ProcessingState.Error(e.localizedMessage ?: "Unknown pipeline execution error.")
        }
    }

    private fun loadInstrumentalIfAvailable(uri: Uri) {
        val file = cache.instrumentalFileFor(uri)
        if (file.exists()) instrumental.loadFromFile(file)
    }

    // ── Language switching & Voice re-check ────────────────────────────────────
    fun switchLanguage(language: Language) {
        if (_currentLanguage.value == language) return
        _currentLanguage.value = language
        segmentAudioPlayer.stop()
        ttsManager.selectVoiceForGender(language, _detectedGender.value)
        recheckVoiceAvailability()
        applyVolumeForLanguage(language)
        lastSpokenIndex = -1
        if (_processingState.value == ProcessingState.Ready) startTtsPolling()
    }

    fun recheckVoiceAvailability() {
        val status = ttsManager.checkVoiceAvailability(_currentLanguage.value)
        _voiceAvailabilityStatus.value = status
        _missingVoiceWarning.value = !status.hasGenderMatchedVoices
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

    // ── Real-Time Segment Audio Polling Engine ────────────────────────────────
    private fun startTtsPolling() {
        ttsPollingJob?.cancel()
        ttsPollingJob = viewModelScope.launch {
            while (isActive) {
                val currentLang = _currentLanguage.value
                if (currentLang != Language.HINDI && exoPlayer.isPlaying) {
                    val pos = exoPlayer.currentPosition
                    val segments = cache.load(_videoUri.value ?: Uri.EMPTY) ?: emptyList()

                    val activeIdx = segments.indexOfFirst { seg ->
                        pos >= (seg.startMs - TRIGGER_TOLERANCE_MS) && pos <= (seg.endMs + TRIGGER_TOLERANCE_MS)
                    }

                    if (activeIdx != -1 && activeIdx != lastSpokenIndex) {
                        lastSpokenIndex = activeIdx
                        val activeSeg = segments[activeIdx]

                        val (audioPath, speedRatio) = when (currentLang) {
                            Language.ENGLISH -> Pair(activeSeg.englishAudioPath, activeSeg.englishSpeedRatio)
                            Language.TELUGU  -> Pair(activeSeg.teluguAudioPath,  activeSeg.teluguSpeedRatio)
                            Language.HINDI   -> Pair("", 1.0f)
                        }

                        if (audioPath.isNotBlank() && File(audioPath).exists()) {
                            segmentAudioPlayer.playSegment(File(audioPath), speedRatio)
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pipelineJob?.cancel()
        ttsPollingJob?.cancel()
        exoPlayer.release()
        segmentAudioPlayer.stop()
        instrumental.release()
        voskRecognizer.close()
        ttsManager.shutdown()
    }
}
