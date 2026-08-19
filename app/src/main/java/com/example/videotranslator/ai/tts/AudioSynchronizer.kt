package com.example.videotranslator.ai.tts

import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "AudioSynchronizer"

/**
 * Audio-Video Natural Timing & Speed Alignment Synchronizer.
 * Calculates natural human speaking rate and bounds speed strictly within 0.95x - 1.15x.
 * Zero silent audio masking on failed text.
 */
class AudioSynchronizer {

    private val timingEngine = TranslationTimingEngine()

    fun calculateSpeedRatio(synthesizedDurationMs: Long, targetDurationMs: Long): Float {
        if (targetDurationMs <= 0L || synthesizedDurationMs <= 0L) return 1.0f
        val ratio = synthesizedDurationMs.toFloat() / targetDurationMs.toFloat()
        return ratio.coerceIn(0.95f, 1.15f)
    }

    suspend fun synchronizeSegments(
        segments: List<TranslationSegment>,
        outputDir: File,
        languagePrefix: String = ""
    ): List<TranslationSegment> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyList()

        DiagnosticLogger.log(TAG, "STAGE 6 - Synchronizing natural timing and pause allocation for ${segments.size} segments…")
        outputDir.mkdirs()

        val timedResults = timingEngine.calculateTiming(segments, outputDir, languagePrefix)
        val finalSegments = mutableListOf<TranslationSegment>()

        for (timed in timedResults) {
            val seg = timed.segment
            val audioFileName = if (languagePrefix.isNotBlank()) "dub_${languagePrefix}_${seg.id}.wav" else "dub_${seg.id}.wav"
            val audioFile = File(outputDir, audioFileName)

            finalSegments.add(
                seg.copy(
                    audioFilePath = audioFile.absolutePath,
                    targetDurationMs = timed.originalDurationMs,
                    actualDurationMs = timed.actualTtsDurationMs,
                    speedRatio = timed.naturalSpeedRatio
                )
            )
        }

        DiagnosticLogger.log(TAG, "STAGE 6 - Natural audio timing alignment complete ✓ (All speaking speeds bounded to 0.95x-1.15x)")
        finalSegments
    }
}
