package com.example.videotranslator.audio

import android.util.Log
import com.example.videotranslator.model.Gender
import kotlin.math.sqrt

private const val TAG = "GenderDetector"
private const val SAMPLE_RATE = 16_000

/**
 * DSP Pitch (F0) Estimator & Gender Classifier.
 *
 * Uses subharmonic pitch-period autocorrelation over ~30ms frames of 16kHz mono PCM speech audio.
 *  1. Splits audio into 30ms frames (480 samples) with 15ms hop (240 samples).
 *  2. Filters out unvoiced / silent frames with RMS energy below threshold.
 *  3. Computes autocorrelation over lag range 45..213 (75Hz to 350Hz).
 *  4. Applies subharmonic pitch period peak selection (preventing 2x octave doubling).
 *  5. Takes the median pitch F0 across all voiced frames.
 *  6. Classifies median F0 < 165Hz as MALE, >= 165Hz as FEMALE.
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

        // Lag range corresponding to 75Hz - 350Hz at 16kHz
        // f = sampleRate / lag -> minLag = 16000 / 350 = 45, maxLag = 16000 / 75 = 213
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

            // Voiced frame energy threshold check (skip silence/noise)
            if (rms >= 120.0) {
                val lags = IntArray(maxLag - minLag + 1)
                val autocorrValues = DoubleArray(maxLag - minLag + 1)
                var maxVal = -1.0

                for (idx in lags.indices) {
                    val lag = minLag + idx
                    lags[idx] = lag
                    var autocorr = 0.0
                    for (i in 0 until (frameSize - lag)) {
                        autocorr += pcmMono[offset + i].toDouble() * pcmMono[offset + i + lag].toDouble()
                    }
                    autocorrValues[idx] = autocorr
                    if (autocorr > maxVal) {
                        maxVal = autocorr
                    }
                }

                if (maxVal > 0) {
                    val thresh = maxVal * 0.65
                    val peakIndices = mutableListOf<Int>()

                    // Find local peaks above threshold
                    for (i in 1 until autocorrValues.size - 1) {
                        val v = autocorrValues[i]
                        if (v >= thresh && v > autocorrValues[i - 1] && v > autocorrValues[i + 1]) {
                            peakIndices.add(i)
                        }
                    }

                    // Select the peak with the LARGEST lag (lowest fundamental frequency F0),
                    // which prevents 2x harmonic octave doubling (e.g. 225Hz doubling -> 86.5Hz fundamental)!
                    val bestLag = if (peakIndices.isNotEmpty()) {
                        peakIndices.maxOf { lags[it] }
                    } else {
                        var topIdx = 0
                        for (i in autocorrValues.indices) {
                            if (autocorrValues[i] > autocorrValues[topIdx]) topIdx = i
                        }
                        lags[topIdx]
                    }

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
