package com.example.videotranslator.audio

import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.util.DiagnosticLogger
import java.io.File
import kotlin.math.max

private const val TAG = "AudioSynchronizer"
private const val MIN_SPEED_RATIO = 0.75f
private const val MAX_SPEED_RATIO = 1.50f
private const val MIN_SEGMENT_DURATION_MS = 300L

/**
 * Handles Audio-Video Timing Synchronization, Dynamic Lip-Sync Speed Adjustment,
 * Silence Padding, and Overlap Prevention.
 */
class AudioSynchronizer {

    data class SyncResult(
        val segment: TranslationSegment,
        val adjustedSpeedRatio: Float,
        val silencePaddingBeforeMs: Long,
        val effectiveDurationMs: Long
    )

    /**
     * Synchronizes a list of translated audio segments to match the original video timestamps.
     */
    fun synchronizeSegments(
        segments: List<TranslationSegment>,
        renderedDurationsMs: List<Long>
    ): List<SyncResult> {
        val results = mutableListOf<SyncResult>()
        var previousEndMs = 0L

        for (i in segments.indices) {
            val seg = segments[i]
            val renderedMs = if (i < renderedDurationsMs.size) renderedDurationsMs[i] else (seg.endMs - seg.startMs)
            val targetDurationMs = (seg.endMs - seg.startMs).coerceAtLeast(MIN_SEGMENT_DURATION_MS)

            // Calculate dynamic lip-sync speed adjustment
            val speedRatio = if (renderedMs > 0) {
                (renderedMs.toFloat() / targetDurationMs.toFloat()).coerceIn(MIN_SPEED_RATIO, MAX_SPEED_RATIO)
            } else {
                1.0f
            }

            // Calculate silence padding to prevent overlap
            val silencePadding = max(0L, seg.startMs - previousEndMs)
            val effectiveDuration = (targetDurationMs * speedRatio).toLong()

            previousEndMs = seg.startMs + effectiveDuration

            results.add(
                SyncResult(
                    segment = seg,
                    adjustedSpeedRatio = speedRatio,
                    silencePaddingBeforeMs = silencePadding,
                    effectiveDurationMs = effectiveDuration
                )
            )
        }

        DiagnosticLogger.log(TAG, "Synchronized ${results.size} audio segments to video timeline.")
        return results
    }
}
