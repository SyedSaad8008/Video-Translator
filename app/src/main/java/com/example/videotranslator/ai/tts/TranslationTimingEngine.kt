package com.example.videotranslator.ai.tts

import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.util.DiagnosticLogger
import java.io.File

private const val TAG = "TranslationTimingEngine"

data class TimedSegment(
    val segment: TranslationSegment,
    val naturalSpeedRatio: Float,
    val scheduledStartMs: Long,
    val scheduledEndMs: Long,
    val actualTtsDurationMs: Long,
    val originalDurationMs: Long
)

/**
 * Natural Audio Timing & Pause Preservation Engine.
 *
 * Priorities:
 *  1. Natural human speaking speed (strictly bounded 0.95x - 1.15x, never rushed).
 *  2. Preserve original pause structure without artificial gaps.
 *  3. Intelligent schedule allocation across available inter-segment pauses.
 */
class TranslationTimingEngine {

    companion object {
        const val MIN_SPEED_RATIO = 0.95f
        const val MAX_SPEED_RATIO = 1.15f
        const val DEFAULT_SPEED_RATIO = 1.0f
    }

    /**
     * Calculates natural timing and scheduled boundaries for a list of segments.
     */
    fun calculateTiming(
        segments: List<TranslationSegment>,
        audioFilesDir: File,
        languagePrefix: String = ""
    ): List<TimedSegment> {
        if (segments.isEmpty()) return emptyList()

        val results = mutableListOf<TimedSegment>()

        for (i in segments.indices) {
            val seg = segments[i]
            val nextSeg = if (i < segments.size - 1) segments[i + 1] else null

            val originalDurationMs = (seg.endMs - seg.startMs).coerceAtLeast(300L)
            val pauseToNextMs = if (nextSeg != null) (nextSeg.startMs - seg.endMs).coerceAtLeast(0L) else 1500L

            // Locate rendered audio file
            val audioFileName = if (languagePrefix.isNotBlank()) "dub_${languagePrefix}_${seg.id}.wav" else "dub_${seg.id}.wav"
            val audioFile = File(audioFilesDir, audioFileName)
            val actualTtsDurationMs = if (audioFile.exists() && audioFile.length() > 44L) {
                getWavDurationMs(audioFile)
            } else {
                originalDurationMs
            }

            // Available time window borrows up to 60% of natural pause without crashing into next speech
            val usablePauseMs = (pauseToNextMs * 0.60).toLong()
            val availableWindowMs = originalDurationMs + usablePauseMs

            // Calculate gentle speed adjustment strictly in natural human range (0.95x - 1.15x)
            val naturalSpeed = if (actualTtsDurationMs > 0L && availableWindowMs > 0L) {
                val ratio = actualTtsDurationMs.toFloat() / availableWindowMs.toFloat()
                ratio.coerceIn(MIN_SPEED_RATIO, MAX_SPEED_RATIO)
            } else {
                DEFAULT_SPEED_RATIO
            }

            val effectiveDurationMs = (actualTtsDurationMs / naturalSpeed).toLong()
            val scheduledStartMs = seg.startMs
            val scheduledEndMs = scheduledStartMs + effectiveDurationMs

            DiagnosticLogger.log(
                TAG,
                "Segment ${seg.id}: Original=${originalDurationMs}ms, TTS=${actualTtsDurationMs}ms, Pause=${pauseToNextMs}ms → Speed=${"%.2f".format(naturalSpeed)}x, Window=${scheduledStartMs}ms..${scheduledEndMs}ms"
            )

            results.add(
                TimedSegment(
                    segment = seg.copy(
                        targetDurationMs = originalDurationMs,
                        actualDurationMs = actualTtsDurationMs,
                        speedRatio = naturalSpeed
                    ),
                    naturalSpeedRatio = naturalSpeed,
                    scheduledStartMs = scheduledStartMs,
                    scheduledEndMs = scheduledEndMs,
                    actualTtsDurationMs = actualTtsDurationMs,
                    originalDurationMs = originalDurationMs
                )
            )
        }

        return results
    }

    private fun getWavDurationMs(file: File): Long {
        if (!file.exists() || file.length() <= 44L) return 0L
        val dataBytes = file.length() - 44L
        val bytesPerSec = 16000 * 2 // 16kHz 16-bit mono = 32,000 bytes/sec
        return (dataBytes * 1000L) / bytesPerSec
    }
}
