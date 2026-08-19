package com.example.videotranslator.ai.speech

import com.example.videotranslator.util.DiagnosticLogger
import kotlin.math.max

private const val TAG = "AudioSegmenter"

/**
 * Energy & Spectral Voice Activity Detection (VAD) Speech Chunker with Word Boundary Padding.
 * Detailed segment diagnostics and speech preservation.
 */
class AudioSegmenter(
    private val frameSizeMs: Int = 30,
    private val sampleRate: Int = 16_000,
    private val minSpeechDurationMs: Long = 250L,
    private val maxSpeechDurationMs: Long = 15_000L,
    private val maxSilenceDurationMs: Long = 800L,
    private val preSpeechPaddingMs: Long = 300L,
    private val postSpeechPaddingMs: Long = 350L
) {

    data class SpeechInterval(
        val startMs: Long,
        val endMs: Long,
        val pcm: ShortArray
    )

    data class VadReport(
        val totalAudioSec: Double,
        val speechDurationSec: Double,
        val removedDurationSec: Double,
        val intervals: List<SpeechInterval>,
        val concatenatedSpeechPcm: ShortArray
    )

    fun analyzeAndSegment(pcm: ShortArray): VadReport {
        if (pcm.isEmpty()) {
            return VadReport(0.0, 0.0, 0.0, emptyList(), ShortArray(0))
        }

        val totalAudioSec = pcm.size / 16000.0
        val samplesPerFrame = (sampleRate * frameSizeMs) / 1000
        val numFrames = pcm.size / samplesPerFrame
        if (numFrames == 0) {
            val interval = SpeechInterval(0L, (pcm.size * 1000L) / sampleRate, pcm)
            return VadReport(totalAudioSec, totalAudioSec, 0.0, listOf(interval), pcm)
        }

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
        val speechThreshold = max(noiseFloor * 1.30f, 55f).coerceAtMost(avgEnergy * 0.35f)

        // 2. Classify voiced vs unvoiced frames
        val isVoiced = BooleanArray(numFrames) { i -> energies[i] >= speechThreshold }

        // 3. Form contiguous speech intervals with natural pause tolerance
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

        // Calculate total speech duration
        var totalSpeechMs = 0L
        for (interval in intervals) {
            totalSpeechMs += (interval.endMs - interval.startMs)
        }
        val speechSec = totalSpeechMs / 1000.0
        val removedSec = max(0.0, totalAudioSec - speechSec)

        DiagnosticLogger.log(
            TAG,
            "VAD Speech Analysis: TotalAudio=${"%.1f".format(totalAudioSec)}s, DetectedSpeech=${"%.1f".format(speechSec)}s, Removed=${"%.1f".format(removedSec)}s (${intervals.size} speech segments)"
        )

        for ((idx, interval) in intervals.withIndex()) {
            val startSec = interval.startMs / 1000.0
            val endSec = interval.endMs / 1000.0
            DiagnosticLogger.log(TAG, "  • Segment ${idx + 1}: ${"%.2f".format(startSec)}s → ${"%.2f".format(endSec)}s (duration: ${"%.2f".format(endSec - startSec)}s)")
        }

        // Concatenate all speech intervals
        val totalSamples = intervals.sumOf { it.pcm.size }
        val concatPcm = ShortArray(totalSamples)
        var writePos = 0
        for (interval in intervals) {
            System.arraycopy(interval.pcm, 0, concatPcm, writePos, interval.pcm.size)
            writePos += interval.pcm.size
        }

        // If VAD speech coverage is very small (< 50% of audio), protect against over-clipping by preserving full audio
        val effectiveIntervals = if (intervals.isEmpty() || speechSec < totalAudioSec * 0.40) {
            DiagnosticLogger.log(TAG, "▶ VAD speech ratio low (${"%.1f".format(speechSec)}s / ${"%.1f".format(totalAudioSec)}s). Preserving 100% full audio stream.")
            listOf(SpeechInterval(0L, (pcm.size * 1000L) / sampleRate, pcm))
        } else {
            intervals
        }

        return VadReport(
            totalAudioSec = totalAudioSec,
            speechDurationSec = speechSec,
            removedDurationSec = removedSec,
            intervals = effectiveIntervals,
            concatenatedSpeechPcm = if (concatPcm.isNotEmpty()) concatPcm else pcm
        )
    }

    fun segmentSpeech(pcm: ShortArray): List<SpeechInterval> {
        return analyzeAndSegment(pcm).intervals
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
