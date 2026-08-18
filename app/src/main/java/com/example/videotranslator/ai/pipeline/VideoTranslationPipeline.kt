package com.example.videotranslator.ai.pipeline

import android.content.Context
import android.net.Uri
import com.example.videotranslator.ai.speech.WhisperEngine
import com.example.videotranslator.ai.translation.TranslationPipeline
import com.example.videotranslator.ai.tts.AudioSynchronizer
import com.example.videotranslator.ai.tts.NeuralTtsEngine
import com.example.videotranslator.ai.voice.VoiceGenderClassifier
import com.example.videotranslator.audio.AudioExtractor
import com.example.videotranslator.audio.NoiseSuppressor
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
import java.io.File
import java.util.UUID

private const val TAG = "VideoTranslationPipeline"

/**
 * Enterprise 7-Stage On-Device Multilingual Video Translation Pipeline.
 * 100% Offline • Zero Cloud • Pure Neural Edge AI.
 */
class VideoTranslationPipeline(
    private val context: Context,
    private val cache: SegmentCache,
    private val libraryRepo: VideoLibraryRepository
) {

    private val audioExtractor = AudioExtractor(context)
    private val noiseSuppressor = NoiseSuppressor()
    private val whisperEngine = WhisperEngine(context)
    private val genderClassifier = VoiceGenderClassifier()
    private val translationPipeline = TranslationPipeline(context)
    private val ttsEngine = NeuralTtsEngine(context)
    private val synchronizer = AudioSynchronizer()

    suspend fun loadEngines() {
        whisperEngine.load()
        translationPipeline.load()
    }

    fun close() {
        whisperEngine.close()
        translationPipeline.close()
        ttsEngine.close()
    }

    data class PipelineResult(
        val runId: String,
        val detectedSourceLanguage: Language,
        val detectedGender: Gender,
        val segments: List<TranslationSegment>
    )

    /**
     * Executes full 7-stage offline video translation pipeline.
     */
    suspend fun execute(
        videoUri: Uri,
        manualSourceLanguage: Language? = null,
        targetLanguage: Language = Language.ENGLISH,
        fallbackGender: Gender = Gender.MALE,
        onProgress: (ProcessingState.Loading) -> Unit
    ): PipelineResult = withContext(Dispatchers.IO) {
        val runId = UUID.randomUUID().toString()
        DiagnosticLogger.log(TAG, "══════════ STARTING 100% OFFLINE NEURAL PIPELINE [Run: $runId] ══════════")

        val pcmFile = cache.pcmFileForRun(runId)
        val instrumentalFile = cache.instrumentalFileForRun(runId)
        val renderedDir = cache.renderedAudioDirForRun(runId)

        try {
            // STAGE 1: Audio Extraction & Voice Separation
            onProgress(
                ProcessingState.Loading(
                    currentStage = 1,
                    totalStages = 7,
                    step = "Extracting audio and isolating voice tracks…",
                    progress = 0.10f
                )
            )
            val extractRes = audioExtractor.extractToFiles(videoUri, pcmFile, instrumentalFile)
            val fullPcm = extractRes.mono
            if (fullPcm.isEmpty()) throw IllegalStateException("Failed to extract audio stream from video.")
            val cleanPcm = noiseSuppressor.suppressNoise(fullPcm)

            // STAGE 2: Language Identification & Whisper Multilingual STT
            onProgress(
                ProcessingState.Loading(
                    currentStage = 2,
                    totalStages = 7,
                    step = "Whisper Neural STT: Identifying spoken language & transcribing…",
                    progress = 0.28f
                )
            )
            val sourceLang = manualSourceLanguage ?: whisperEngine.identifyLanguage(cleanPcm)
            DiagnosticLogger.log(TAG, "Source Spoken Language Selected: $sourceLang (Manual=$manualSourceLanguage)")

            val rawSegments = whisperEngine.transcribe(cleanPcm, sourceLang)
            if (rawSegments.isEmpty()) throw IllegalStateException("No speech dialogue detected in video.")

            // STAGE 3: Voice Characteristic & Pitch Gender Verification
            onProgress(
                ProcessingState.Loading(
                    currentStage = 3,
                    totalStages = 7,
                    step = "Analyzing acoustic pitch (F0) & speaker characteristics…",
                    progress = 0.45f
                )
            )
            val genderSegments = genderClassifier.classifySegments(rawSegments, cleanPcm, fallbackGender)
            val primaryGender = genderSegments.firstOrNull()?.voiceGender ?: Gender.MALE

            // STAGE 4: NLLB-200 Multilingual Neural Translation
            onProgress(
                ProcessingState.Loading(
                    currentStage = 4,
                    totalStages = 7,
                    step = "NLLB-200 Neural Translation across all language tracks…",
                    progress = 0.62f
                )
            )
            val translatedSegments = translationPipeline.translateSegments(genderSegments, sourceLang)

            // STAGE 5: Gender-Matched Neural TTS Dubbing
            onProgress(
                ProcessingState.Loading(
                    currentStage = 5,
                    totalStages = 7,
                    step = "Synthesizing dubbed voice tracks with gender matching…",
                    progress = 0.80f
                )
            )
            val synthesizedSegments = ttsEngine.synthesizeSegments(translatedSegments, targetLanguage, renderedDir)

            // STAGE 6: Lip-Sync Speed & Timing Alignment
            onProgress(
                ProcessingState.Loading(
                    currentStage = 6,
                    totalStages = 7,
                    step = "Synchronizing audio-video lip-sync and timing alignment…",
                    progress = 0.92f
                )
            )
            val finalSegments = synchronizer.synchronizeSegments(synthesizedSegments, renderedDir)

            // STAGE 7: Save to Cache & Persistent Video Library
            onProgress(
                ProcessingState.Loading(
                    currentStage = 7,
                    totalStages = 7,
                    step = "Finalizing video translation session…",
                    progress = 0.98f
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

            DiagnosticLogger.log(TAG, "══════════ 100% OFFLINE NEURAL PIPELINE COMPLETED SUCCESSFULLY [Run: $runId] ══════════")
            PipelineResult(
                runId = runId,
                detectedSourceLanguage = sourceLang,
                detectedGender = primaryGender,
                segments = finalSegments
            )
        } catch (ce: CancellationException) {
            DiagnosticLogger.log(TAG, "Pipeline cancelled by user.")
            throw ce
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "Pipeline exception: ${e.message}", e)
            throw e
        }
    }
}
