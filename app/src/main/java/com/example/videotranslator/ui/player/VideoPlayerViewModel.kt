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
import com.example.videotranslator.library.VideoLibraryRepository
import com.example.videotranslator.library.VideoRun
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
import java.util.UUID

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
    private val libraryRepo        = VideoLibraryRepository(application)
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

    private val _currentRunId = MutableStateFlow<String?>(null)
    val currentRunId: StateFlow<String?> = _currentRunId.asStateFlow()

    private val _libraryRuns = MutableStateFlow<List<VideoRun>>(emptyList())
    val libraryRuns: StateFlow<List<VideoRun>> = _libraryRuns.asStateFlow()

    private val _detectedSourceLanguage = MutableStateFlow(Language.HINDI)
    val detectedSourceLanguage: StateFlow<Language> = _detectedSourceLanguage.asStateFlow()

    private var pipelineJob: Job? = null
    private var ttsPollingJob: Job? = null
    private var prewarmJob: Job? = null
    private var lastSpokenIndex = -1

    init {
        DiagnosticLogger.init(application)
        DiagnosticLogger.log(TAG, "VideoPlayerViewModel initialized.")

        refreshLibrary()

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

    fun refreshLibrary() {
        viewModelScope.launch {
            _libraryRuns.value = libraryRepo.getAllRuns()
        }
    }

    // ── Video picking (Creates a brand-new unique run ID) ─────────────────────
    fun onVideoPicked(uri: Uri) {
        val newRunId = UUID.randomUUID().toString()
        val title = uri.lastPathSegment?.replace(Regex("[^a-zA-Z0-9_.-]"), " ")?.take(30) ?: "Video Run"
        DiagnosticLogger.log(TAG, "New video picked: $uri (RunId: $newRunId)")

        val newRun = VideoRun(
            runId = newRunId,
            uriString = uri.toString(),
            videoTitle = title,
            timestampMs = System.currentTimeMillis(),
            status = "Processing"
        )
        libraryRepo.saveRun(newRun)
        refreshLibrary()

        pipelineJob?.cancel()
        ttsPollingJob?.cancel()
        segmentAudioPlayer.stop()
        instrumental.stop()
        lastSpokenIndex = -1

        _videoUri.value = uri
        _currentRunId.value = newRunId
        _processingState.value = ProcessingState.Idle

        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        applyVolumeForLanguage(_currentLanguage.value)

        pipelineJob = viewModelScope.launch { runPipeline(uri, newRunId) }
    }

    // ── Load an existing run from persistent library ──────────────────────────
    fun loadPastRun(run: VideoRun) {
        DiagnosticLogger.log(TAG, "Loading past run from library: ${run.videoTitle} (${run.runId})")
        pipelineJob?.cancel()
        ttsPollingJob?.cancel()
        segmentAudioPlayer.stop()
        instrumental.stop()
        lastSpokenIndex = -1

        val uri = Uri.parse(run.uriString)
        _videoUri.value = uri
        _currentRunId.value = run.runId

        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        applyVolumeForLanguage(_currentLanguage.value)

        viewModelScope.launch {
            val cachedSegs = cache.loadRun(run.runId)
            if (cachedSegs != null && cachedSegs.isNotEmpty()) {
                val instrFile = cache.instrumentalFileForRun(run.runId)
                if (instrFile.exists()) instrumental.loadFromFile(instrFile)
                
                _detectedGender.value = if (run.detectedGender == "FEMALE") Gender.FEMALE else Gender.MALE
                _detectedSourceLanguage.value = when (run.detectedSourceLanguage) {
                    "TELUGU"  -> Language.TELUGU
                    "ENGLISH" -> Language.ENGLISH
                    else      -> Language.HINDI
                }
                _processingState.value = ProcessingState.Ready
                startTtsPolling()
                DiagnosticLogger.log(TAG, "Past run [${run.runId}] loaded instantly from cache ✓")
            } else {
                // If files missing, re-run pipeline for this runId
                runPipeline(uri, run.runId)
            }
        }
    }

    fun deleteRun(runId: String) {
        libraryRepo.deleteRun(runId)
        refreshLibrary()
        if (_currentRunId.value == runId) {
            pipelineJob?.cancel()
            ttsPollingJob?.cancel()
            segmentAudioPlayer.stop()
            instrumental.stop()
            exoPlayer.stop()
            _videoUri.value = null
            _currentRunId.value = null
            _processingState.value = ProcessingState.Idle
        }
    }

    fun retryPipeline() {
        val uri = _videoUri.value ?: return
        val runId = _currentRunId.value ?: return
        DiagnosticLogger.log(TAG, "User triggered retry for run [$runId]: $uri")
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch { runPipeline(uri, runId) }
    }

    // ── Pipeline Execution ────────────────────────────────────────────────────
    private suspend fun runPipeline(uri: Uri, runId: String) {
        val pipelineStart = System.currentTimeMillis()
        DiagnosticLogger.log(TAG, "Starting full pipeline execution for run [$runId]: $uri")
        try {
            // ── 1. Wait for pre-warm ──────────────────────────────────
            _processingState.value = ProcessingState.Loading("Initializing AI models…", 0.05f)
            prewarmJob?.join()

            // ── 2. Extract audio (mono + instrumental) ────────────────
            _processingState.value = ProcessingState.Loading("Extracting audio from video…", 0.15f)
            val monoFile         = cache.pcmFileForRun(runId)
            val instrumentalFile = cache.instrumentalFileForRun(runId)

            val result = if (monoFile.exists()) {
                val mono  = audioExtractor.loadMonoFromCache(monoFile)
                val instr = if (instrumentalFile.exists())
                    audioExtractor.loadInstrumentalFromCache(instrumentalFile) else null
                AudioExtractor.ExtractionResult(mono, instr)
            } else {
                audioExtractor.extractToFiles(uri, monoFile, instrumentalFile)
            }

            if (result.instrumental != null) instrumental.loadFromFile(instrumentalFile)

            // ── 2b. Multi-Segment Targeted DSP Noise Suppression ─────────
            _processingState.value = ProcessingState.Loading("Applying targeted DSP noise suppression (Fan, Wind HPF, Horn)…", 0.22f)
            val noiseResult = noiseSuppressor.suppressNoiseWithResult(result.mono)
            val cleanedMono = noiseResult.cleanedPcm
            val transientMask = noiseResult.transientMask

            // ── 2c. Global Voice Gender Detection (Baseline) ──────────────
            _processingState.value = ProcessingState.Loading("Analyzing global audio pitch…", 0.28f)
            val globalGenderResult = genderDetector.detectGender(
                pcmMono = cleanedMono,
                transientMask = transientMask
            )
            _detectedGender.value = globalGenderResult.gender
            DiagnosticLogger.log(TAG, "Global Audio Gender Baseline: ${globalGenderResult.gender} (Median F0 = ${"%.1f".format(globalGenderResult.medianF0)} Hz)")

            // ── 3. Acoustic STT Dual-Probe Source Language Detection ───
            _processingState.value = ProcessingState.Loading("Probing acoustic speech (Hindi vs English vs Telugu)…", 0.38f)
            val sourceLang = voskRecognizer.probeLanguage(cleanedMono)
            _detectedSourceLanguage.value = sourceLang
            _currentLanguage.value = sourceLang
            applyVolumeForLanguage(sourceLang)
            DiagnosticLogger.log(TAG, "STAGE 3 - Probed source language: $sourceLang")

            // ── 4. Full Transcribe (Vosk) on Cleaned Audio for Detected Language ──
            _processingState.value = ProcessingState.Loading("Transcribing speech for $sourceLang (High-Precision STT)…", 0.48f)
            val rawSegments = voskRecognizer.recognise(cleanedMono, sourceLang)
            DiagnosticLogger.log(TAG, "Vosk recognized ${rawSegments.size} segments for $sourceLang")

            if (rawSegments.isEmpty()) {
                val msg = "No spoken speech detected in this video. Please select a video with clear spoken speech."
                DiagnosticLogger.log(TAG, "Pipeline Stopped: $msg")
                libraryRepo.getRun(runId)?.copy(status = "Error")?.let { libraryRepo.saveRun(it) }
                refreshLibrary()
                _processingState.value = ProcessingState.Error(msg)
                return
            }

            // ── 5. Download ML Kit translation & verification models ─────
            _processingState.value = ProcessingState.Loading("Loading neural translation models…", 0.62f)
            val modelResult = translationManager.downloadModels()
            if (modelResult.isFailure) {
                val err = modelResult.exceptionOrNull()?.message ?: "Translation model download failed."
                DiagnosticLogger.log(TAG, "Pipeline Error: $err")
                libraryRepo.getRun(runId)?.copy(status = "Error")?.let { libraryRepo.saveRun(it) }
                refreshLibrary()
                _processingState.value = ProcessingState.Error(err)
                return
            }

            // ── 6. Contextual Translation & Back-Translation Verification ─
            _processingState.value = ProcessingState.Loading("Translating & verifying (${sourceLang} → other languages)…", 0.72f)
            val translatedSegments = translationManager.translate(rawSegments, sourceLang)

            // ── 5b. Phase 1: Multi-Signal Ensemble Gender Analysis (all segments) ─
            val totalSegs = translatedSegments.size.coerceAtLeast(1)
            var runningGender = globalGenderResult.gender

            _processingState.value = ProcessingState.Loading(
                "Analyzing speaker gender (multi-signal ensemble)…", 0.74f
            )

            val rawDetections = mutableListOf<GenderDetector.DetectionResult>()
            for (idx in translatedSegments.indices) {
                val seg = translatedSegments[idx]
                val startSample = ((seg.startMs * 16000) / 1000).toInt().coerceIn(0, cleanedMono.size)
                val endSample   = ((seg.endMs * 16000) / 1000).toInt().coerceIn(startSample, cleanedMono.size)
                val segPcm      = if (endSample > startSample) cleanedMono.copyOfRange(startSample, endSample) else ShortArray(0)
                val prevEndMs   = if (idx > 0) translatedSegments[idx - 1].endMs else 0L

                val res = genderDetector.detectGender(
                    pcmMono = segPcm,
                    fallbackGender = runningGender,
                    fullPcmMono = cleanedMono,
                    segmentStartMs = seg.startMs,
                    segmentEndMs = seg.endMs,
                    previousSegmentEndMs = prevEndMs,
                    transientMask = transientMask
                )
                runningGender = res.gender
                rawDetections.add(res)
            }

            // ── 5c. Phase 2: Temporal Consistency Smoothing ─────────────────
            _processingState.value = ProcessingState.Loading(
                "Applying temporal consistency smoothing…", 0.78f
            )
            val smoothedDetections = genderDetector.smoothSequence(rawDetections)

            // Log full ensemble results for every segment
            for (idx in translatedSegments.indices) {
                val seg = translatedSegments[idx]
                val raw = rawDetections[idx]
                val sm  = smoothedDetections[idx]
                DiagnosticLogger.log(TAG, "ENSEMBLE GENDER [$idx] (${seg.startMs}ms→${seg.endMs}ms):\n" +
                        "   F0=${"%.1f".format(raw.medianF0)}Hz → ${raw.f0Vote}\n" +
                        "   SC=${"%.0f".format(raw.spectralCentroid)}Hz → ${raw.scVote}\n" +
                        "   HNR=${"%.1f".format(raw.hnr)}dB\n" +
                        "   EnsConf=${"%.2f".format(raw.ensembleConfidence)}, Voiced=${raw.totalVoicedFrames}\n" +
                        "   Raw=${raw.gender}${if (sm.wasSmoothed) " → SMOOTHED TO ${sm.gender}" else ""}\n" +
                        "   Final Gender: ${sm.gender}")
            }

            // ── 5d. Phase 3: Duration-Matched TTS Pre-Rendering ─────────────
            val renderedDir = cache.renderedAudioDirForRun(runId)
            val finalProcessedSegments = mutableListOf<TranslationSegment>()

            for (idx in translatedSegments.indices) {
                val seg = translatedSegments[idx]
                val segmentGender = smoothedDetections[idx].gender
                val progressStep = 0.80f + (0.16f * (idx.toFloat() / totalSegs.toFloat()))
                _processingState.value = ProcessingState.Loading(
                    "Pre-rendering dubbed audio (${idx + 1}/$totalSegs)…",
                    progressStep
                )

                val targetDurationMs = (seg.endMs - seg.startMs).coerceAtLeast(300L)

                // Pre-render all three language dub tracks
                val enFile = File(renderedDir, "seg_${idx}_en.wav")
                ttsManager.selectVoiceForGender(Language.ENGLISH, segmentGender)
                val enRenderedMs = ttsManager.synthesizeToFile(seg.english, enFile)
                val enSpeedRatio = if (enRenderedMs > 0) (enRenderedMs.toFloat() / targetDurationMs).coerceIn(0.75f, 1.5f) else 1.0f

                val teFile = File(renderedDir, "seg_${idx}_te.wav")
                ttsManager.selectVoiceForGender(Language.TELUGU, segmentGender)
                val teRenderedMs = ttsManager.synthesizeToFile(seg.telugu, teFile)
                val teSpeedRatio = if (teRenderedMs > 0) (teRenderedMs.toFloat() / targetDurationMs).coerceIn(0.75f, 1.5f) else 1.0f

                val hiFile = File(renderedDir, "seg_${idx}_hi.wav")
                ttsManager.selectVoiceForGender(Language.HINDI, segmentGender)
                val hiRenderedMs = ttsManager.synthesizeToFile(seg.hindi, hiFile)
                val hiSpeedRatio = if (hiRenderedMs > 0) (hiRenderedMs.toFloat() / targetDurationMs).coerceIn(0.75f, 1.5f) else 1.0f

                finalProcessedSegments.add(
                    seg.copy(
                        gender = segmentGender,
                        englishAudioPath = enFile.absolutePath,
                        englishSpeedRatio = enSpeedRatio,
                        teluguAudioPath = teFile.absolutePath,
                        teluguSpeedRatio = teSpeedRatio,
                        hindiAudioPath = hiFile.absolutePath,
                        hindiSpeedRatio = hiSpeedRatio,
                        detectedSourceLanguage = sourceLang.name
                    )
                )
            }

            // ── 6. Cache + publish ────────────────────────────────────
            _processingState.value = ProcessingState.Loading("Saving cached audio…", 0.98f)
            cache.saveRun(runId, finalProcessedSegments)

            // Update persistent library run
            libraryRepo.getRun(runId)?.copy(
                status = "Ready",
                segmentCount = finalProcessedSegments.size,
                detectedGender = globalGenderResult.gender.name,
                detectedSourceLanguage = sourceLang.name
            )?.let { libraryRepo.saveRun(it) }
            refreshLibrary()

            _processingState.value = ProcessingState.Ready

            val totalPipelineMs = System.currentTimeMillis() - pipelineStart
            DiagnosticLogger.log(TAG, "================ TOTAL PIPELINE EXECUTION TIME ================")
            DiagnosticLogger.log(TAG, "Completed full Two-Tier pipeline in ${"%.2f".format(totalPipelineMs / 1000.0)}s for run [$runId]")
            DiagnosticLogger.log(TAG, "================================================================")

            startTtsPolling()

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "Pipeline Exception for run [$runId]", e)
            libraryRepo.getRun(runId)?.copy(status = "Error")?.let { libraryRepo.saveRun(it) }
            refreshLibrary()
            _processingState.value = ProcessingState.Error(e.localizedMessage ?: "Unknown pipeline execution error.")
        }
    }

    private fun loadInstrumentalIfAvailable(uri: Uri) {
        val runId = _currentRunId.value
        if (runId != null) {
            val file = cache.instrumentalFileForRun(runId)
            if (file.exists()) instrumental.loadFromFile(file)
        }
    }

    // ── Language switching & Voice re-check ────────────────────────────────────
    fun switchLanguage(language: Language) {
        if (_currentLanguage.value == language) return
        _currentLanguage.value = language
        segmentAudioPlayer.stop()
        viewModelScope.launch {
            ttsManager.selectVoiceForGender(language, _detectedGender.value)
            recheckVoiceAvailability()
        }
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
        val src = _detectedSourceLanguage.value
        if (lang == src) {
            // Playing original audio — full video, mute TTS overlay
            exoPlayer.volume = EXOPLAYER_FULL
            instrumental.stop()
        } else {
            // Playing dubbed translation — mute video, play TTS + music overlay
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
                val srcLang     = _detectedSourceLanguage.value
                val runId = _currentRunId.value

                // When playing original language, ExoPlayer handles audio — no TTS overlay needed
                if (currentLang != srcLang && exoPlayer.isPlaying && runId != null) {
                    val pos = exoPlayer.currentPosition
                    val segments = cache.loadRun(runId) ?: emptyList()

                    val activeIdx = segments.indexOfFirst { seg ->
                        pos >= (seg.startMs - TRIGGER_TOLERANCE_MS) && pos <= (seg.endMs + TRIGGER_TOLERANCE_MS)
                    }

                    if (activeIdx != -1 && activeIdx != lastSpokenIndex) {
                        lastSpokenIndex = activeIdx
                        val activeSeg = segments[activeIdx]

                        val (audioPath, speedRatio) = when (currentLang) {
                            Language.ENGLISH -> Pair(activeSeg.englishAudioPath, activeSeg.englishSpeedRatio)
                            Language.TELUGU  -> Pair(activeSeg.teluguAudioPath,  activeSeg.teluguSpeedRatio)
                            Language.HINDI   -> Pair(activeSeg.hindiAudioPath,   activeSeg.hindiSpeedRatio)
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
