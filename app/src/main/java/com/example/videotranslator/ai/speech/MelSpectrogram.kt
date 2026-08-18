package com.example.videotranslator.ai.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * 80-Channel Log-Mel Filterbank Feature Extractor for Whisper Multilingual STT.
 * Compliant with OpenAI Whisper audio pre-processing specification.
 */
class MelSpectrogram(
    private val sampleRate: Int = 16_000,
    private val nFft: Int = 400,
    private val hopLength: Int = 160,
    private val nMels: Int = 80
) {

    private val window: FloatArray = FloatArray(nFft) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / nFft))).toFloat()
    }

    private val melFilters: Array<FloatArray> = createMelFilterbank()

    fun extract(pcm: ShortArray): Array<FloatArray> {
        if (pcm.isEmpty()) return Array(1) { FloatArray(nMels) }

        val floatSamples = FloatArray(pcm.size) { i -> pcm[i] / 32768.0f }
        val numFrames = max(1, (floatSamples.size - nFft) / hopLength + 1)
        val melFrames = Array(numFrames) { FloatArray(nMels) }

        val real = FloatArray(nFft)
        val imag = FloatArray(nFft)
        val power = FloatArray(nFft / 2 + 1)

        for (frame in 0 until numFrames) {
            val start = frame * hopLength
            for (i in 0 until nFft) {
                val sampleIdx = start + i
                real[i] = if (sampleIdx < floatSamples.size) floatSamples[sampleIdx] * window[i] else 0f
                imag[i] = 0f
            }

            // In-place FFT
            fft(real, imag)

            // Power spectrum
            for (i in 0..nFft / 2) {
                power[i] = real[i] * real[i] + imag[i] * imag[i]
            }

            // Apply 80 Mel filters and take log10
            for (m in 0 until nMels) {
                var melEnergy = 0f
                val filter = melFilters[m]
                for (k in 0..nFft / 2) {
                    melEnergy += power[k] * filter[k]
                }
                melFrames[frame][m] = (ln(max(1e-5f, melEnergy)) / ln(10.0f)).toFloat()
            }
        }

        return melFrames
    }

    private fun createMelFilterbank(): Array<FloatArray> {
        val numBins = nFft / 2 + 1
        val filters = Array(nMels) { FloatArray(numBins) }

        val minMel = hzToMel(0f)
        val maxMel = hzToMel(sampleRate / 2f)

        val melPoints = FloatArray(nMels + 2) { i ->
            minMel + i * (maxMel - minMel) / (nMels + 1)
        }
        val binPoints = IntArray(nMels + 2) { i ->
            ((nFft + 1) * melToHz(melPoints[i]) / sampleRate).toInt().coerceIn(0, numBins - 1)
        }

        for (m in 1..nMels) {
            val left = binPoints[m - 1]
            val center = binPoints[m]
            val right = binPoints[m + 1]

            for (k in left until center) {
                if (center > left) filters[m - 1][k] = (k - left).toFloat() / (center - left)
            }
            for (k in center until right) {
                if (right > center) filters[m - 1][k] = (right - k).toFloat() / (right - center)
            }
        }

        return filters
    }

    private fun hzToMel(hz: Float): Float = 2595f * (ln(1f + hz / 700f) / ln(10.0f)).toFloat()
    private fun melToHz(mel: Float): Float = 700f * (Math.pow(10.0, (mel / 2595.0)) - 1.0).toFloat()

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }

        var l = 1
        while (l < n) {
            val step = l * 2
            val angle = -PI / l
            for (m in 0 until l) {
                val wr = cos(m * angle).toFloat()
                val wi = sin(m * angle).toFloat()
                for (i in m until n step step) {
                    val next = i + l
                    val tr = wr * real[next] - wi * imag[next]
                    val ti = wr * imag[next] + wi * real[next]
                    real[next] = real[i] - tr
                    imag[next] = imag[i] - ti
                    real[i] += tr
                    imag[i] += ti
                }
            }
            l = step
        }
    }
}
