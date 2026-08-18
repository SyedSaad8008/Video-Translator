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
import com.example.videotranslator.audio.AudioSynchronizer
import com.example.videotranslator.audio.InstrumentalPlayer
import com.example.videotranslator.audio.NoiseSuppressor
import com.example.videotranslator.audio.SegmentAudioPlayer
import com.example.videotranslator.cache.SegmentCache
import com.example.videotranslator.library.VideoLibraryRepository
import com.example.videotranslator.library.VideoRun
import com.example.videotranslator.model.Gender
import com.example.videotranslator.model.Language
import com.example.videotranslator.model.ProcessingState
import com.example.videotranslator.model.Speaker
import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.model.VoiceMode
import com.example.videotranslator.models.ModelManager
import com.example.videotranslator.speaker.SpeakerManager
import com.example.videotranslator.speaker.VoiceGenderClassifier
import com.example.videotranslator.speech.WhisperRecognizer
import com.example.videotranslator.translation.TranslationManager
import com.example.videotranslator.tts.TtsManager
import com.example.videotranslator.tts.VoiceAvailabilityStatus
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

private const val TAG = "VideoPlayerVM"
private const val POLL_INTERVAL_MS = 100L
private const val TRIGGER_TOLERANCE_MS = 200L
private const val EXOPLAYER_FULL = 1.0f
private const val EXOPLAYER_MUTED = 0.0f

class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    // ── Dependencies ──────────────────────────────────────────────────────────
    val modelManager           = ModelManager(application, viewModelScope)
    private val cache          = SegmentCache(application)
    private val libraryRepo    = VideoLibraryRepository(application)
    private val audioExtractor = AudioExtractor(application)
    private val noiseSuppressor = NoiseSuppressor()
    private val whisperRecognizer = WhisperRecognizer(application)
    private val translationManager = TranslationManager(application)
    private val speakerManager = SpeakerManager()
    private val genderClassifier = VoiceGenderClassifier()
    private val audioSynchronizer = AudioSynchronizer()
    val ttsManager             = TtsManager(application)
    private val segmentAudioPlayer = SegmentAudioPlayer()
    private val instrumental   = InstrumentalPlayer(viewModelScope)

    // ── State Flows ───────────────────────────────────────────────────────────
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(application).build()

    private val _currentLanguage = MutableStateFlow(Language.HINDI)
    val currentLanguage: StateFlow<Language> = _currentLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow(Language.ENGLISH)
    val targetLanguage: StateFlow<Language> = _targetLanguage.asStateFlow()

    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()

    private val _detectedGender = MutableStateFlow(Gender.MALE)
    val detectedGender: StateFlow<Gender> = _detectedGender.asStateFlow()

    private val _voiceMode = MutableStateFlow(VoiceMode.GENDER_MATCHED)
    val voiceMode: StateFlow<VoiceMode> = _voiceMode.asStateFlow()

    private val _lowConfFallbackGender = MutableStateFlow(Gender.MALE)
    val lowConfFallbackGender: StateFlow<Gender> = _lowConfFallbackGender.asStateFlow()

    private val _speakers = MutableStateFlow<List<Speaker>>(emptyList())
    val speakers: StateFlow<List<Speaker>> = _speakers.asStateFlow()

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

    /** Null = auto-detect, non-null = user manually selected source language. */
    private val _manualSourceLanguage = MutableStateFlow<Language?>(null)
    val manualSourceLanguage: StateFlow<Language?> = _manualSourceLanguage.asStateFlow()

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
                if (isPlaying) {
                    segmentAudioPlayer.resume()
                } else {
                    segmentAudioPlayer.pause()
                    instrumental.pause()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                lastSpokenIndex = -1
                segmentAudioPlayer.stop()
                instrumental.seekTo(newPosition.positionMs)
            }
        })

        // Pre-warm models asynchronously
        prewarmJob = viewModelScope.launch {
            try {
                whisperRecognizer.loadModel()
                translationManager.downloadModels()
                DiagnosticLogger.log(TAG, "AI Models pre-warm complete ✓")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w(TAG, "Pre-warm notice: ${e.message}") }
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            _libraryRuns.value = libraryRepo.getAllRuns()
        }
    }

    fun setManualSourceLanguage(lang: Language?) {
        _manualSourceLanguage.value = lang
        DiagnosticLogger.log(TAG, "Source language configured: ${lang?.displayName ?: "Auto Detect"}")
    }

    fun setTargetLanguage(lang: Language) {
        if (_manualSourceLanguage.value != lang) {
            _targetLanguage.value = lang
        }
    }

    fun setVoiceMode(mode: VoiceMode) {
        _voiceMode.value = mode
    }

    fun setLowConfFallback(gender: Gender) {
        _lowConfFallbackGender.value = gender
    }

    // ── Video Picking & Run Creation ──────────────────────────────────────────
    fun onVideoPicked(uri: Uri) {
        val newRunId = UUID.randomUUID().toString()
        val title = uri.lastPathSegment?.replace(Regex("[^a-zA-Z0-9_.-]"), " ")?.take(30) ?: "Video Run"
        DiagnosticLogger.log(TAG, "New video selected: $uri (RunId: $newRunId)")

        val newRun = VideoRun(
            runId = newRunId,
            uriString = uri.toString(),
            videoTitle = title,
            timestampMs = System.currentTimeMillis(),
            status = "Processing"
        )
        libraryRepo.saveRun(newRun)
        refreshLibrary()

        cancelPipeline()

        _videoUri.value = uri
        _currentRunId.value = newRunId
        _processingState.value = ProcessingState.Idle

        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        viewModelScope.launch(Dispatchers.Main) { applyVolumeForLanguage(_currentLanguage.value) }

        pipelineJob = viewModelScope.launch { runPipeline(uri, newRunId) }
    }

    fun loadPastRun(run: VideoRun) {
        DiagnosticLogger.log(TAG, "Loading past run from library: ${run.videoTitle} (${run.runId})")
        cancelPipeline()

        val uri = Uri.parse(run.uriString)
        _videoUri.value = uri
        _currentRunId.value = run.runId

        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        viewModelScope.launch(Dispatchers.Main) { applyVolumeForLanguage(_currentLanguage.value) }

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
                DiagnosticLogger.log(TAG, "Past run [${run.runId}] loaded from cache ✓")
            } else {
                runPipeline(uri, run.runId)
            }
        }
    }

    fun deleteRun(runId: String) {
        libraryRepo.deleteRun(runId)
        refreshLibrary()
        if (_currentRunId.value == runId) {
            cancelPipeline()
            exoPlayer.stop()
            _videoUri.value = null
            _currentRunId.value = null
            _processingState.value = ProcessingState.Idle
        }
    }

    fun cancelPipeline() {
        pipelineJob?.cancel()
        ttsPollingJob?.cancel()
        segmentAudioPlayer.stop()
        instrumental.stop()
        lastSpokenIndex = -1
    }

    fun retryPipeline() {
        val uri = _videoUri.value ?: return
        val runId = _currentRunId.value ?: return
        DiagnosticLogger.log(TAG, "Retrying pipeline for run [$runId]")
        cancelPipeline()
        pipelineJob = viewModelScope.launch { runPipeline(uri, runId) }
    }

    // ── Full On-Device AI Pipeline Execution ─────────────────────────────────
    private suspend fun runPipeline(uri: Uri, runId: String) = withContext(Dispatchers.Default) {
        val pipelineStart = System.currentTimeMillis()
        DiagnosticLogger.log(TAG, "Starting 100% Offline AI Video Translation Pipeline for run [$runId]")

        try {
            // ── Stage 1: Audio Extraction & Targeted DSP Preprocessing ──
            _processingState.value = ProcessingState.Loading("1/7 Extracting audio & applying targeted DSP noise filter…", 0.12f, 1, 7)
            val monoFile = cache.pcmFileForRun(runId)
            val instrumentalFile = cache.instrumentalFileForRun(runId)

            val extractionResult = if (monoFile.exists()) {
                val mono = audioExtractor.loadMonoFromCache(monoFile)
                val instr = if (instrumentalFile.exists()) audioExtractor.loadInstrumentalFromCache(instrumentalFile) else null
                AudioExtractor.ExtractionResult(mono, instr)
            } else {
                audioExtractor.extractToFiles(uri, monoFile, instrumentalFile)
            }

            if (extractionResult.instrumental != null) instrumental.loadFromFile(instrumentalFile)

            val noiseResult = noiseSuppressor.suppressNoiseWithResult(extractionResult.mono)
            val cleanedMono = noiseResult.cleanedPcm
            val transientMask = noiseResult.transientMask

            // ── Stage 2: Language Identification & Whisper Speech-to-Text ──
            _processingState.value = ProcessingState.Loading("2/7 Identifying language & transcribing speech with Whisper…", 0.28f, 2, 7)

            val manualLang = _manualSourceLanguage.value
            val sourceLang = if (manualLang != null) {
                DiagnosticLogger.log(TAG, "STAGE 2 - Manual source language selected: $manualLang")
                manualLang
            } else {
                whisperRecognizer.probeLanguage(cleanedMono)
            }
            _detectedSourceLanguage.value = sourceLang
            _currentLanguage.value = sourceLang
            applyVolumeForLanguage(sourceLang)

            val rawSegments = whisperRecognizer.recognize(cleanedMono, sourceLang)
            if (rawSegments.isEmpty()) {
                val msg = "No speech detected in this video. Please select a video containing spoken dialogue."
                DiagnosticLogger.log(TAG, msg)
                _processingState.value = ProcessingState.Error(msg)
                return@withContext
            }

            // ── Stage 3: Speaker Tracking & Multi-Signal Gender Classification ──
            _processingState.value = ProcessingState.Loading("3/7 Tracking speakers & analyzing voice characteristics…", 0.45f, 3, 7)

            val genderDetections = mutableListOf<VoiceGenderClassifier.ClassificationResult>()
            val f0Estimates = mutableListOf<Float>()

            for (seg in rawSegments) {
                val startSample = ((seg.startMs * 16000) / 1000).toInt().coerceIn(0, cleanedMono.size)
                val endSample   = ((seg.endMs * 16000) / 1000).toInt().coerceIn(startSample, cleanedMono.size)
                val segPcm      = if (endSample > startSample) cleanedMono.copyOfRange(startSample, endSample) else ShortArray(0)

                val res = genderClassifier.classifyVoice(segPcm, transientMask = transientMask)
                genderDetections.add(res)
                f0Estimates.add(res.medianF0)
            }

            val smoothedGenders = genderClassifier.smoothSequence(genderDetections)
            val (speakerTrackedSegments, trackedSpeakers) = speakerManager.trackSpeakers(rawSegments, f0Estimates)
            _speakers.value = trackedSpeakers

            // Determine dominant video gender
            val maleCount = smoothedGenders.count { it.gender == Gender.MALE }
            val femaleCount = smoothedGenders.count { it.gender == Gender.FEMALE }
            _detectedGender.value = if (femaleCount > maleCount) Gender.FEMALE else Gender.MALE

            // ── Stage 4: Context-Aware NLLB-200 Neural Translation ──
            _processingState.value = ProcessingState.Loading("4/7 Translating dialogue with NLLB-200 (${sourceLang.displayName} → other languages)…", 0.65f, 4, 7)
            val translatedSegments = translationManager.translate(speakerTrackedSegments, sourceLang)

            // ── Stage 5: Gender-Matched Voice Synthesis (Piper / Local TTS) ──
            _processingState.value = ProcessingState.Loading("5/7 Pre-rendering gender-matched voice dubs…", 0.82f, 5, 7)
            val renderedDir = cache.renderedAudioDirForRun(runId)
            val finalSegments = mutableListOf<TranslationSegment>()
            val renderedDurations = mutableListOf<Long>()

            val mode = _voiceMode.value
            val fallback = _lowConfFallbackGender.value

            for ((idx, seg) in translatedSegments.withIndex()) {
                val detectedVoiceGender = smoothedGenders[idx].gender
                val voiceGenderToUse = when (mode) {
                    VoiceMode.FORCE_MALE -> Gender.MALE
                    VoiceMode.FORCE_FEMALE -> Gender.FEMALE
                    VoiceMode.ORIGINAL_SPEAKER -> _detectedGender.value
                    VoiceMode.GENDER_MATCHED -> if (detectedVoiceGender == Gender.UNKNOWN) fallback else detectedVoiceGender
                }

                val targetDurationMs = (seg.endMs - seg.startMs).coerceAtLeast(300L)

                // Pre-render English
                val enFile = File(renderedDir, "seg_${idx}_en.wav")
                ttsManager.selectVoiceForGender(Language.ENGLISH, voiceGenderToUse)
                val enMs = ttsManager.synthesizeToFile(seg.english, enFile)
                val enRatio = if (enMs > 0) (enMs.toFloat() / targetDurationMs).coerceIn(0.75f, 1.5f) else 1.0f

                // Pre-render Telugu
                val teFile = File(renderedDir, "seg_${idx}_te.wav")
                ttsManager.selectVoiceForGender(Language.TELUGU, voiceGenderToUse)
                val teMs = ttsManager.synthesizeToFile(seg.telugu, teFile)
                val teRatio = if (teMs > 0) (teMs.toFloat() / targetDurationMs).coerceIn(0.75f, 1.5f) else 1.0f

                // Pre-render Hindi
                val hiFile = File(renderedDir, "seg_${idx}_hi.wav")
                ttsManager.selectVoiceForGender(Language.HINDI, voiceGenderToUse)
                val hiMs = ttsManager.synthesizeToFile(seg.hindi, hiFile)
                val hiRatio = if (hiMs > 0) (hiMs.toFloat() / targetDurationMs).coerceIn(0.75f, 1.5f) else 1.0f

                renderedDurations.add(enMs)

                finalSegments.add(
                    seg.copy(
                        gender = voiceGenderToUse,
                        voiceGender = voiceGenderToUse.name.lowercase(),
                        genderConfidence = smoothedGenders[idx].confidence,
                        englishAudioPath = enFile.absolutePath,
                        englishSpeedRatio = enRatio,
                        teluguAudioPath = teFile.absolutePath,
                        teluguSpeedRatio = teRatio,
                        hindiAudioPath = hiFile.absolutePath,
                        hindiSpeedRatio = hiRatio,
                        detectedSourceLanguage = sourceLang.name
                    )
                )
            }

            // ── Stage 6: Audio-Video Timing Synchronization ──
            _processingState.value = ProcessingState.Loading("6/7 Synchronizing audio timestamps & lip-sync…", 0.94f, 6, 7)
            audioSynchronizer.synchronizeSegments(finalSegments, renderedDurations)

            // ── Stage 7: Caching & Library Update ──
            _processingState.value = ProcessingState.Loading("7/7 Saving translation session to local library…", 0.98f, 7, 7)
            cache.saveRun(runId, finalSegments)

            libraryRepo.getRun(runId)?.copy(
                status = "Ready",
                segmentCount = finalSegments.size,
                detectedGender = _detectedGender.value.name,
                detectedSourceLanguage = sourceLang.name
            )?.let { libraryRepo.saveRun(it) }
            refreshLibrary()

            _processingState.value = ProcessingState.Ready

            val totalMs = System.currentTimeMillis() - pipelineStart
            DiagnosticLogger.log(TAG, "Completed full 100% Offline AI Video Translation in ${"%.2f".format(totalMs / 1000.0)}s for run [$runId] ✓")

            startTtsPolling()

        } catch (e: CancellationException) {
            DiagnosticLogger.log(TAG, "Pipeline canceled by user.")
            _processingState.value = ProcessingState.Idle
            throw e
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "Pipeline execution error: ${e.localizedMessage}", e)
            libraryRepo.getRun(runId)?.copy(status = "Error")?.let { libraryRepo.saveRun(it) }
            refreshLibrary()
            _processingState.value = ProcessingState.Error(e.localizedMessage ?: "Unknown pipeline execution error.")
        }
    }

    // ── Language Switching & Real-Time Track Synchronization ─────────────────
    fun switchLanguage(language: Language) {
        if (_currentLanguage.value == language) return
        _currentLanguage.value = language
        segmentAudioPlayer.stop()
        viewModelScope.launch {
            ttsManager.selectVoiceForGender(language, _detectedGender.value)
            recheckVoiceAvailability()
            applyVolumeForLanguage(language)
        }
        lastSpokenIndex = -1
        if (_processingState.value == ProcessingState.Ready) startTtsPolling()
    }

    fun recheckVoiceAvailability() {
        val status = ttsManager.checkVoiceAvailability(_currentLanguage.value)
        _voiceAvailabilityStatus.value = status
        _missingVoiceWarning.value = !status.hasGenderMatchedVoices
    }

    private suspend fun applyVolumeForLanguage(lang: Language) = withContext(Dispatchers.Main) {
        val src = _detectedSourceLanguage.value
        if (lang == src) {
            exoPlayer.volume = EXOPLAYER_FULL
            instrumental.stop()
        } else {
            exoPlayer.volume = EXOPLAYER_MUTED
            instrumental.stop()
        }
    }

    private fun startTtsPolling() {
        ttsPollingJob?.cancel()
        ttsPollingJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                val currentLang = _currentLanguage.value
                val srcLang     = _detectedSourceLanguage.value
                val runId       = _currentRunId.value

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
        cancelPipeline()
        exoPlayer.release()
        segmentAudioPlayer.stop()
        instrumental.release()
        whisperRecognizer.close()
        ttsManager.shutdown()
    }
}
