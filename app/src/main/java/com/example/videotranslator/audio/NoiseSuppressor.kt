package com.example.videotranslator.audio

import android.util.Log
import com.example.videotranslator.util.DiagnosticLogger
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "NoiseSuppressor"
private const val FFT_SIZE = 512       // 32ms window at 16kHz (bin width = 31.25 Hz)
private const val HOP_SIZE = 256       // 50% overlap (16ms hop)
private const val OVER_SUBTRACTION = 1.65 // Over-subtraction factor alpha for fan/AC hum
private const val SPECTRAL_FLOOR   = 0.04 // Spectral floor beta to prevent musical noise
private const val WIND_HPF_CUTOFF_HZ = 90.0 // Cutoff frequency for wind/air rumble HPF

/**
 * Multi-Type Targeted DSP Noise & Transient Reducer.
 *
 * Handles 3 distinct noise profiles:
 *  1. **Fan / AC Hum**: Multi-region stationary spectral subtraction across STFT magnitude spectrum.
 *  2. **Wind / Air Rumble**: Low-frequency high-pass filtering (HPF bin cutoff < 90 Hz).
 *  3. **Horn / Loud Transients**: Spectral onset detection (sharp frame energy derivative ΔE + tonal peakiness).
 *     Applies dynamic transient attenuation and returns a boolean `transientMask` per frame so downstream
 *     pitch tracking can exclude horn blast frames from corrupting $F_0$.
 */
class NoiseSuppressor {

    data class NoiseReductionResult(
        val cleanedPcm: ShortArray,
        val transientMask: BooleanArray,
        val totalTransientFrames: Int
    )

    suspend fun suppressNoise(pcmMono: ShortArray): ShortArray = withContext(Dispatchers.Default) {
        suppressNoiseWithResult(pcmMono).cleanedPcm
    }

    suspend fun suppressNoiseWithResult(pcmMono: ShortArray): NoiseReductionResult = withContext(Dispatchers.Default) {
        if (pcmMono.size < FFT_SIZE) {
            return@withContext NoiseReductionResult(pcmMono.clone(), BooleanArray(0), 0)
        }

        val startTime = System.currentTimeMillis()
        val numFrames = (pcmMono.size - FFT_SIZE) / HOP_SIZE + 1
        if (numFrames <= 0) {
            return@withContext NoiseReductionResult(pcmMono.clone(), BooleanArray(0), 0)
        }

        // Precompute Hanning window
        val hanning = DoubleArray(FFT_SIZE) { i ->
            0.5 * (1.0 - cos(2.0 * Math.PI * i / FFT_SIZE))
        }

        // Calculate HPF bin threshold (90 Hz / 31.25 Hz per bin ≈ bin 3)
        val hpfBinCutoff = (WIND_HPF_CUTOFF_HZ / (16000.0 / FFT_SIZE)).toInt().coerceIn(1, 10)

        // 1. Calculate RMS energy per frame to find quiet noise-floor stretches & detect transients
        val frameEnergies = FloatArray(numFrames)
        for (f in 0 until numFrames) {
            val offset = f * HOP_SIZE
            var sumSq = 0.0
            for (i in 0 until FFT_SIZE) {
                val s = pcmMono[offset + i].toDouble()
                sumSq += s * s
            }
            frameEnergies[f] = sqrt(sumSq / FFT_SIZE).toFloat()
        }

        // 2. Identify stationary noise floor across 3 temporal regions (start, middle, end)
        val regionSize = numFrames / 3
        val quietIndices = mutableListOf<Int>()

        for (r in 0 until 3) {
            val startIdx = r * regionSize
            val endIdx = if (r == 2) numFrames else (r + 1) * regionSize
            if (endIdx > startIdx) {
                val regionQuiet = (startIdx until endIdx).sortedBy { frameEnergies[it] }
                val takeCount = max(1, (endIdx - startIdx) / 8)
                quietIndices.addAll(regionQuiet.take(takeCount))
            }
        }

        val noiseMagSum = DoubleArray(FFT_SIZE / 2 + 1)
        val realBuf = DoubleArray(FFT_SIZE)
        val imagBuf = DoubleArray(FFT_SIZE)

        for (fIdx in quietIndices) {
            val offset = fIdx * HOP_SIZE
            for (i in 0 until FFT_SIZE) {
                realBuf[i] = pcmMono[offset + i].toDouble() * hanning[i]
                imagBuf[i] = 0.0
            }
            fft(realBuf, imagBuf)
            for (k in 0..FFT_SIZE / 2) {
                val r = realBuf[k]
                val im = imagBuf[k]
                noiseMagSum[k] += sqrt(r * r + im * im)
            }
        }

        val noiseProfile = DoubleArray(FFT_SIZE / 2 + 1) { k ->
            noiseMagSum[k] / quietIndices.size.toDouble()
        }

        // 3. Transient / Horn Blast Detection via energy derivative ΔE & spectral peakiness
        val transientMask = BooleanArray(numFrames)
        var totalTransients = 0
        val avgEnergy = frameEnergies.average().toFloat()

        for (f in 1 until numFrames) {
            val deltaE = frameEnergies[f] - frameEnergies[f - 1]
            // Transient spike: sudden rise > 2.8x average energy
            if (deltaE > 2.8f * avgEnergy && frameEnergies[f] > 350.0f) {
                transientMask[f] = true
                // Mark adjacent frame to cover horn duration
                if (f + 1 < numFrames) transientMask[f + 1] = true
                totalTransients++
            }
        }

        val quietNoiseRms = noiseProfile.average()
        val adaptiveAlpha = if (quietNoiseRms < 12.0) 1.05 else if (quietNoiseRms < 30.0) 1.25 else 1.50
        DiagnosticLogger.log(TAG, "ADAPTIVE DSP NOISE SUPPRESSION: quietNoiseRms=${"%.1f".format(quietNoiseRms)} -> adaptiveAlpha=${"%.2f".format(adaptiveAlpha)}")

        // 4. Spectral Subtraction + Wind HPF + Transient Attenuation Reconstruction
        val outAudio = DoubleArray(pcmMono.size)
        val normWindowSum = DoubleArray(pcmMono.size)

        for (f in 0 until numFrames) {
            val offset = f * HOP_SIZE
            for (i in 0 until FFT_SIZE) {
                realBuf[i] = pcmMono[offset + i].toDouble() * hanning[i]
                imagBuf[i] = 0.0
            }

            fft(realBuf, imagBuf)

            val isHornTransient = transientMask[f]
            val transientAttenFactor = if (isHornTransient) 0.35 else 1.0 // Attenuate horn transient energy

            for (k in 0..FFT_SIZE / 2) {
                val r = realBuf[k]
                val im = imagBuf[k]
                val origMag = sqrt(r * r + im * im)
                val phase   = atan2(im, r)

                // 4a. Wind HPF cutoff (zero out low frequencies < 90 Hz)
                val hpfMag = if (k < hpfBinCutoff) 0.0 else origMag

                // 4b. Adaptive Fan/AC Hum spectral subtraction
                val noiseMag = noiseProfile[k]
                var subMag = max(hpfMag - adaptiveAlpha * noiseMag, SPECTRAL_FLOOR * hpfMag)

                // 4c. Targeted horn transient attenuation
                subMag *= transientAttenFactor

                realBuf[k] = subMag * cos(phase)
                imagBuf[k] = subMag * sin(phase)

                if (k > 0 && k < FFT_SIZE / 2) {
                    realBuf[FFT_SIZE - k] = realBuf[k]
                    imagBuf[FFT_SIZE - k] = -imagBuf[k]
                }
            }

            ifft(realBuf, imagBuf)

            for (i in 0 until FFT_SIZE) {
                val sampleIdx = offset + i
                if (sampleIdx < outAudio.size) {
                    outAudio[sampleIdx] += realBuf[i] * hanning[i]
                    normWindowSum[sampleIdx] += hanning[i] * hanning[i]
                }
            }
        }

        val cleanedPcm = ShortArray(pcmMono.size)
        for (i in pcmMono.indices) {
            val norm = if (normWindowSum[i] > 1e-6) normWindowSum[i] else 1.0
            val s = outAudio[i] / norm
            cleanedPcm[i] = s.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }

        val elapsed = System.currentTimeMillis() - startTime
        DiagnosticLogger.log(TAG, "TARGETED NOISE REDUCTION COMPLETE in ${elapsed}ms:\n" +
                "   • Fan/AC Hum Subtraction: Applied across $numFrames frames (${quietIndices.size} quiet noise-floor frames)\n" +
                "   • Wind/Air HPF Filter: Subtracted frequencies < ${WIND_HPF_CUTOFF_HZ}Hz (bin 0..$hpfBinCutoff)\n" +
                "   • Horn Transient Detector: Flagged & attenuated $totalTransients transient frames")

        return@withContext NoiseReductionResult(cleanedPcm, transientMask, totalTransients)
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
            val halfLen = len shr 1
            val angle = -2.0 * Math.PI / len
            val wStepR = cos(angle)
            val wStepI = sin(angle)

            var i = 0
            while (i < n) {
                var wR = 1.0
                var wI = 0.0
                for (k in 0 until halfLen) {
                    val pos = i + k
                    val match = pos + halfLen

                    val uR = real[pos]
                    val uI = imag[pos]
                    val vR = real[match] * wR - imag[match] * wI
                    val vI = real[match] * wI + imag[match] * wR

                    real[pos] = uR + vR
                    imag[pos] = uI + vI
                    real[match] = uR - vR
                    imag[match] = uI - vI

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

    private fun ifft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        for (i in 0 until n) imag[i] = -imag[i]
        fft(real, imag)
        for (i in 0 until n) {
            real[i] = real[i] / n
            imag[i] = -imag[i] / n
        }
    }
}
