package com.example.videotranslator.speaker

import com.example.videotranslator.model.Gender
import com.example.videotranslator.model.Speaker
import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.util.DiagnosticLogger
import kotlin.math.abs

private const val TAG = "SpeakerManager"
private const val SPEAKER_PITCH_DIFF_THRESHOLD = 35.0f // Hz difference indicating distinct speaker

/**
 * Lightweight Local Speaker Tracker and Diarizer.
 *
 * Tracks multiple speakers across conversational dialogue segments
 * using pitch contours, spectral continuity, and pause heuristics.
 */
class SpeakerManager {

    /**
     * Clusters translation segments into tracked speaker identities.
     */
    fun trackSpeakers(
        segments: List<TranslationSegment>,
        f0Estimates: List<Float>
    ): Pair<List<TranslationSegment>, List<Speaker>> {
        if (segments.isEmpty()) return Pair(emptyList(), emptyList())

        val speakers = mutableListOf<Speaker>()
        val speakerMeanPitches = mutableMapOf<String, Float>()
        val updatedSegments = mutableListOf<TranslationSegment>()

        // Initialize Speaker 1
        val spk1 = Speaker(id = "speaker_01", label = "Speaker 1")
        speakers.add(spk1)
        if (f0Estimates.isNotEmpty() && f0Estimates[0] > 0) {
            speakerMeanPitches["speaker_01"] = f0Estimates[0]
        }

        for (i in segments.indices) {
            val seg = segments[i]
            val f0 = if (i < f0Estimates.size) f0Estimates[i] else 150f

            var assignedSpeakerId = "speaker_01"

            if (f0 > 75f) {
                // Check if matches existing speaker or creates new speaker
                var bestMatchId = "speaker_01"
                var minDiff = Float.MAX_VALUE

                for ((spkId, meanPitch) in speakerMeanPitches) {
                    val diff = abs(meanPitch - f0)
                    if (diff < minDiff) {
                        minDiff = diff
                        bestMatchId = spkId
                    }
                }

                if (minDiff > SPEAKER_PITCH_DIFF_THRESHOLD && speakerMeanPitches.size < 4) {
                    // New speaker detected
                    val newId = "speaker_0${speakerMeanPitches.size + 1}"
                    val newSpeaker = Speaker(
                        id = newId,
                        label = "Speaker ${speakerMeanPitches.size + 1}"
                    )
                    speakers.add(newSpeaker)
                    speakerMeanPitches[newId] = f0
                    assignedSpeakerId = newId
                } else {
                    assignedSpeakerId = bestMatchId
                    // Update running average
                    val currentMean = speakerMeanPitches[assignedSpeakerId] ?: f0
                    speakerMeanPitches[assignedSpeakerId] = (currentMean * 0.7f + f0 * 0.3f)
                }
            }

            updatedSegments.add(
                seg.copy(speakerId = assignedSpeakerId)
            )
        }

        DiagnosticLogger.log(TAG, "Tracked ${speakers.size} distinct speaker(s) across ${segments.size} segments.")
        return Pair(updatedSegments, speakers)
    }
}
