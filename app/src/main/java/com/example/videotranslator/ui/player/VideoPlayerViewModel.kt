package com.example.videotranslator.ui.player

import android.app.Application
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.videotranslator.ai.pipeline.VideoTranslationPipeline
import com.example.videotranslator.audio.InstrumentalPlayer
import com.example.videotranslator.audio.SegmentAudioPlayer
import com.example.videotranslator.cache.SegmentCache
import com.example.videotranslator.library.VideoLibraryRepository
import com.example.videotranslator.library.VideoRun
import com.example.videotranslator.model.Gender
import com.example.videotranslator.model.Language
import com.example.videotranslator.model.ProcessingState
import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.models.ModelManager
import com.example.videotranslator.tts.TtsManager
import com.example.videotranslator.tts.VoiceAvailabilityStatus
import com.example.videotranslator.util.DiagnosticLogger
import com.example.videotranslator.util.VideoExporter
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

private const val TAG = "VideoPlayerVM"
private const val POLL_INTERVAL_MS = 100L
private const val TRIGGER_TOLERANCE_MS = 200L
private const val EXOPLAYER_FULL = 1.0f
private const val EXOPLAYER_MUTED = 0.0f

class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    // ── Infrastructure & AI Pipeline ───────────────────────────────────────────
    val modelManager = ModelManager(application, viewModelScope)
    private val cache = SegmentCache(application)
    private val libraryRepo = VideoLibraryRepository(application)
    private val pipeline = VideoTranslationPipeline(application, cache, libraryRepo)
    val ttsManager = TtsManager(application)
    private val segmentAudioPlayer = SegmentAudioPlayer()
    private val instrumental = InstrumentalPlayer(viewModelScope)
    private val videoExporter = VideoExporter(application)

    // ── Playback & UI State ───────────────────────────────────────────────────
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(application).build()

    private val _currentLanguage = MutableStateFlow(Language.HINDI)
    val currentLanguage: StateFlow<Language> = _currentLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow(Language.ENGLISH)
    val targetLanguage: StateFlow<Language> = _targetLanguage.asStateFlow()

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
            isLanguageSupported = true,
            totalVoicesCount = 2,
            hasMaleVoice = true,
            hasFemaleVoice = true,
            hasGenderMatchedVoices = true,
            isSingleVoiceOnly = false,
            message = "On-device neural voices active"
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

    private val _manualSourceLanguage = MutableStateFlow<Language?>(null)
    val manualSourceLanguage: StateFlow<Language?> = _manualSourceLanguage.asStateFlow()

    val isAllModelReady: StateFlow<Boolean> = modelManager.isAllReady
    val installProgress = modelManager.installProgress

    private var pipelineJob: Job? = null
    private var ttsPollingJob: Job? = null
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
            }
        })

        viewModelScope.launch {
            pipeline.loadEngines()
        }
    }

    fun setManualSourceLanguage(language: Language?) {
        _manualSourceLanguage.value = language
    }

    fun onVideoPicked(uri: Uri) {
        _videoUri.value = uri
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        startPipeline(uri)
    }

    fun cancelPipeline() {
        pipelineJob?.cancel()
        _processingState.value = ProcessingState.Idle
    }

    fun retryPipeline() {
        val uri = _videoUri.value ?: return
        startPipeline(uri)
    }

    private fun startPipeline(uri: Uri) {
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            try {
                _processingState.value = ProcessingState.Loading(
                    currentStage = 1,
                    totalStages = 7,
                    step = "Starting translation pipeline…",
                    progress = 0.05f
                )

                val result = pipeline.execute(
                    videoUri = uri,
                    manualSourceLanguage = _manualSourceLanguage.value,
                    targetLanguage = _targetLanguage.value,
                    fallbackGender = _detectedGender.value,
                    onProgress = { loadingState ->
                        _processingState.value = loadingState
                    }
                )

                _currentRunId.value = result.runId
                _detectedSourceLanguage.value = result.detectedSourceLanguage
                _detectedGender.value = result.detectedGender
                _processingState.value = ProcessingState.Ready

                // Default playback language to English unless source was English
                val defaultPlayLang = if (result.detectedSourceLanguage == Language.ENGLISH) Language.HINDI else Language.ENGLISH
                _currentLanguage.value = defaultPlayLang

                refreshLibrary()
                applyAudioRouting(defaultPlayLang, result.detectedSourceLanguage)
                startPlaybackPolling(result.segments, defaultPlayLang, result.runId)
                exoPlayer.play()
            } catch (ce: CancellationException) {
                _processingState.value = ProcessingState.Idle
            } catch (e: Exception) {
                _processingState.value = ProcessingState.Error(e.localizedMessage ?: "Translation pipeline failed")
            }
        }
    }

    fun switchLanguage(newLanguage: Language) {
        _currentLanguage.value = newLanguage
        val sourceLang = _detectedSourceLanguage.value
        applyAudioRouting(newLanguage, sourceLang)

        val runId = _currentRunId.value ?: return
        viewModelScope.launch {
            val segments = cache.loadRun(runId) ?: return@launch
            startPlaybackPolling(segments, newLanguage, runId)
        }
    }

    private fun applyAudioRouting(selectedLanguage: Language, sourceLanguage: Language) {
        if (selectedLanguage == sourceLanguage) {
            exoPlayer.volume = EXOPLAYER_FULL
            instrumental.pause()
            segmentAudioPlayer.stop()
        } else {
            exoPlayer.volume = EXOPLAYER_MUTED
            val runId = _currentRunId.value
            if (runId != null) {
                val instFile = cache.instrumentalFileForRun(runId)
                if (instFile.exists() && instFile.length() > 44L) {
                    instrumental.loadFromFile(instFile)
                    instrumental.play(exoPlayer.currentPosition)
                }
            }
        }
    }

    /**
     * Tightly polls audio playback timestamps on Dispatchers.Main to ensure safe ExoPlayer thread access.
     */
    private fun startPlaybackPolling(
        segments: List<TranslationSegment>,
        language: Language,
        runId: String
    ) {
        ttsPollingJob?.cancel()
        lastSpokenIndex = -1

        if (language == _detectedSourceLanguage.value) {
            return
        }

        val renderedDir = cache.renderedAudioDirForRun(runId)

        ttsPollingJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                if (exoPlayer.isPlaying) {
                    val pos = exoPlayer.currentPosition
                    for (i in segments.indices) {
                        val seg = segments[i]
                        val inWindow = pos in (seg.startMs - TRIGGER_TOLERANCE_MS)..(seg.endMs + TRIGGER_TOLERANCE_MS)
                        if (inWindow && i != lastSpokenIndex) {
                            lastSpokenIndex = i
                            val audioFile = File(renderedDir, "dub_${seg.id}.wav")
                            if (audioFile.exists() && audioFile.length() > 44L) {
                                segmentAudioPlayer.playSegment(audioFile, seg.speedRatio)
                            }
                            break
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun loadPastRun(run: VideoRun) {
        _videoUri.value = Uri.parse(run.uriString)
        _currentRunId.value = run.runId
        _detectedGender.value = if (run.detectedGender == "FEMALE") Gender.FEMALE else Gender.MALE
        val src = when (run.detectedSourceLanguage) {
            "ENGLISH" -> Language.ENGLISH
            "TELUGU"  -> Language.TELUGU
            else      -> Language.HINDI
        }
        _detectedSourceLanguage.value = src

        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(run.uriString)))
        exoPlayer.prepare()

        viewModelScope.launch {
            val segments = cache.loadRun(run.runId)
            if (segments != null && segments.isNotEmpty()) {
                _processingState.value = ProcessingState.Ready
                val defaultLang = if (src == Language.ENGLISH) Language.HINDI else Language.ENGLISH
                _currentLanguage.value = defaultLang
                applyAudioRouting(defaultLang, src)
                startPlaybackPolling(segments, defaultLang, run.runId)
                exoPlayer.play()
            } else {
                startPipeline(Uri.parse(run.uriString))
            }
        }
    }

    fun exportRun(run: VideoRun, language: Language) {
        viewModelScope.launch {
            val res = videoExporter.exportTranslatedAudioToDownloads(run, language)
            if (res.isSuccess) {
                Toast.makeText(
                    getApplication(),
                    "Exported ${run.videoTitle} (${language.displayName}) to Downloads/LinguaPlay ✓",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    getApplication(),
                    "Export failed: ${res.exceptionOrNull()?.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun shareRun(run: VideoRun, language: Language) {
        viewModelScope.launch {
            videoExporter.shareTranslatedFile(run, language)
        }
    }

    fun deleteRun(runId: String) {
        libraryRepo.deleteRun(runId)
        refreshLibrary()
    }

    fun refreshLibrary() {
        _libraryRuns.value = libraryRepo.getAllRuns()
    }

    fun recheckVoiceAvailability() {
        _missingVoiceWarning.value = false
    }

    override fun onCleared() {
        super.onCleared()
        pipelineJob?.cancel()
        ttsPollingJob?.cancel()
        exoPlayer.release()
        instrumental.release()
        segmentAudioPlayer.release()
        pipeline.close()
    }
}
