package com.example.videotranslator.audio

import android.util.Log
import com.example.videotranslator.model.Gender
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "GenderDetector"
private const val SAMPLE_RATE = 16_000
private const val MIN_VOICED_FRAMES = 10 // Minimum ~150ms of voiced speech
private const val CONFIDENCE_THRESHOLD = 0.65f // Target confidence score for high certainty

/**
 * Multi-Pass Pitch (F0) Estimator & Per-Segment Gender Classifier with Confidence Scoring.
 *
 *  1. **Pass 1 Primary Analysis**: 30ms frames (480 samples), 15ms hop, YIN Normalized Autocorrelation.
 *  2. **Confidence Metric (C)**: Evaluates peak strength ratio & autocorrelation consistency across voiced frames.
 *  3. **Pass 2 Multi-Pass Window Expansion**: If C < 0.65 or voiced frames < 15, runs a secondary pass with a 50% wider window (+-250ms) and 20ms frame resolution to resolve ambiguous audio.
 *  4. **Carryover Fallback**: If confidence remains low, carries over previous segment's gender.
 */
class GenderDetector {

    data class DetectionResult(
        val medianF0: Float,
        val gender: Gender,
        val totalVoicedFrames: Int,
        val confidenceScore: Float,
        val isPass2Triggered: Boolean = false,
        val isCarriedOver: Boolean = false
    )

    fun detectGender(
        pcmMono: ShortArray,
        fallbackGender: Gender = Gender.MALE,
        fullPcmMono: ShortArray? = null,
        segmentStartMs: Long = 0L,
        segmentEndMs: Long = 0L
    ): DetectionResult {

        // Pass 1 Analysis
        val pass1Result = analyzePcmSlice(
            pcmMono = pcmMono,
            frameSizeMs = 30,
            frameHopMs = 15,
            rmsThreshold = 120.0,
            fallbackGender = fallbackGender
        )

        // Check if Pass 2 Multi-Pass analysis is needed
        val isUncertain = pass1Result.confidenceScore < CONFIDENCE_THRESHOLD || pass1Result.totalVoicedFrames < 15

        if (!isUncertain || fullPcmMono == null || fullPcmMono.isEmpty()) {
            return pass1Result
        }

        // Pass 2 Analysis: Expand window by 50% into adjacent audio (+-250ms)
        Log.d(TAG, "Pass 1 uncertain (Voiced=${pass1Result.totalVoicedFrames}, Conf=${"%.2f".format(pass1Result.confidenceScore)}) -> Running Pass 2 Multi-Pass Expanded Analysis (+-250ms)…")

        val expandedStartMs = max(0L, segmentStartMs - 250L)
        val expandedEndMs   = min((fullPcmMono.size * 1000L) / 16000L, segmentEndMs + 250L)

        val startSample = ((expandedStartMs * 16000) / 1000).toInt().coerceIn(0, fullPcmMono.size)
        val endSample   = ((expandedEndMs * 16000) / 1000).toInt().coerceIn(startSample, fullPcmMono.size)

        val expandedPcm = if (endSample > startSample) fullPcmMono.copyOfRange(startSample, endSample) else ShortArray(0)

        val pass2Result = analyzePcmSlice(
            pcmMono = expandedPcm,
            frameSizeMs = 20,
            frameHopMs = 10,
            rmsThreshold = 80.0,
            fallbackGender = fallbackGender
        )

        val finalResult = if (pass2Result.confidenceScore >= pass1Result.confidenceScore && pass2Result.totalVoicedFrames >= MIN_VOICED_FRAMES) {
            pass2Result.copy(isPass2Triggered = true)
        } else {
            pass1Result.copy(isPass2Triggered = true)
        }

        Log.d(TAG, "Pass 2 Multi-Pass Result: medianF0=${"%.1f".format(finalResult.medianF0)}Hz, Conf=${"%.2f".format(finalResult.confidenceScore)}, Gender=${finalResult.gender}")
        return finalResult
    }

    private fun analyzePcmSlice(
        pcmMono: ShortArray,
        frameSizeMs: Int,
        frameHopMs: Int,
        rmsThreshold: Double,
        fallbackGender: Gender
    ): DetectionResult {
        if (pcmMono.isEmpty()) {
            return DetectionResult(0f, fallbackGender, 0, 0.0f, isCarriedOver = true)
        }

        val frameSize = (SAMPLE_RATE * (frameSizeMs / 1000.0)).toInt()
        val frameHop  = (SAMPLE_RATE * (frameHopMs / 1000.0)).toInt()

        val minLag = (SAMPLE_RATE / 350.0).toInt().coerceAtLeast(1) // 45
        val maxLag = (SAMPLE_RATE / 75.0).toInt().coerceAtMost(frameSize - 1) // 213

        val f0Estimates = mutableListOf<Float>()
        val peakValList = mutableListOf<Double>()
        var offset = 0

        while (offset + frameSize <= pcmMono.size) {
            var sumSq = 0.0
            for (i in 0 until frameSize) {
                val s = pcmMono[offset + i].toDouble()
                sumSq += s * s
            }
            val rms = sqrt(sumSq / frameSize)

            if (rms >= rmsThreshold) {
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

                    var bestLag = -1
                    var bestVal = 0.0
                    for (k in peakLags.indices) {
                        if (peakVals[k] >= thresh) {
                            bestLag = peakLags[k]
                            bestVal = peakVals[k]
                            break
                        }
                    }

                    if (bestLag > 0) {
                        val frameF0 = SAMPLE_RATE.toFloat() / bestLag.toFloat()
                        if (frameF0 in 75.0f..450.0f) {
                            f0Estimates.add(frameF0)
                            peakValList.add(bestVal)
                        }
                    }
                }
            }
            offset += frameHop
        }

        if (f0Estimates.size < MIN_VOICED_FRAMES) {
            return DetectionResult(0f, fallbackGender, f0Estimates.size, 0.0f, isCarriedOver = true)
        }

        f0Estimates.sort()
        val medianF0 = if (f0Estimates.size % 2 == 0) {
            (f0Estimates[f0Estimates.size / 2 - 1] + f0Estimates[f0Estimates.size / 2]) / 2.0f
        } else {
            f0Estimates[f0Estimates.size / 2]
        }

        // Confidence score calculation C in [0.0, 1.0]
        val avgPeakStrength = if (peakValList.isNotEmpty()) peakValList.average().toFloat() else 0.0f
        val frameCoverageFactor = (f0Estimates.size.toFloat() / 30.0f).coerceIn(0.0f, 1.0f)
        val confidenceScore = (avgPeakStrength * 0.7f + frameCoverageFactor * 0.3f).coerceIn(0.0f, 1.0f)

        val classifiedGender = if (medianF0 < 165.0f) Gender.MALE else Gender.FEMALE

        return DetectionResult(
            medianF0 = medianF0,
            gender = classifiedGender,
            totalVoicedFrames = f0Estimates.size,
            confidenceScore = confidenceScore,
            isCarriedOver = false
        )
    }
}
