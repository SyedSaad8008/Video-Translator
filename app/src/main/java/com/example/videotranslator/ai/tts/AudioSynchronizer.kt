package com.example.videotranslator.ai.tts

import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

private const val TAG = "AudioSynchronizer"

/**
 * Audio-Video Natural Timing & Speed Alignment Synchronizer.
 * Calculates natural human speaking rate and bounds speed strictly within 0.95x - 1.15x.
 */
class AudioSynchronizer {

    private val timingEngine = TranslationTimingEngine()

    fun calculateSpeedRatio(synthesizedDurationMs: Long, targetDurationMs: Long): Float {
        if (targetDurationMs <= 0L || synthesizedDurationMs <= 0L) return 1.0f
        val ratio = synthesizedDurationMs.toFloat() / targetDurationMs.toFloat()
        return ratio.coerceIn(0.95f, 1.15f)
    }

    suspend fun synchronizeSegments(
        segments: List<TranslationSegment>,
        outputDir: File,
        languagePrefix: String = ""
    ): List<TranslationSegment> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyList()

        DiagnosticLogger.log(TAG, "STAGE 6 - Synchronizing natural timing and pause allocation for ${segments.size} segments…")
        outputDir.mkdirs()

        val timedResults = timingEngine.calculateTiming(segments, outputDir, languagePrefix)
        val finalSegments = mutableListOf<TranslationSegment>()

        for (timed in timedResults) {
            val seg = timed.segment
            val audioFileName = if (languagePrefix.isNotBlank()) "dub_${languagePrefix}_${seg.id}.wav" else "dub_${seg.id}.wav"
            val audioFile = File(outputDir, audioFileName)

            if (!audioFile.exists() || audioFile.length() < 44L) {
                createSilentWav(audioFile, timed.originalDurationMs)
            }

            finalSegments.add(
                seg.copy(
                    audioFilePath = audioFile.absolutePath,
                    targetDurationMs = timed.originalDurationMs,
                    actualDurationMs = timed.actualTtsDurationMs,
                    speedRatio = timed.naturalSpeedRatio
                )
            )
        }

        DiagnosticLogger.log(TAG, "STAGE 6 - Natural audio timing alignment complete ✓ (All speaking speeds bounded to 0.95x-1.15x)")
        finalSegments
    }

    private fun createSilentWav(file: File, durationMs: Long) {
        val sampleRate = 16000
        val numSamples = ((sampleRate * durationMs) / 1000L).toInt()
        val dataSize = numSamples * 2
        val totalSize = dataSize + 36

        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.writeBytes("RIFF")
            raf.writeInt(Integer.reverseBytes(totalSize))
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeInt(Integer.reverseBytes(16))
            raf.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            raf.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            raf.writeInt(Integer.reverseBytes(sampleRate))
            raf.writeInt(Integer.reverseBytes(sampleRate * 2))
            raf.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt())
            raf.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
            raf.writeBytes("data")
            raf.writeInt(Integer.reverseBytes(dataSize))
            raf.write(ByteArray(dataSize))
        }
    }
}
