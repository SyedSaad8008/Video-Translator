package com.example.videotranslator.ai.pipeline

import android.content.Context
import android.net.Uri
import com.example.videotranslator.audio.AudioExtractor
import com.example.videotranslator.audio.NoiseSuppressor
import com.example.videotranslator.ai.speech.WhisperEngine
import com.example.videotranslator.ai.translation.TranslationPipeline
import com.example.videotranslator.ai.tts.AudioSynchronizer
import com.example.videotranslator.ai.tts.NeuralTtsEngine
import com.example.videotranslator.ai.voice.VoiceGenderClassifier
import com.example.videotranslator.cache.SegmentCache
import com.example.videotranslator.library.VideoLibraryRepository
import com.example.videotranslator.library.VideoRun
import com.example.videotranslator.model.Gender
import com.example.videotranslator.model.Language
import com.example.videotranslator.model.ProcessingState
import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "VideoTranslationPipeline"

data class PipelineResult(
    val runId: String,
    val detectedSourceLanguage: Language,
    val detectedGender: Gender,
    val segments: List<TranslationSegment>
)

/**
 * 7-Stage End-to-End On-Device Video Translation Pipeline Orchestrator.
 * 100% Offline • Zero Cloud • Native Acoustic STT, Neural Translation, Gender-Matched Dubbing.
 */
class VideoTranslationPipeline(
    private val context: Context,
    private val cache: SegmentCache = SegmentCache(context),
    private val libraryRepo: VideoLibraryRepository = VideoLibraryRepository(context)
) {

    private val audioExtractor = AudioExtractor(context)
    private val noiseSuppressor = NoiseSuppressor()
    private val whisperEngine = WhisperEngine(context)
    private val genderClassifier = VoiceGenderClassifier()
    private val translationPipeline = TranslationPipeline(context)
    private val ttsEngine = NeuralTtsEngine(context)
    private val synchronizer = AudioSynchronizer()

    suspend fun loadEngines() = withContext(Dispatchers.IO) {
        DiagnosticLogger.log("PIPELINE", "Pre-warming on-device AI pipelines…")
        whisperEngine.load()
        translationPipeline.load()
    }

    fun close() {
        whisperEngine.close()
        translationPipeline.close()
        ttsEngine.close()
    }

    suspend fun execute(
        videoUri: Uri,
        manualSourceLanguage: Language? = null,
        targetLanguage: Language = Language.ENGLISH,
        fallbackGender: Gender = Gender.MALE,
        onProgress: (ProcessingState.Loading) -> Unit
    ): PipelineResult = withContext(Dispatchers.IO) {
        val runId = UUID.randomUUID().toString()
        val videoName = videoUri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
        DiagnosticLogger.log("PIPELINE", "══════════ STARTING VIDEO TRANSLATION PIPELINE [Run: $runId] ══════════")
        DiagnosticLogger.log("PIPELINE", "Video Source: $videoName")

        val pcmFile = cache.pcmFileForRun(runId)
        val instrumentalFile = cache.instrumentalFileForRun(runId)
        val renderedDir = cache.renderedAudioDirForRun(runId)

        try {
            // STAGE 1: Audio Extraction & Voice Isolation
            onProgress(
                ProcessingState.Loading(
                    currentStage = 1,
                    totalStages = 7,
                    step = "Extracting audio track & isolating voice dialogue…",
                    progress = 0.08f
                )
            )
            val extractRes = audioExtractor.extractToFiles(videoUri, pcmFile, instrumentalFile)
            val fullPcm = extractRes.mono
            if (fullPcm.isEmpty()) throw IllegalStateException("No audio track found in selected video.")
            val cleanPcm = noiseSuppressor.suppressNoise(fullPcm)
            DiagnosticLogger.log("AUDIO", "Extracted ${(cleanPcm.size / 16000.0)}s mono 16kHz audio ✓")

            // STAGE 2: Language Identification & Whisper Multilingual STT
            onProgress(
                ProcessingState.Loading(
                    currentStage = 2,
                    totalStages = 7,
                    step = "Whisper Neural STT: Identifying spoken language…",
                    progress = 0.16f
                )
            )
            val sourceLang = if (manualSourceLanguage != null) {
                DiagnosticLogger.log("LANG_DETECT", "▶ Manual Source Language Override: ${manualSourceLanguage.displayName} (${manualSourceLanguage.name}) ✓")
                manualSourceLanguage
            } else {
                whisperEngine.identifyLanguage(cleanPcm)
            }

            onProgress(
                ProcessingState.Loading(
                    currentStage = 2,
                    totalStages = 7,
                    step = "Whisper Neural STT: Transcribing ${sourceLang.displayName} dialogue segments…",
                    progress = 0.24f
                )
            )
            val rawSegments = whisperEngine.transcribe(cleanPcm, sourceLang)
            if (rawSegments.isEmpty()) throw IllegalStateException("No speech dialogue detected in video.")

            // STAGE 3: Voice Characteristic & Pitch Gender Verification
            onProgress(
                ProcessingState.Loading(
                    currentStage = 3,
                    totalStages = 7,
                    step = "Acoustic Pitch Tracking (F0 YIN): Verifying speaker characteristics…",
                    progress = 0.40f
                )
            )
            val genderSegments = genderClassifier.classifySegments(rawSegments, cleanPcm, fallbackGender)
            val primaryGender = genderSegments.firstOrNull()?.voiceGender ?: Gender.MALE
            DiagnosticLogger.log("VOICE", "Detected Primary Voice Gender: $primaryGender ✓")

            // STAGE 4: NLLB-200 Multilingual Neural Translation
            val totalSegs = genderSegments.size
            val translatedSegments = mutableListOf<TranslationSegment>()

            for ((idx, seg) in genderSegments.withIndex()) {
                val progressVal = 0.48f + (0.20f * (idx.toFloat() / totalSegs.toFloat()))
                onProgress(
                    ProcessingState.Loading(
                        currentStage = 4,
                        totalStages = 7,
                        step = "Neural Translation [${sourceLang.displayName}]: Segment ${idx + 1} of $totalSegs…",
                        progress = progressVal
                    )
                )
                val translated = translationPipeline.translateSegments(listOf(seg), sourceLang)
                translatedSegments.addAll(translated)
            }
            DiagnosticLogger.log("TRANSLATION", "All $totalSegs dialogue segments translated across Hindi, English & Telugu ✓")

            // STAGE 5: Gender-Matched Neural TTS Dubbing across Target Languages
            val targetLanguagesToSynthesize = Language.entries.filter { it != sourceLang }
            var synthesizedSegments: List<TranslationSegment> = translatedSegments

            for ((langIdx, tLang) in targetLanguagesToSynthesize.withIndex()) {
                val progressVal = 0.70f + (0.12f * (langIdx.toFloat() / targetLanguagesToSynthesize.size.toFloat()))
                onProgress(
                    ProcessingState.Loading(
                        currentStage = 5,
                        totalStages = 7,
                        step = "Neural TTS: Synthesizing ${tLang.displayName} dubbed voice track…",
                        progress = progressVal
                    )
                )
                synthesizedSegments = ttsEngine.synthesizeSegments(synthesizedSegments, tLang, renderedDir)
            }
            DiagnosticLogger.log("TTS", "Rendered dubbed audio segments for target languages ✓")

            // STAGE 6: Natural Timing & Audio Synchronization
            onProgress(
                ProcessingState.Loading(
                    currentStage = 6,
                    totalStages = 7,
                    step = "Applying natural speaking timing & pause allocation…",
                    progress = 0.88f
                )
            )
            val finalSegments = synchronizer.synchronizeSegments(synthesizedSegments, renderedDir, targetLanguage.name.lowercase())
            DiagnosticLogger.log("SYNC", "Natural audio timing aligned across all segments ✓")

            // STAGE 7: Save to Cache & Persistent Video Library
            onProgress(
                ProcessingState.Loading(
                    currentStage = 7,
                    totalStages = 7,
                    step = "Finalizing & saving translation session…",
                    progress = 0.96f
                )
            )
            cache.saveRun(runId, finalSegments)

            val videoTitle = videoUri.lastPathSegment?.substringAfterLast('/')?.take(30) ?: "Video Run"
            libraryRepo.saveRun(
                VideoRun(
                    runId = runId,
                    uriString = videoUri.toString(),
                    videoTitle = videoTitle,
                    timestampMs = System.currentTimeMillis(),
                    detectedGender = primaryGender.name,
                    detectedSourceLanguage = sourceLang.name,
                    segmentCount = finalSegments.size,
                    status = "Ready"
                )
            )

            onProgress(
                ProcessingState.Loading(
                    currentStage = 7,
                    totalStages = 7,
                    step = "Translation complete! Ready for playback.",
                    progress = 1.0f
                )
            )

            DiagnosticLogger.log("PIPELINE", "══════════ 100% OFFLINE NEURAL PIPELINE COMPLETED [Run: $runId] ══════════")
            PipelineResult(
                runId = runId,
                detectedSourceLanguage = sourceLang,
                detectedGender = primaryGender,
                segments = finalSegments
            )
        } catch (ce: CancellationException) {
            DiagnosticLogger.log("PIPELINE", "Pipeline cancelled by user.")
            throw ce
        } catch (e: Exception) {
            DiagnosticLogger.log("PIPELINE", "Pipeline execution error: ${e.message}", e)
            throw e
        }
    }
}
