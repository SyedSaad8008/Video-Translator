package com.example.videotranslator.audio

import com.example.videotranslator.util.DiagnosticLogger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "AudioQualityAnalyzer"
private const val FFT_SIZE = 512
private const val HOP_SIZE = 256

enum class NoiseLevel {
    LOW,
    MEDIUM,
    HIGH,
    EXTREME
}

data class AudioQualityReport(
    val snrDb: Float,
    val noiseLevel: NoiseLevel,
    val musicDetected: Boolean,
    val echoDetected: Boolean,
    val clippingDetected: Boolean,
    val clippingPercentage: Float,
    val machineryDetected: Boolean,
    val speechConfidence: Float,
    val averageRms: Float,
    val peakRms: Float
)

/**
 * Real-time DSP Audio Quality & Acoustic Environment Analyzer.
 * Detects noise floors, SNR, clipping, background music, room echo/reverberation,
 * and machinery drone to guide adaptive enhancement.
 */
class AudioQualityAnalyzer {

    fun analyze(pcm: ShortArray): AudioQualityReport {
        if (pcm.size < FFT_SIZE * 2) {
            return AudioQualityReport(
                snrDb = 30f,
                noiseLevel = NoiseLevel.LOW,
                musicDetected = false,
                echoDetected = false,
                clippingDetected = false,
                clippingPercentage = 0f,
                machineryDetected = false,
                speechConfidence = 0.9f,
                averageRms = 1000f,
                peakRms = 2000f
            )
        }

        val numFrames = (pcm.size - FFT_SIZE) / HOP_SIZE + 1
        val frameRms = FloatArray(numFrames)
        var clippedSamples = 0
        var totalSamples = pcm.size

        // 1. Clipping & Basic Dynamic Range
        for (s in pcm) {
            if (abs(s.toInt()) >= 32700) {
                clippedSamples++
            }
        }
        val clippingPercentage = (clippedSamples.toFloat() / totalSamples.toFloat()) * 100f
        val isClipping = clippingPercentage > 0.02f

        // 2. Frame RMS energies
        var sumRms = 0.0
        var maxRms = 0f
        for (f in 0 until numFrames) {
            val offset = f * HOP_SIZE
            var sumSq = 0.0
            for (i in 0 until FFT_SIZE) {
                val s = pcm[offset + i].toDouble()
                sumSq += s * s
            }
            val rms = sqrt(sumSq / FFT_SIZE).toFloat()
            frameRms[f] = rms
            sumRms += rms
            if (rms > maxRms) maxRms = rms
        }
        val avgRms = (sumRms / numFrames).toFloat()

        // 3. SNR (Signal to Noise Ratio) Estimation
        val sortedRms = frameRms.clone().apply { sort() }
        val noiseFloorRms = sortedRms[(numFrames * 0.15f).toInt()].coerceAtLeast(10f)
        val speechPeakRms = sortedRms[(numFrames * 0.85f).toInt()].coerceAtLeast(noiseFloorRms)
        val snrDb = (20.0 * log10((speechPeakRms / noiseFloorRms).toDouble())).toFloat().coerceIn(-10f, 60f)

        val noiseLevel = when {
            snrDb >= 25f -> NoiseLevel.LOW
            snrDb >= 15f -> NoiseLevel.MEDIUM
            snrDb >= 6f  -> NoiseLevel.HIGH
            else         -> NoiseLevel.EXTREME
        }

        // 4. Spectral Analysis: Music, Reverberation, and Machinery Detection
        val hanning = DoubleArray(FFT_SIZE) { i -> 0.5 * (1.0 - cos(2.0 * Math.PI * i / FFT_SIZE)) }
        val realBuf = DoubleArray(FFT_SIZE)
        val imagBuf = DoubleArray(FFT_SIZE)

        var spectralFlatnessSum = 0.0
        var persistentNarrowBandHits = 0
        var totalAnalyzedFrames = 0

        val maxFramesToAnalyze = min(numFrames, 300)
        val step = max(1, numFrames / maxFramesToAnalyze)

        for (f in 0 until numFrames step step) {
            val offset = f * HOP_SIZE
            for (i in 0 until FFT_SIZE) {
                realBuf[i] = pcm[offset + i].toDouble() * hanning[i]
                imagBuf[i] = 0.0
            }
            fft(realBuf, imagBuf)

            var geomMeanLog = 0.0
            var arithMean = 0.0
            var peakVal = 0.0
            val numBins = FFT_SIZE / 2

            for (k in 1..numBins) {
                val mag = sqrt(realBuf[k] * realBuf[k] + imagBuf[k] * imagBuf[k]) + 1e-6
                geomMeanLog += log10(mag)
                arithMean += mag
                if (mag > peakVal) peakVal = mag
            }

            val geoMean = Math.pow(10.0, geomMeanLog / numBins)
            arithMean /= numBins
            val flatness = (geoMean / arithMean).coerceIn(0.0, 1.0)
            spectralFlatnessSum += flatness

            // Peakiness check for stationary machinery drone
            if (peakVal > arithMean * 4.5 && arithMean > 50.0) {
                persistentNarrowBandHits++
            }

            totalAnalyzedFrames++
        }

        val avgFlatness = if (totalAnalyzedFrames > 0) spectralFlatnessSum / totalAnalyzedFrames else 0.5
        val musicDetected = avgFlatness < 0.18 && avgRms > 200f
        val machineryDetected = (persistentNarrowBandHits.toFloat() / totalAnalyzedFrames.coerceAtLeast(1).toFloat()) > 0.35f

        // 5. Echo / Reverberation Detection: Short-time energy decay lag
        var echoDetected = false
        if (numFrames > 20) {
            var backwardSpillCount = 0
            for (f in 5 until numFrames - 5) {
                val prev = frameRms[f - 1]
                val curr = frameRms[f]
                val next1 = frameRms[f + 1]
                val next2 = frameRms[f + 2]
                // Detect slow decaying tail after sudden speech offset
                if (prev > 1500f && curr < prev * 0.45f && next1 > curr * 0.85f && next2 > curr * 0.70f) {
                    backwardSpillCount++
                }
            }
            echoDetected = backwardSpillCount >= 3
        }

        // 6. Speech Confidence Score
        val speechConfidence = when {
            isClipping && noiseLevel == NoiseLevel.EXTREME -> 0.45f
            noiseLevel == NoiseLevel.EXTREME -> 0.55f
            noiseLevel == NoiseLevel.HIGH -> 0.75f
            noiseLevel == NoiseLevel.MEDIUM -> 0.88f
            else -> 0.96f
        }

        val report = AudioQualityReport(
            snrDb = snrDb,
            noiseLevel = noiseLevel,
            musicDetected = musicDetected,
            echoDetected = echoDetected,
            clippingDetected = isClipping,
            clippingPercentage = clippingPercentage,
            machineryDetected = machineryDetected,
            speechConfidence = speechConfidence,
            averageRms = avgRms,
            peakRms = maxRms
        )

        DiagnosticLogger.log(
            TAG,
            "Acoustic Quality Analysis: SNR=${"%.1f".format(snrDb)}dB (${noiseLevel.name}), Music=${if (musicDetected) "YES" else "NO"}, Echo=${if (echoDetected) "YES" else "NO"}, Machinery=${if (machineryDetected) "YES" else "NO"}, Clipping=${if (isClipping) "YES (${"%.1f".format(clippingPercentage)}%)" else "NO"}, SpeechConf=${"%.2f".format(speechConfidence)}"
        )

        return report
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var len = 2
        while (len <= n) {
            val half = len shr 1
            val angle = -2.0 * Math.PI / len
            val wStepR = cos(angle)
            val wStepI = sin(angle)
            var i = 0
            while (i < n) {
                var wR = 1.0
                var wI = 0.0
                for (m in 0 until half) {
                    val uR = real[i + m]
                    val uI = imag[i + m]
                    val pos = i + m + half
                    val vR = real[pos] * wR - imag[pos] * wI
                    val vI = real[pos] * wI + imag[pos] * wR
                    real[i + m] = uR + vR
                    imag[i + m] = uI + vI
                    real[pos] = uR - vR
                    imag[pos] = uI - vI
                    val nextWR = wR * wStepR - wI * wStepI
                    val nextWI = wR * wStepI + wI * wStepR
                    wR = nextWR
                    wI = nextWI
                }
                i += len
            }
            len = len shl 1
        }
    }
}
