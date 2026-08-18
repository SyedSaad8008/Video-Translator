package com.example.videotranslator.ai.speech

import kotlin.math.abs
import kotlin.math.max

/**
 * Energy & Spectral Voice Activity Detection (VAD) Speech Chunker.
 * Produces clean, non-overlapping speech intervals with startMs and endMs timestamps.
 */
class AudioSegmenter(
    private val frameSizeMs: Int = 30,
    private val sampleRate: Int = 16_000,
    private val minSpeechDurationMs: Long = 400L,
    private val maxSpeechDurationMs: Long = 8_000L,
    private val maxSilenceDurationMs: Long = 600L
) {

    data class SpeechInterval(
        val startMs: Long,
        val endMs: Long,
        val pcm: ShortArray
    )

    fun segmentSpeech(pcm: ShortArray): List<SpeechInterval> {
        if (pcm.isEmpty()) return emptyList()

        val samplesPerFrame = (sampleRate * frameSizeMs) / 1000
        val numFrames = pcm.size / samplesPerFrame
        if (numFrames == 0) return emptyList()

        // 1. Calculate frame RMS energies & dynamic noise floor
        val energies = FloatArray(numFrames)
        var sumEnergy = 0.0
        for (f in 0 until numFrames) {
            val start = f * samplesPerFrame
            var sumSq = 0.0
            for (i in 0 until samplesPerFrame) {
                val s = pcm[start + i].toDouble()
                sumSq += s * s
            }
            val rms = Math.sqrt(sumSq / samplesPerFrame).toFloat()
            energies[f] = rms
            sumEnergy += rms
        }

        val avgEnergy = (sumEnergy / numFrames).toFloat()
        val sortedEnergies = energies.clone().apply { sort() }
        val noiseFloor = sortedEnergies[(numFrames * 0.15f).toInt()]
        val speechThreshold = max(noiseFloor * 2.2f, avgEnergy * 0.40f).coerceAtLeast(180f)

        // 2. Classify voiced vs unvoiced frames
        val isVoiced = BooleanArray(numFrames) { i -> energies[i] >= speechThreshold }

        // 3. Form contiguous speech intervals with pause merging
        val intervals = mutableListOf<SpeechInterval>()
        var speechStartFrame = -1
        var silenceCount = 0
        val maxSilenceFrames = (maxSilenceDurationMs / frameSizeMs).toInt()
        val maxFramesPerChunk = (maxSpeechDurationMs / frameSizeMs).toInt()

        for (f in 0 until numFrames) {
            if (isVoiced[f]) {
                if (speechStartFrame == -1) speechStartFrame = f
                silenceCount = 0
            } else {
                if (speechStartFrame != -1) {
                    silenceCount++
                    val durationFrames = f - speechStartFrame
                    if (silenceCount >= maxSilenceFrames || durationFrames >= maxFramesPerChunk) {
                        val endFrame = f - silenceCount
                        addIntervalIfValid(pcm, speechStartFrame, endFrame, samplesPerFrame, intervals)
                        speechStartFrame = -1
                        silenceCount = 0
                    }
                }
            }
        }

        if (speechStartFrame != -1) {
            addIntervalIfValid(pcm, speechStartFrame, numFrames - 1, samplesPerFrame, intervals)
        }

        // Fallback: If no segments detected (low level), wrap full audio in single segment
        if (intervals.isEmpty()) {
            intervals.add(
                SpeechInterval(
                    startMs = 0L,
                    endMs = (pcm.size * 1000L) / sampleRate,
                    pcm = pcm
                )
            )
        }

        return intervals
    }

    private fun addIntervalIfValid(
        pcm: ShortArray,
        startFrame: Int,
        endFrame: Int,
        samplesPerFrame: Int,
        outList: MutableList<SpeechInterval>
    ) {
        val startSample = (startFrame * samplesPerFrame).coerceIn(0, pcm.size - 1)
        val endSample = ((endFrame + 1) * samplesPerFrame).coerceIn(startSample + 1, pcm.size)
        val durationMs = ((endSample - startSample) * 1000L) / sampleRate

        if (durationMs >= minSpeechDurationMs) {
            val chunkPcm = pcm.copyOfRange(startSample, endSample)
            val startMs = (startSample * 1000L) / sampleRate
            val endMs = (endSample * 1000L) / sampleRate
            outList.add(SpeechInterval(startMs = startMs, endMs = endMs, pcm = chunkPcm))
        }
    }
}
