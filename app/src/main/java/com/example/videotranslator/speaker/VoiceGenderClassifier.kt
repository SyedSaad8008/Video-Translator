package com.example.videotranslator.speaker

import com.example.videotranslator.audio.GenderDetector
import com.example.videotranslator.model.Gender
import com.example.videotranslator.util.DiagnosticLogger

private const val TAG = "VoiceGenderClassifier"
private const val CONFIDENCE_THRESHOLD = 0.60f

/**
 * On-Device Multi-Signal Voice Gender & Pitch Classifier.
 *
 * Classifies vocal acoustic characteristics into MALE, FEMALE, or UNKNOWN.
 * If classification confidence falls below 0.60, returns UNKNOWN.
 */
class VoiceGenderClassifier {

    private val genderDetector = GenderDetector()

    data class ClassificationResult(
        val gender: Gender,
        val confidence: Float,
        val medianF0: Float,
        val spectralCentroid: Float,
        val hnr: Float,
        val isLowConfidence: Boolean
    )

    /**
     * Classifies voice gender for a segment with confidence thresholding.
     */
    suspend fun classifyVoice(
        pcmMono: ShortArray,
        fallbackGender: Gender = Gender.MALE,
        transientMask: BooleanArray? = null
    ): ClassificationResult {
        val detection = genderDetector.detectGender(
            pcmMono = pcmMono,
            fallbackGender = fallbackGender,
            transientMask = transientMask
        )

        val conf = detection.ensembleConfidence
        val isLowConf = conf < CONFIDENCE_THRESHOLD

        val finalGender = if (isLowConf) {
            Gender.UNKNOWN
        } else {
            detection.gender
        }

        DiagnosticLogger.log(TAG, "Voice Classification: $finalGender (Conf=${"%.2f".format(conf)}, F0=${"%.1f".format(detection.medianF0)}Hz, LowConf=$isLowConf)")

        return ClassificationResult(
            gender = finalGender,
            confidence = conf,
            medianF0 = detection.medianF0,
            spectralCentroid = detection.spectralCentroid,
            hnr = detection.hnr,
            isLowConfidence = isLowConf
        )
    }

    /**
     * Applies temporal sequence smoothing across conversational dialogue turns.
     */
    fun smoothSequence(results: List<ClassificationResult>): List<ClassificationResult> {
        if (results.size <= 2) return results

        val smoothed = results.toMutableList()
        for (i in 1 until results.lastIndex) {
            val prev = smoothed[i - 1].gender
            val next = smoothed[i + 1].gender
            val curr = smoothed[i]

            // If an isolated segment disagrees with both neighbors and has low/moderate confidence
            if (prev == next && prev != Gender.UNKNOWN && curr.gender != prev && curr.confidence < 0.75f) {
                DiagnosticLogger.log(TAG, "Temporal smoothing: Segment $i smoothed from ${curr.gender} → $prev")
                smoothed[i] = curr.copy(
                    gender = prev,
                    confidence = (curr.confidence * 0.5f + smoothed[i - 1].confidence * 0.5f)
                )
            }
        }
        return smoothed
    }
}
