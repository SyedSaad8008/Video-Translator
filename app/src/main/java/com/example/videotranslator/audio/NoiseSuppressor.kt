package com.example.videotranslator.audio

import android.util.Log
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "NoiseSuppressor"
private const val FFT_SIZE = 512       // 32ms window at 16kHz
private const val HOP_SIZE = 256       // 50% overlap (16ms hop)
private const val OVER_SUBTRACTION = 1.6 // Over-subtraction factor alpha
private const val SPECTRAL_FLOOR   = 0.05 // Spectral floor beta to prevent musical noise

/**
 * Multi-Segment Adaptive DSP Spectral Subtraction & Wiener Filter Noise Reducer.
 *
 *  1. Performs STFT over 512-sample overlapping frames with Hanning windowing.
 *  2. Scans quiet stretches dynamically across multiple regions (start, middle, end) of the audio file to track non-stationary noise floors.
 *  3. Subtracts noise magnitude spectrum: |S(f)| = max(|X(f)| - alpha * |N(f)|, beta * |X(f)|).
 *  4. Reconstructs clean 16kHz PCM audio via Inverse FFT (IFFT) + Overlap-Add.
 */
class NoiseSuppressor {

    fun suppressNoise(pcmMono: ShortArray): ShortArray {
        if (pcmMono.size < FFT_SIZE) return pcmMono.clone()

        val startTime = System.currentTimeMillis()

        // Precompute Hanning window
        val hanning = DoubleArray(FFT_SIZE) { i ->
            0.5 * (1.0 - cos(2.0 * Math.PI * i / FFT_SIZE))
        }

        val numFrames = (pcmMono.size - FFT_SIZE) / HOP_SIZE + 1
        if (numFrames <= 0) return pcmMono.clone()

        // 1. Calculate RMS energy per frame to find quiet noise-floor stretches
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

        // Divide audio into 3 temporal regions (start, middle, end) to sample adaptive noise floor
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

        // 2. Build multi-segment adaptive noise spectrum profile |N(f)|
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
                val mag = sqrt(r * r + im * im)
                noiseMagSum[k] += mag
            }
        }

        val noiseProfile = DoubleArray(FFT_SIZE / 2 + 1) { k ->
            noiseMagSum[k] / quietIndices.size.toDouble()
        }

        // 3. Spectral Subtraction & Overlap-Add Reconstruction
        val outAudio = DoubleArray(pcmMono.size)
        val normWindowSum = DoubleArray(pcmMono.size)

        for (f in 0 until numFrames) {
            val offset = f * HOP_SIZE

            for (i in 0 until FFT_SIZE) {
                realBuf[i] = pcmMono[offset + i].toDouble() * hanning[i]
                imagBuf[i] = 0.0
            }

            fft(realBuf, imagBuf)

            // Spectral subtraction on magnitude spectrum
            for (k in 0..FFT_SIZE / 2) {
                val r = realBuf[k]
                val im = imagBuf[k]
                val origMag = sqrt(r * r + im * im)
                val phase   = atan2(im, r)

                val noiseMag = noiseProfile[k]
                val subMag   = max(origMag - OVER_SUBTRACTION * noiseMag, SPECTRAL_FLOOR * origMag)

                realBuf[k] = subMag * cos(phase)
                imagBuf[k] = subMag * sin(phase)

                // Mirror conjugate symmetric spectrum for real IFFT
                if (k > 0 && k < FFT_SIZE / 2) {
                    realBuf[FFT_SIZE - k] = realBuf[k]
                    imagBuf[FFT_SIZE - k] = -imagBuf[k]
                }
            }

            ifft(realBuf, imagBuf)

            // Overlap-Add
            for (i in 0 until FFT_SIZE) {
                val sampleIdx = offset + i
                if (sampleIdx < outAudio.size) {
                    outAudio[sampleIdx] += realBuf[i] * hanning[i]
                    normWindowSum[sampleIdx] += hanning[i] * hanning[i]
                }
            }
        }

        // Normalize overlap-add output
        val cleanedPcm = ShortArray(pcmMono.size)
        for (i in pcmMono.indices) {
            val norm = if (normWindowSum[i] > 1e-6) normWindowSum[i] else 1.0
            val s = outAudio[i] / norm
            cleanedPcm[i] = s.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Multi-Segment Adaptive Spectral Subtraction complete: processed ${pcmMono.size} samples across ${quietIndices.size} quiet frames in ${elapsed}ms")

        return cleanedPcm
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
        for (i in 0 until n) {
            imag[i] = -imag[i]
        }
        fft(real, imag)
        for (i in 0 until n) {
            real[i] = real[i] / n
            imag[i] = -imag[i] / n
        }
    }
}
