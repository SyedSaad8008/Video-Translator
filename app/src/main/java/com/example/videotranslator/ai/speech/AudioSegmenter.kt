package com.example.videotranslator.ai.speech

import kotlin.math.abs
import kotlin.math.max

/**
 * Energy & Spectral Voice Activity Detection (VAD) Speech Chunker with Word Boundary Padding.
 * Produces clean speech intervals with startMs and endMs timestamps without clipping word boundaries.
 */
class AudioSegmenter(
    private val frameSizeMs: Int = 30,
    private val sampleRate: Int = 16_000,
    private val minSpeechDurationMs: Long = 350L,
    private val maxSpeechDurationMs: Long = 9_000L,
    private val maxSilenceDurationMs: Long = 500L,
    private val preSpeechPaddingMs: Long = 200L,
    private val postSpeechPaddingMs: Long = 250L
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
        val speechThreshold = max(noiseFloor * 1.8f, avgEnergy * 0.35f).coerceAtLeast(150f)

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
        val padStartSamples = ((preSpeechPaddingMs * sampleRate) / 1000L).toInt()
        val padEndSamples = ((postSpeechPaddingMs * sampleRate) / 1000L).toInt()

        val rawStartSample = startFrame * samplesPerFrame
        val rawEndSample = (endFrame + 1) * samplesPerFrame

        val paddedStartSample = (rawStartSample - padStartSamples).coerceIn(0, pcm.size - 1)
        val paddedEndSample = (rawEndSample + padEndSamples).coerceIn(paddedStartSample + 1, pcm.size)
        val durationMs = ((paddedEndSample - paddedStartSample) * 1000L) / sampleRate

        if (durationMs >= minSpeechDurationMs) {
            val chunkPcm = pcm.copyOfRange(paddedStartSample, paddedEndSample)
            val startMs = (paddedStartSample * 1000L) / sampleRate
            val endMs = (paddedEndSample * 1000L) / sampleRate
            outList.add(SpeechInterval(startMs = startMs, endMs = endMs, pcm = chunkPcm))
        }
    }
}
