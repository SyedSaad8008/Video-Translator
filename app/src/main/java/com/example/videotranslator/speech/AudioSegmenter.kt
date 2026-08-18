package com.example.videotranslator.speech

import com.example.videotranslator.util.DiagnosticLogger
import kotlin.math.sqrt

private const val TAG = "AudioSegmenter"
private const val SAMPLE_RATE = 16_000
private const val FRAME_SIZE = 400   // 25ms at 16kHz
private const val HOP_SIZE = 160     // 10ms hop
private const val MIN_SPEECH_DURATION_MS = 350L
private const val MAX_SPEECH_DURATION_MS = 10_000L
private const val SILENCE_SPLIT_MS = 600L

/**
 * Voice Activity Detection (VAD) and Audio Segmentation Engine.
 *
 * Breaks continuous 16kHz audio into clean, timestamped speech chunks
 * based on frame energy dynamics and silence pauses.
 */
class AudioSegmenter {

    data class SpeechInterval(
        val startMs: Long,
        val endMs: Long,
        val pcmChunk: ShortArray,
        val avgEnergy: Float
    )

    /**
     * Segments continuous audio into distinct speech intervals.
     */
    fun segmentSpeech(pcm: ShortArray): List<SpeechInterval> {
        if (pcm.size < FRAME_SIZE) return emptyList()

        val numFrames = (pcm.size - FRAME_SIZE) / HOP_SIZE + 1
        val frameEnergies = FloatArray(numFrames)

        // 1. Calculate frame RMS energy
        for (f in 0 until numFrames) {
            val offset = f * HOP_SIZE
            var sumSq = 0.0
            for (i in 0 until FRAME_SIZE) {
                val s = pcm[offset + i].toDouble()
                sumSq += s * s
            }
            frameEnergies[f] = sqrt(sumSq / FRAME_SIZE).toFloat()
        }

        // 2. Dynamic threshold calculation
        val sortedEnergies = frameEnergies.sorted()
        val noiseFloor = sortedEnergies.take((numFrames * 0.20).toInt().coerceAtLeast(1)).average().toFloat()
        val speechThreshold = (noiseFloor * 2.2f).coerceAtLeast(150.0f)

        // 3. Mark voiced frames with hysteresis
        val isVoiced = BooleanArray(numFrames)
        for (f in 0 until numFrames) {
            isVoiced[f] = frameEnergies[f] >= speechThreshold
        }

        // 4. Cluster into speech intervals
        val intervals = mutableListOf<SpeechInterval>()
        var speechStartFrame = -1
        var silentFramesCount = 0
        val silenceFrameLimit = (SILENCE_SPLIT_MS / 10).toInt()

        for (f in 0 until numFrames) {
            if (isVoiced[f]) {
                if (speechStartFrame == -1) {
                    speechStartFrame = f
                }
                silentFramesCount = 0
            } else {
                if (speechStartFrame != -1) {
                    silentFramesCount++
                    val currentDurMs = (f - speechStartFrame) * 10L
                    if (silentFramesCount >= silenceFrameLimit || currentDurMs >= MAX_SPEECH_DURATION_MS) {
                        val speechEndFrame = f - silentFramesCount
                        val durMs = (speechEndFrame - speechStartFrame) * 10L

                        if (durMs >= MIN_SPEECH_DURATION_MS) {
                            val startMs = (speechStartFrame * HOP_SIZE * 1000L) / SAMPLE_RATE
                            val endMs = ((speechEndFrame * HOP_SIZE + FRAME_SIZE) * 1000L) / SAMPLE_RATE
                            val startSample = (speechStartFrame * HOP_SIZE).coerceIn(0, pcm.size)
                            val endSample = (speechEndFrame * HOP_SIZE + FRAME_SIZE).coerceIn(startSample, pcm.size)
                            val chunk = pcm.copyOfRange(startSample, endSample)

                            val energySlice = frameEnergies.slice(speechStartFrame..speechEndFrame.coerceAtMost(numFrames - 1))
                            val avgEnergy = energySlice.average().toFloat()

                            intervals.add(SpeechInterval(startMs, endMs, chunk, avgEnergy))
                        }
                        speechStartFrame = -1
                        silentFramesCount = 0
                    }
                }
            }
        }

        // Handle trailing speech
        if (speechStartFrame != -1) {
            val speechEndFrame = numFrames - 1
            val durMs = (speechEndFrame - speechStartFrame) * 10L
            if (durMs >= MIN_SPEECH_DURATION_MS) {
                val startMs = (speechStartFrame * HOP_SIZE * 1000L) / SAMPLE_RATE
                val endMs = (pcm.size * 1000L) / SAMPLE_RATE
                val startSample = (speechStartFrame * HOP_SIZE).coerceIn(0, pcm.size)
                val chunk = pcm.copyOfRange(startSample, pcm.size)
                intervals.add(SpeechInterval(startMs, endMs, chunk, 200f))
            }
        }

        DiagnosticLogger.log(TAG, "AudioSegmenter: Found ${intervals.size} speech intervals (Noise floor: ${"%.1f".format(noiseFloor)}, Threshold: ${"%.1f".format(speechThreshold)})")
        return intervals
    }
}
