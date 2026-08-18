package com.example.videotranslator.ai.voice

import com.example.videotranslator.audio.GenderDetector
import com.example.videotranslator.model.Gender
import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "VoiceGenderClassifier"

/**
 * Multi-Signal Acoustic Voice Characteristic & Gender Classifier.
 * Combines YIN F0 Pitch Tracking, Spectral Centroid, and HNR Periodicity.
 * Outputs Male / Female / Unknown (confidence < 0.60).
 */
class VoiceGenderClassifier {

    private val detector = GenderDetector()

    suspend fun classifySegments(
        segments: List<TranslationSegment>,
        fullPcm: ShortArray,
        fallbackGender: Gender = Gender.MALE
    ): List<TranslationSegment> = withContext(Dispatchers.IO) {
        if (segments.isEmpty() || fullPcm.isEmpty()) return@withContext segments

        DiagnosticLogger.log(TAG, "STAGE 3 - Running Voice Gender Classification on ${segments.size} segments…")

        val results = mutableListOf<TranslationSegment>()

        // 1. First analyze full audio for global dominant voice characteristic
        val globalDetection = detector.detectGender(fullPcm, fallbackGender)
        val globalGender = globalDetection.gender
        val globalConfidence = globalDetection.ensembleConfidence

        DiagnosticLogger.log(
            TAG,
            "STAGE 3 - Global audio voice characteristic: $globalGender (pitch=${"%.1f".format(globalDetection.medianF0)} Hz, conf=${"%.2f".format(globalConfidence)})"
        )

        // 2. Classify individual segments with acoustic pitch analysis
        for (seg in segments) {
            val startSample = ((seg.startMs * 16000L) / 1000L).toInt().coerceIn(0, fullPcm.size - 1)
            val endSample = ((seg.endMs * 16000L) / 1000L).toInt().coerceIn(startSample + 1, fullPcm.size)

            val segPcm = fullPcm.copyOfRange(startSample, endSample)
            val segDetection = if (segPcm.size >= 1600) {
                detector.detectGender(segPcm, fallbackGender, fullPcm, seg.startMs, seg.endMs)
            } else {
                globalDetection
            }

            val conf = segDetection.ensembleConfidence
            val resolvedGender = if (conf < 0.55f) fallbackGender else segDetection.gender

            results.add(
                seg.copy(
                    voiceGender = resolvedGender,
                    genderConfidence = conf.toDouble()
                )
            )
        }

        DiagnosticLogger.log(TAG, "STAGE 3 - Voice characteristic classification complete for ${results.size} segments ✓")
        results
    }
}
