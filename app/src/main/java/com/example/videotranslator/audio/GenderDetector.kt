package com.example.videotranslator.audio

import com.example.videotranslator.model.Gender
import com.example.videotranslator.util.DiagnosticLogger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "GenderDetector"
private const val SAMPLE_RATE = 16_000

// ── Ensemble thresholds ──────────────────────────────────────────────────────
private const val F0_MALE_FEMALE_BOUNDARY = 165.0f     // Hz — above = female-leaning
private const val SC_MALE_FEMALE_BOUNDARY = 2200.0f    // Hz — spectral centroid boundary
private const val HNR_RELIABILITY_FLOOR   = 4.0f       // dB — below this, F0 is untrustworthy (creaky)
private const val MIN_VOICED_FRAMES       = 8          // Minimum ~120ms of voiced speech

// ── Temporal smoothing ───────────────────────────────────────────────────────
private const val SMOOTHING_CONFIDENCE_CEILING = 0.68f // Below this, allow neighbor override

/**
 * Multi-Signal Ensemble Gender Classifier with Temporal Consistency Smoothing.
 *
 * Combines three independent acoustic signals per segment:
 *   1. YIN-based F0 (pitch) — kept from previous implementation
 *   2. Spectral Centroid — center of mass of the frequency spectrum;
 *      robust to vocal fry / creaky voice that corrupts F0
 *   3. Harmonic-to-Noise Ratio (HNR) — measures voice periodicity;
 *      low HNR flags unreliable F0 (creaky/trailing speech)
 *
 * Ensemble scoring weights each signal's vote by its own reliability,
 * producing a single confidence-weighted gender decision per segment.
 *
 * After per-segment classification, a temporal consistency pass corrects
 * isolated low-confidence outliers that disagree with both neighbors,
 * while preserving genuine high-confidence speaker transitions.
 */
class GenderDetector {

    data class DetectionResult(
        val medianF0: Float,
        val spectralCentroid: Float,
        val hnr: Float,
        val gender: Gender,
        val totalVoicedFrames: Int,
        val ensembleConfidence: Float,
        val f0Vote: Gender,
        val scVote: Gender,
        val isPass2Triggered: Boolean = false,
        val isCarriedOver: Boolean = false,
        val wasSmoothed: Boolean = false,
        // Legacy compat fields
        val confidenceScore: Float = 0f
    )

    // ── Public API: per-segment detection ────────────────────────────────────

    fun detectGender(
        pcmMono: ShortArray,
        fallbackGender: Gender = Gender.MALE,
        fullPcmMono: ShortArray? = null,
        segmentStartMs: Long = 0L,
        segmentEndMs: Long = 0L,
        previousSegmentEndMs: Long = 0L,
        transientMask: BooleanArray? = null
    ): DetectionResult {

        val pauseMs = max(0L, segmentStartMs - previousSegmentEndMs)

        // Pass 1: Analyze the segment's own PCM
        val pass1 = analyzeEnsemble(pcmMono, fallbackGender, segmentStartMs, transientMask)

        val isUncertain = pass1.ensembleConfidence < SMOOTHING_CONFIDENCE_CEILING ||
                pass1.totalVoicedFrames < MIN_VOICED_FRAMES

        if (!isUncertain || fullPcmMono == null || fullPcmMono.isEmpty()) {
            DiagnosticLogger.log(TAG, "Pass 1 Decisive (${segmentStartMs}ms→${segmentEndMs}ms, Pause=${pauseMs}ms): " +
                    "F0=${"%.1f".format(pass1.medianF0)}Hz(${pass1.f0Vote}), " +
                    "SC=${"%.0f".format(pass1.spectralCentroid)}Hz(${pass1.scVote}), " +
                    "HNR=${"%.1f".format(pass1.hnr)}dB, " +
                    "EnsConf=${"%.2f".format(pass1.ensembleConfidence)}, " +
                    "Voiced=${pass1.totalVoicedFrames}, Gender=${pass1.gender}")
            return pass1
        }

        // Pass 2: Expand window (clamped to avoid preceding pause)
        val clampedMinStartMs = max(previousSegmentEndMs, segmentStartMs)
        val expandedStartMs = max(clampedMinStartMs, segmentStartMs - 250L)
        val expandedEndMs = min((fullPcmMono.size * 1000L) / SAMPLE_RATE.toLong(), segmentEndMs + 250L)

        val startSample = ((expandedStartMs * SAMPLE_RATE) / 1000).toInt().coerceIn(0, fullPcmMono.size)
        val endSample = ((expandedEndMs * SAMPLE_RATE) / 1000).toInt().coerceIn(startSample, fullPcmMono.size)
        val expandedPcm = if (endSample > startSample) fullPcmMono.copyOfRange(startSample, endSample) else ShortArray(0)

        DiagnosticLogger.log(TAG, "Pass 1 Uncertain (${segmentStartMs}ms→${segmentEndMs}ms, " +
                "Pause=${pauseMs}ms, EnsConf=${"%.2f".format(pass1.ensembleConfidence)}) " +
                "→ Pass 2 Expanded (${expandedStartMs}ms→${expandedEndMs}ms)…")

        val pass2 = analyzeEnsemble(expandedPcm, fallbackGender)

        val final = if (pass2.ensembleConfidence >= pass1.ensembleConfidence &&
            pass2.totalVoicedFrames >= MIN_VOICED_FRAMES
        ) {
            pass2.copy(isPass2Triggered = true)
        } else {
            pass1.copy(isPass2Triggered = true)
        }

        DiagnosticLogger.log(TAG, "Pass 2 Final: F0=${"%.1f".format(final.medianF0)}Hz(${final.f0Vote}), " +
                "SC=${"%.0f".format(final.spectralCentroid)}Hz(${final.scVote}), " +
                "HNR=${"%.1f".format(final.hnr)}dB, " +
                "EnsConf=${"%.2f".format(final.ensembleConfidence)}, Gender=${final.gender}")
        return final
    }

    // ── Temporal consistency smoothing (called on the full sequence) ─────────

    fun smoothSequence(results: List<DetectionResult>): List<DetectionResult> {
        if (results.size < 3) return results

        val smoothed = results.toMutableList()
        var corrections = 0

        for (i in 1 until results.lastIndex) {
            val prev = smoothed[i - 1]
            val curr = smoothed[i]
            val next = smoothed[i + 1]

            val disagreesWithBoth = curr.gender != prev.gender && curr.gender != next.gender
            val neighborsAgree = prev.gender == next.gender
            val currIsLowConf = curr.ensembleConfidence < SMOOTHING_CONFIDENCE_CEILING

            if (disagreesWithBoth && neighborsAgree && currIsLowConf) {
                val correctedGender = prev.gender
                smoothed[i] = curr.copy(
                    gender = correctedGender,
                    wasSmoothed = true
                )
                corrections++

                DiagnosticLogger.log(TAG, "⚡ TEMPORAL SMOOTHING [seg $i]: " +
                        "Overriding ${curr.gender} → $correctedGender " +
                        "(EnsConf=${"%.2f".format(curr.ensembleConfidence)} < $SMOOTHING_CONFIDENCE_CEILING, " +
                        "neighbors=[${prev.gender}, ${next.gender}], " +
                        "F0=${"%.1f".format(curr.medianF0)}Hz, " +
                        "SC=${"%.0f".format(curr.spectralCentroid)}Hz, " +
                        "HNR=${"%.1f".format(curr.hnr)}dB)")
            }
        }

        if (corrections > 0) {
            DiagnosticLogger.log(TAG, "Temporal smoothing corrected $corrections/${results.size} segments")
        } else {
            DiagnosticLogger.log(TAG, "Temporal smoothing: no corrections needed (all segments consistent)")
        }

        return smoothed
    }

    // ── Core multi-signal analysis ───────────────────────────────────────────

    private fun analyzeEnsemble(
        pcmMono: ShortArray,
        fallbackGender: Gender,
        segmentStartMs: Long = 0L,
        transientMask: BooleanArray? = null
    ): DetectionResult {
        if (pcmMono.size == 0) {
            return DetectionResult(
                medianF0 = 0f, spectralCentroid = 0f, hnr = 0f,
                gender = fallbackGender, totalVoicedFrames = 0,
                ensembleConfidence = 0f, f0Vote = fallbackGender, scVote = fallbackGender,
                isCarriedOver = true, confidenceScore = 0f
            )
        }

        val frameSizeMs = 25
        val frameHopMs = 12
        val frameSize = (SAMPLE_RATE * (frameSizeMs / 1000.0)).toInt() // 400 samples
        val frameHop = (SAMPLE_RATE * (frameHopMs / 1000.0)).toInt()   // 192 samples

        val minLag = (SAMPLE_RATE / 350.0).toInt().coerceAtLeast(1)  // ~45 (350Hz)
        val maxLag = (SAMPLE_RATE / 75.0).toInt().coerceAtMost(frameSize - 1) // ~213 (75Hz)

        val f0Estimates = mutableListOf<Float>()
        val peakValList = mutableListOf<Double>()
        val frameRmsValues = mutableListOf<Double>()
        val rmsThreshold = 100.0

        val spectralCentroids = mutableListOf<Float>()
        val hnrValues = mutableListOf<Float>()

        var offset = 0
        var frameIndex = 0

        while (offset + frameSize <= pcmMono.size) {
            // Check transient mask for horn blast exclusion
            val absoluteFrameIdx = ((segmentStartMs * 16) / 256).toInt() + (offset / 256)
            val isTransientHorn = transientMask != null && absoluteFrameIdx in transientMask.indices && transientMask[absoluteFrameIdx]

            if (isTransientHorn) {
                DiagnosticLogger.log(TAG, "EXCLUDED TRANSIENT HORN FRAME at offset ${offset}ms from F0 pitch tracking")
                offset += frameHop
                frameIndex++
                continue
            }

            var sumSq = 0.0
            for (i in 0 until frameSize) {
                val s = pcmMono[offset + i].toDouble()
                sumSq += s * s
            }
            val rms = sqrt(sumSq / frameSize)

            if (rms >= rmsThreshold) {
                frameRmsValues.add(rms)

                // ── F0 via normalized autocorrelation ────────────────────
                val normAutocorr = DoubleArray(maxLag - minLag + 1)
                for (idx in normAutocorr.indices) {
                    val lag = minLag + idx
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

                // Find best autocorrelation peak
                var bestLag = -1
                var bestVal = 0.0
                for (i in 1 until normAutocorr.size - 1) {
                    val v = normAutocorr[i]
                    if (v >= 0.28 && v > normAutocorr[i - 1] && v > normAutocorr[i + 1]) {
                        if (v > bestVal) {
                            bestVal = v
                            bestLag = minLag + i
                        }
                    }
                }

                if (bestLag > 0) {
                    val f0 = SAMPLE_RATE.toFloat() / bestLag
                    if (f0 in 75.0f..350.0f) {
                        f0Estimates.add(f0)
                        peakValList.add(bestVal)

                        // ── Signal 3: HNR for this voiced frame ─────────────
                        // HNR ≈ 10 * log10(autocorr_peak / (1 - autocorr_peak))
                        val clampedPeak = bestVal.coerceIn(0.01, 0.99)
                        val hnr = (10.0 * Math.log10(clampedPeak / (1.0 - clampedPeak))).toFloat()
                        hnrValues.add(hnr)
                    }
                }

                // ── Signal 2: Spectral Centroid for this voiced frame ────
                val sc = computeSpectralCentroid(pcmMono, offset, frameSize)
                if (sc > 0f) {
                    spectralCentroids.add(sc)
                }
            }
            offset += frameHop
        }

        // ── Aggregate signals ────────────────────────────────────────────────

        if (f0Estimates.isEmpty()) {
            // No voiced frames at all — check if we can at least use spectral centroid
            if (spectralCentroids.isNotEmpty()) {
                val medianSC = median(spectralCentroids)
                val scGender = if (medianSC < SC_MALE_FEMALE_BOUNDARY) Gender.MALE else Gender.FEMALE
                return DetectionResult(
                    medianF0 = 0f, spectralCentroid = medianSC, hnr = 0f,
                    gender = scGender, totalVoicedFrames = 0,
                    ensembleConfidence = 0.35f, f0Vote = fallbackGender, scVote = scGender,
                    isCarriedOver = false, confidenceScore = 0.35f
                )
            }
            return DetectionResult(
                medianF0 = 0f, spectralCentroid = 0f, hnr = 0f,
                gender = fallbackGender, totalVoicedFrames = 0,
                ensembleConfidence = 0f, f0Vote = fallbackGender, scVote = fallbackGender,
                isCarriedOver = true, confidenceScore = 0f
            )
        }

        val medianF0 = median(f0Estimates)
        val medianSC = if (spectralCentroids.isNotEmpty()) median(spectralCentroids) else 0f
        val medianHNR = if (hnrValues.isNotEmpty()) median(hnrValues) else 0f

        // ── Per-signal votes ─────────────────────────────────────────────────
        val f0Vote = if (medianF0 < F0_MALE_FEMALE_BOUNDARY) Gender.MALE else Gender.FEMALE
        val scVote = if (medianSC < SC_MALE_FEMALE_BOUNDARY) Gender.MALE else Gender.FEMALE

        // ── Ensemble weighting ───────────────────────────────────────────────
        //
        // When HNR is high (periodic speech), F0 is reliable → weight it heavily.
        // When HNR is low (creaky/trailing), F0 is unreliable → discount it,
        //   lean on spectral centroid which is robust to creaky voice.
        //
        val f0Reliable = medianHNR >= HNR_RELIABILITY_FLOOR
        val f0Weight = if (f0Reliable) 0.55f else 0.20f
        val scWeight = if (f0Reliable) 0.35f else 0.65f
        val voicedBonus = 0.10f // small bonus for having many voiced frames

        // Score: how much evidence for MALE (0.0) vs FEMALE (1.0)
        val f0Score = ((medianF0 - 100f) / 200f).coerceIn(0f, 1f) // 100Hz→0, 300Hz→1
        val scScore = ((medianSC - 1200f) / 2000f).coerceIn(0f, 1f) // 1200Hz→0, 3200Hz→1
        val voicedRatio = (f0Estimates.size.toFloat() / max(1, pcmMono.size / frameHop)).coerceAtMost(1f)

        val ensembleScore = f0Weight * f0Score + scWeight * scScore + voicedBonus * voicedRatio
        val gender = if (ensembleScore < 0.48f) Gender.MALE else Gender.FEMALE

        // Confidence: how far from the decision boundary (0.48), scaled
        val distFromBoundary = abs(ensembleScore - 0.48f)
        val baseConfidence = (distFromBoundary / 0.48f).coerceIn(0f, 1f)
        val avgPeakVal = peakValList.average().toFloat()
        val ensembleConfidence = (baseConfidence * 0.6f + avgPeakVal * 0.25f + voicedRatio * 0.15f).coerceIn(0f, 1f)

        return DetectionResult(
            medianF0 = medianF0,
            spectralCentroid = medianSC,
            hnr = medianHNR,
            gender = gender,
            totalVoicedFrames = f0Estimates.size,
            ensembleConfidence = ensembleConfidence,
            f0Vote = f0Vote,
            scVote = scVote,
            confidenceScore = ensembleConfidence
        )
    }

    // ── Spectral centroid computation ─────────────────────────────────────────

    private fun computeSpectralCentroid(pcm: ShortArray, offset: Int, frameSize: Int): Float {
        // Use a simple DFT magnitude spectrum up to Nyquist (8000 Hz)
        // For efficiency, compute only magnitudes at ~50 Hz resolution
        val numBins = 160 // covers 0–8000Hz in 50Hz steps
        val binWidth = SAMPLE_RATE.toFloat() / frameSize

        var weightedSum = 0.0
        var magSum = 0.0

        for (k in 1..numBins.coerceAtMost(frameSize / 2)) {
            val freq = k * binWidth
            // DFT bin magnitude
            var realPart = 0.0
            var imagPart = 0.0
            val omega = 2.0 * PI * k / frameSize
            for (n in 0 until frameSize) {
                val sample = pcm[offset + n].toDouble()
                realPart += sample * cos(omega * n)
                imagPart -= sample * kotlin.math.sin(omega * n)
            }
            val mag = sqrt(realPart * realPart + imagPart * imagPart)
            weightedSum += freq * mag
            magSum += mag
        }

        return if (magSum > 0) (weightedSum / magSum).toFloat() else 0f
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
