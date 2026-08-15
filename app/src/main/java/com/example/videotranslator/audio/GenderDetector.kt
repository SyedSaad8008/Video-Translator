package com.example.videotranslator.audio

import android.util.Log
import com.example.videotranslator.model.Gender
import com.example.videotranslator.util.DiagnosticLogger
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
 * Tier 0.5 Upgrade: Clamps Pass 2 window expansion (+-250ms) to prevent expanding backward into
 * preceding speech pauses or trailing room noise/breath tails, fixing female misclassification after pauses.
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
        segmentEndMs: Long = 0L,
        previousSegmentEndMs: Long = 0L
    ): DetectionResult {

        val pauseMs = max(0L, segmentStartMs - previousSegmentEndMs)

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
            DiagnosticLogger.log(TAG, "Pass 1 Decisive ($segmentStartMs ms -> $segmentEndMs ms, Pause=$pauseMs ms): F0=${"%.1f".format(pass1Result.medianF0)}Hz, Conf=${"%.2f".format(pass1Result.confidenceScore)}, Voiced=${pass1Result.totalVoicedFrames}, Gender=${pass1Result.gender}")
            return pass1Result
        }

        // Pass 2 Analysis: Expand window (+-250ms) BUT clamp expandedStartMs to previousSegmentEndMs to avoid pulling in silence/breath noise
        val clampedMinStartMs = max(previousSegmentEndMs, segmentStartMs)
        val expandedStartMs = max(clampedMinStartMs, segmentStartMs - 250L)
        val expandedEndMs   = min((fullPcmMono.size * 1000L) / 16000L, segmentEndMs + 250L)

        val startSample = ((expandedStartMs * 16000) / 1000).toInt().coerceIn(0, fullPcmMono.size)
        val endSample   = ((expandedEndMs * 16000) / 1000).toInt().coerceIn(startSample, fullPcmMono.size)

        val expandedPcm = if (endSample > startSample) fullPcmMono.copyOfRange(startSample, endSample) else ShortArray(0)

        DiagnosticLogger.log(TAG, "Pass 1 Uncertain ($segmentStartMs ms -> $segmentEndMs ms, Pause=$pauseMs ms, Conf=${"%.2f".format(pass1Result.confidenceScore)}) -> Running Pass 2 Clamped Expansion ($expandedStartMs ms -> $expandedEndMs ms)…")

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

        DiagnosticLogger.log(TAG, "Pass 2 Final Result: F0=${"%.1f".format(finalResult.medianF0)}Hz, Conf=${"%.2f".format(finalResult.confidenceScore)}, Voiced=${finalResult.totalVoicedFrames}, Gender=${finalResult.gender}")
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

        val minLag = (SAMPLE_RATE / 350.0).toInt().coerceAtLeast(1) // 45 (350Hz upper bound)
        val maxLag = (SAMPLE_RATE / 75.0).toInt().coerceAtMost(frameSize - 1) // 213 (75Hz lower bound)

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

                if (peakLags.isNotEmpty()) {
                    val maxPeakVal = peakVals.maxOrNull() ?: 0.0
                    for (i in peakLags.indices) {
                        if (peakVals[i] >= 0.70 * maxPeakVal) {
                            val f0 = SAMPLE_RATE.toFloat() / peakLags[i]
                            if (f0 in 75.0f..350.0f) {
                                f0Estimates.add(f0)
                                peakValList.add(peakVals[i])
                                break
                            }
                        }
                    }
                }
            }
            offset += frameHop
        }

        if (f0Estimates.isEmpty()) {
            return DetectionResult(0f, fallbackGender, 0, 0.0f, isCarriedOver = true)
        }

        f0Estimates.sort()
        val medianF0 = f0Estimates[f0Estimates.size / 2]

        val gender = if (medianF0 < 165.0f) Gender.MALE else Gender.FEMALE
        val avgPeakVal = if (peakValList.isNotEmpty()) peakValList.average().toFloat() else 0.0f
        val voicedRatio = (f0Estimates.size.toFloat() / max(1, (pcmMono.size / frameHop))).coerceAtMost(1.0f)
        val confidenceScore = (avgPeakVal * 0.7f + voicedRatio * 0.3f).coerceIn(0.0f, 1.0f)

        return DetectionResult(
            medianF0 = medianF0,
            gender = gender,
            totalVoicedFrames = f0Estimates.size,
            confidenceScore = confidenceScore
        )
    }
}
