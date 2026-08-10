package com.example.videotranslator.audio

import android.util.Log
import com.example.videotranslator.model.Gender
import kotlin.math.sqrt

private const val TAG = "GenderDetector"
private const val SAMPLE_RATE = 16_000

/**
 * DSP Pitch (F0) Estimator & Gender Classifier.
 *
 * Uses autocorrelation over ~30ms frames of 16kHz mono PCM speech audio.
 *  1. Splits audio into 30ms frames (480 samples) with 15ms hop (240 samples).
 *  2. Filters out unvoiced / silent frames with RMS energy below threshold.
 *  3. Computes autocorrelation over lag range 40..200 (80Hz to 400Hz).
 *  4. Takes the median pitch F0 across all voiced frames.
 *  5. Classifies median F0 < 165Hz as MALE, >= 165Hz as FEMALE.
 */
class GenderDetector {

    data class DetectionResult(
        val medianF0: Float,
        val gender: Gender,
        val totalVoicedFrames: Int
    )

    fun detectGender(pcmMono: ShortArray): DetectionResult {
        if (pcmMono.isEmpty()) {
            Log.w(TAG, "PCM audio is empty -> defaulting to Gender.MALE")
            return DetectionResult(0f, Gender.MALE, 0)
        }

        val frameSize = (SAMPLE_RATE * 0.030).toInt() // 480 samples = 30ms
        val frameHop  = (SAMPLE_RATE * 0.015).toInt() // 240 samples = 15ms

        // Lag range corresponding to 80Hz - 400Hz at 16kHz
        // f = sampleRate / lag -> minLag = 16000 / 400 = 40, maxLag = 16000 / 80 = 200
        val minLag = (SAMPLE_RATE / 400.0).toInt().coerceAtLeast(1) // 40
        val maxLag = (SAMPLE_RATE / 80.0).toInt().coerceAtMost(frameSize - 1) // 200

        val f0Estimates = mutableListOf<Float>()
        var offset = 0

        while (offset + frameSize <= pcmMono.size) {
            var sumSq = 0.0
            for (i in 0 until frameSize) {
                val s = pcmMono[offset + i].toDouble()
                sumSq += s * s
            }
            val rms = sqrt(sumSq / frameSize)

            // Voiced frame energy threshold check (skip silence/noise)
            if (rms >= 120.0) {
                var bestLag = -1
                var maxAutocorr = -1.0

                for (lag in minLag..maxLag) {
                    var autocorr = 0.0
                    for (i in 0 until (frameSize - lag)) {
                        autocorr += pcmMono[offset + i].toDouble() * pcmMono[offset + i + lag].toDouble()
                    }
                    if (autocorr > maxAutocorr) {
                        maxAutocorr = autocorr
                        bestLag = lag
                    }
                }

                if (bestLag > 0) {
                    val frameF0 = SAMPLE_RATE.toFloat() / bestLag.toFloat()
                    if (frameF0 in 75.0f..450.0f) {
                        f0Estimates.add(frameF0)
                    }
                }
            }

            offset += frameHop
        }

        if (f0Estimates.isEmpty()) {
            Log.w(TAG, "No voiced frames detected in audio -> defaulting to Gender.MALE")
            return DetectionResult(0f, Gender.MALE, 0)
        }

        f0Estimates.sort()
        val medianF0 = if (f0Estimates.size % 2 == 0) {
            (f0Estimates[f0Estimates.size / 2 - 1] + f0Estimates[f0Estimates.size / 2]) / 2.0f
        } else {
            f0Estimates[f0Estimates.size / 2]
        }

        // Standard cutoff: < 165Hz -> Male, >= 165Hz -> Female
        val classifiedGender = if (medianF0 < 165.0f) Gender.MALE else Gender.FEMALE

        Log.d(TAG, "DSP Gender Detection Result: medianF0=${"%.1f".format(medianF0)} Hz, " +
                "voicedFrames=${f0Estimates.size}, classifiedGender=$classifiedGender")

        return DetectionResult(medianF0, classifiedGender, f0Estimates.size)
    }
}
