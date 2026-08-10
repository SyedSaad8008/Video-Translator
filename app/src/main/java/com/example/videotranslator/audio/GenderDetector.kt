package com.example.videotranslator.audio

import android.util.Log
import com.example.videotranslator.model.Gender
import kotlin.math.sqrt

private const val TAG = "GenderDetector"
private const val SAMPLE_RATE = 16_000

/**
 * DSP Pitch (F0) Estimator & Per-Segment Gender Classifier.
 *
 * Implements YIN Normalized Autocorrelation:
 *  1. Splits audio into 30ms frames (480 samples) with 15ms hop (240 samples).
 *  2. Filters out unvoiced / silent frames with RMS energy below threshold.
 *  3. Computes normalized autocorrelation over lag range 45..213 (75Hz to 350Hz).
 *  4. Identifies candidate pitch peaks r(k) >= 0.30.
 *  5. Selects the primary fundamental pitch peak T0 (preventing harmonic doubling & subharmonic lowering).
 *  6. Classifies median F0 < 165Hz as MALE, >= 165Hz as FEMALE.
 */
class GenderDetector {

    data class DetectionResult(
        val medianF0: Float,
        val gender: Gender,
        val totalVoicedFrames: Int
    )

    fun detectGender(pcmMono: ShortArray, fallbackGender: Gender = Gender.MALE): DetectionResult {
        if (pcmMono.isEmpty()) {
            Log.w(TAG, "PCM audio is empty -> defaulting to fallback gender $fallbackGender")
            return DetectionResult(0f, fallbackGender, 0)
        }

        val frameSize = (SAMPLE_RATE * 0.030).toInt() // 480 samples = 30ms
        val frameHop  = (SAMPLE_RATE * 0.015).toInt() // 240 samples = 15ms

        // Lag range corresponding to 75Hz - 350Hz at 16kHz
        val minLag = (SAMPLE_RATE / 350.0).toInt().coerceAtLeast(1) // 45
        val maxLag = (SAMPLE_RATE / 75.0).toInt().coerceAtMost(frameSize - 1) // 213

        val f0Estimates = mutableListOf<Float>()
        var offset = 0

        while (offset + frameSize <= pcmMono.size) {
            var sumSq = 0.0
            for (i in 0 until frameSize) {
                val s = pcmMono[offset + i].toDouble()
                sumSq += s * s
            }
            val rms = sqrt(sumSq / frameSize)

            // Skip silent/unvoiced frames (RMS threshold)
            if (rms >= 120.0) {
                val lags = IntArray(maxLag - minLag + 1)
                val normAutocorr = DoubleArray(maxLag - minLag + 1)

                for (idx in lags.indices) {
                    val lag = minLag + idx
                    lags[idx] = lag
                    var c = 0.0
                    var rLag = 0.0
                    for (i in 0 until (frameSize - lag)) {
                        val s1 = pcmMono[offset + i].toDouble()
                        val s2 = pcmMono[offset + i + lag].toDouble()
                        c += s1 * s2
                        rLag += s2 * s2
                    }
                    val denom = sqrt(sumSq * rLag)
                    normAutocorr[idx] = if (denom > 0) c / denom else 0.0
                }

                // Find local peaks in normalized autocorrelation >= 0.30
                val peakLags = mutableListOf<Int>()
                val peakVals = mutableListOf<Double>()

                for (i in 1 until normAutocorr.size - 1) {
                    val v = normAutocorr[i]
                    if (v >= 0.30 && v > normAutocorr[i - 1] && v > normAutocorr[i + 1]) {
                        peakLags.add(lags[i])
                        peakVals.add(v)
                    }
                }

                if (peakVals.isNotEmpty()) {
                    val maxPeakVal = peakVals.maxOrNull() ?: 0.0
                    val thresh = maxPeakVal * 0.70

                    // Pick the FIRST peak (smallest lag / highest true fundamental frequency)
                    // among candidate peaks reaching at least 70% of max peak strength.
                    var bestLag = -1
                    for (k in peakLags.indices) {
                        if (peakVals[k] >= thresh) {
                            bestLag = peakLags[k]
                            break
                        }
                    }

                    if (bestLag > 0) {
                        val frameF0 = SAMPLE_RATE.toFloat() / bestLag.toFloat()
                        if (frameF0 in 75.0f..450.0f) {
                            f0Estimates.add(frameF0)
                        }
                    }
                }
            }

            offset += frameHop
        }

        if (f0Estimates.isEmpty()) {
            Log.w(TAG, "No voiced frames in segment PCM slice -> fallback to $fallbackGender")
            return DetectionResult(0f, fallbackGender, 0)
        }

        f0Estimates.sort()
        val medianF0 = if (f0Estimates.size % 2 == 0) {
            (f0Estimates[f0Estimates.size / 2 - 1] + f0Estimates[f0Estimates.size / 2]) / 2.0f
        } else {
            f0Estimates[f0Estimates.size / 2]
        }

        // Cutoff: < 165Hz -> MALE, >= 165Hz -> FEMALE
        val classifiedGender = if (medianF0 < 165.0f) Gender.MALE else Gender.FEMALE

        Log.d(TAG, "YIN Gender Detection Result: medianF0=${"%.1f".format(medianF0)} Hz, " +
                "voicedFrames=${f0Estimates.size}, classifiedGender=$classifiedGender")

        return DetectionResult(medianF0, classifiedGender, f0Estimates.size)
    }
}
