package com.example.videotranslator.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "AudioExtractor"
private const val TARGET_SAMPLE_RATE = 16_000

/**
 * Stage 1 Audio Extractor & Processor.
 * Extracts 100% pristine original 16kHz mono audio from video containers.
 * Saves `original_extracted.wav` for developer inspection and developer control experiments.
 */
class AudioExtractor(private val context: Context) {

    data class ExtractionResult(
        /** 16 kHz mono, 16-bit LE — pristine audio for Vosk/Whisper */
        val mono: ShortArray,
        /** 16 kHz stereo interleaved (L, R, L, R…) — null when source is mono */
        val instrumental: ShortArray?
    )

    suspend fun extractToFiles(
        videoUri: Uri,
        monoFile: File,
        instrumentalFile: File
    ): ExtractionResult = withContext(Dispatchers.IO) {

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, videoUri, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set data source for $videoUri", e)
            throw IllegalStateException("Cannot read video file: ${e.message}")
        }

        var trackIdx = -1
        var inputFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            try {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    trackIdx = i
                    inputFormat = fmt
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error inspecting track $i", e)
            }
        }

        if (trackIdx < 0 || inputFormat == null) {
            throw IllegalStateException("No valid audio track found in video")
        }

        extractor.selectTrack(trackIdx)

        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalStateException("Audio track MIME type is null")

        val initialRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 44100

        val initialChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 2

        DiagnosticLogger.log(TAG, "STAGE 1 - Extracting Audio: mime=$mime, sourceRate=${initialRate}Hz, sourceChannels=$initialChannels")

        val codec: MediaCodec
        try {
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            extractor.release()
            Log.e(TAG, "Failed to create/start MediaCodec for $mime", e)
            throw IllegalStateException("Audio decoder initialization failed: ${e.message}")
        }

        var sampleRate = initialRate
        var channelCount = initialChannels
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        val monoStream = ByteArrayOutputStream(1024 * 1024 * 4)
        val leftStream = ByteArrayOutputStream(1024 * 1024 * 4)
        val rightStream = ByteArrayOutputStream(1024 * 1024 * 4)

        val timeoutUs = 10_000L
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()

        try {
            while (!outputDone && isActive) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        if (buf != null) {
                            buf.clear()
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        DiagnosticLogger.log(TAG, "STAGE 1 - Decoder format: rate=${sampleRate}Hz, channels=$channelCount, encoding=$pcmEncoding")
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)

                            val samples = decodeToShorts(outBuf, pcmEncoding)
                            processDecodedFrameToStreams(samples, channelCount, monoStream, leftStream, rightStream)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }

        val monoBytes = monoStream.toByteArray()
        val monoBuffer = ByteBuffer.wrap(monoBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val monoArr = ShortArray(monoBuffer.remaining()).also { monoBuffer.get(it) }

        val leftBytes = leftStream.toByteArray()
        val leftBuffer = ByteBuffer.wrap(leftBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val leftArr = if (leftBytes.isNotEmpty()) ShortArray(leftBuffer.remaining()).also { leftBuffer.get(it) } else monoArr

        val rightBytes = rightStream.toByteArray()
        val rightBuffer = ByteBuffer.wrap(rightBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val rightArr = if (rightBytes.isNotEmpty()) ShortArray(rightBuffer.remaining()).also { rightBuffer.get(it) } else monoArr

        val isStereo = channelCount >= 2

        // Anti-aliased high quality resampling to 16 kHz
        val monoRs = resampleAntiAliased(monoArr, sampleRate, TARGET_SAMPLE_RATE)
        val leftRs = resampleAntiAliased(leftArr, sampleRate, TARGET_SAMPLE_RATE)
        val rightRs = resampleAntiAliased(rightArr, sampleRate, TARGET_SAMPLE_RATE)

        // Peak Normalization (target peak = 26,000 / ~ -2dB peak)
        val mono = normalizeAudio(monoRs)

        // Calculate & log audio stats
        val peak = mono.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        var sumSq = 0.0
        for (s in mono) sumSq += s.toDouble() * s.toDouble()
        val rms = if (mono.isNotEmpty()) sqrt(sumSq / mono.size) else 0.0
        val durationSec = mono.size / 16000.0
        DiagnosticLogger.log(
            TAG,
            "STAGE 1 - Extracted Audio Stats: duration=${"%.2f".format(durationSec)}s (${mono.size} samples), peak=$peak, rms=${"%.1f".format(rms)}"
        )

        // Instrumental track = (L - R) / 2 (if stereo)
        val instrumental: ShortArray? = if (isStereo) {
            val cancelled = ShortArray(leftRs.size) { i ->
                ((leftRs[i].toInt() - rightRs[i].toInt()) / 2).toShort()
            }
            var instSumSq = 0.0
            val checkSamples = minOf(cancelled.size, 16000 * 10)
            for (i in 0 until checkSamples) {
                instSumSq += cancelled[i].toDouble() * cancelled[i].toDouble()
            }
            val instRms = if (checkSamples > 0) sqrt(instSumSq / checkSamples) else 0.0

            if (instRms < 100.0) {
                null
            } else {
                ShortArray(cancelled.size * 2) { i ->
                    if (i % 2 == 0) cancelled[i / 2] else (-cancelled[i / 2].toInt()).toShort()
                }
            }
        } else null

        // Write raw PCM to cache files
        writePcm(mono, monoFile)
        if (instrumental != null) writePcm(instrumental, instrumentalFile)

        // STAGE 1 DELIVERABLE: Export standard RIFF WAV files for developer verification
        val rawWavExport = File(context.filesDir, "original_extracted.wav")
        writeWavFile(mono, TARGET_SAMPLE_RATE, 1, rawWavExport)
        DiagnosticLogger.log(TAG, "STAGE 1 DELIVERABLE - Exported pristine WAV: ${rawWavExport.absolutePath} (${rawWavExport.length()} bytes) ✓")

        ExtractionResult(mono, instrumental)
    }

    suspend fun loadMonoFromCache(file: File): ShortArray = withContext(Dispatchers.IO) {
        readPcm(file)
    }

    suspend fun loadInstrumentalFromCache(file: File): ShortArray = withContext(Dispatchers.IO) {
        readPcm(file)
    }

    fun exportWav(samples: ShortArray, outputFile: File) {
        writeWavFile(samples, TARGET_SAMPLE_RATE, 1, outputFile)
    }

    // ─────────────────────────────── PCM Decoders ─────────────────────────────

    private fun decodeToShorts(buffer: ByteBuffer, encoding: Int): ShortArray {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floatBuf = buffer.asFloatBuffer()
                val count = floatBuf.remaining()
                ShortArray(count) {
                    val f = floatBuf.get()
                    (f.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val count = buffer.remaining()
                ShortArray(count) {
                    val b = buffer.get().toInt() and 0xFF
                    ((b - 128) * 256).toShort()
                }
            }
            else -> {
                // Default 16-bit LE PCM
                val shortBuf = buffer.asShortBuffer()
                val count = shortBuf.remaining()
                ShortArray(count).also { shortBuf.get(it) }
            }
        }
    }

    private fun processDecodedFrameToStreams(
        samples: ShortArray,
        channels: Int,
        outMono: ByteArrayOutputStream,
        outLeft: ByteArrayOutputStream,
        outRight: ByteArrayOutputStream
    ) {
        if (samples.isEmpty()) return
        val monoBytes = ByteArray(2)
        val leftBytes = ByteArray(2)
        val rightBytes = ByteArray(2)

        when (channels) {
            1 -> {
                for (s in samples) {
                    monoBytes[0] = (s.toInt() and 0xFF).toByte()
                    monoBytes[1] = ((s.toInt() shr 8) and 0xFF).toByte()
                    outMono.write(monoBytes)
                    outLeft.write(monoBytes)
                    outRight.write(monoBytes)
                }
            }
            2 -> {
                var i = 0
                while (i + 1 < samples.size) {
                    val l = samples[i]
                    val r = samples[i + 1]
                    val m = ((l.toInt() + r.toInt()) / 2).toShort()

                    monoBytes[0] = (m.toInt() and 0xFF).toByte()
                    monoBytes[1] = ((m.toInt() shr 8) and 0xFF).toByte()
                    leftBytes[0] = (l.toInt() and 0xFF).toByte()
                    leftBytes[1] = ((l.toInt() shr 8) and 0xFF).toByte()
                    rightBytes[0] = (r.toInt() and 0xFF).toByte()
                    rightBytes[1] = ((r.toInt() shr 8) and 0xFF).toByte()

                    outMono.write(monoBytes)
                    outLeft.write(leftBytes)
                    outRight.write(rightBytes)
                    i += 2
                }
            }
            6 -> { // 5.1 Surround: FL(0), FR(1), Center(2), LFE(3), SL(4), SR(5)
                var i = 0
                while (i + 5 < samples.size) {
                    val fl = samples[i].toInt()
                    val fr = samples[i + 1].toInt()
                    val fc = samples[i + 2].toInt()
                    val m = (fc * 0.50 + (fl + fr) * 0.25).toInt().coerceIn(-32768, 32767).toShort()

                    monoBytes[0] = (m.toInt() and 0xFF).toByte()
                    monoBytes[1] = ((m.toInt() shr 8) and 0xFF).toByte()
                    leftBytes[0] = (fl and 0xFF).toByte()
                    leftBytes[1] = ((fl shr 8) and 0xFF).toByte()
                    rightBytes[0] = (fr and 0xFF).toByte()
                    rightBytes[1] = ((fr shr 8) and 0xFF).toByte()

                    outMono.write(monoBytes)
                    outLeft.write(leftBytes)
                    outRight.write(rightBytes)
                    i += 6
                }
            }
            else -> {
                var i = 0
                while (i + channels - 1 < samples.size) {
                    var sum = 0
                    for (ch in 0 until channels) sum += samples[i + ch].toInt()
                    val m = (sum / channels).toShort()

                    monoBytes[0] = (m.toInt() and 0xFF).toByte()
                    monoBytes[1] = ((m.toInt() shr 8) and 0xFF).toByte()
                    outMono.write(monoBytes)
                    outLeft.write(monoBytes)
                    outRight.write(monoBytes)
                    i += channels
                }
            }
        }
    }

    // ─────────────────────────────── Audio Processing ───────────────────────────

    private fun resampleAntiAliased(input: ShortArray, inRate: Int, outRate: Int): ShortArray {
        if (inRate == outRate || input.isEmpty()) return input

        val filteredInput = if (inRate > outRate) {
            applyLowPassFilter(input, inRate, cutoffHz = (outRate * 0.45).toInt())
        } else {
            input
        }

        val ratio = inRate.toDouble() / outRate.toDouble()
        val outLen = (filteredInput.size / ratio).toInt()
        val out = ShortArray(outLen)

        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val lo = srcPos.toInt().coerceIn(0, filteredInput.size - 1)
            val hi = (lo + 1).coerceIn(0, filteredInput.size - 1)
            val frac = srcPos - lo
            out[i] = (filteredInput[lo] * (1 - frac) + filteredInput[hi] * frac).toInt().toShort()
        }

        return out
    }

    private fun applyLowPassFilter(input: ShortArray, sampleRate: Int, cutoffHz: Int): ShortArray {
        val numTaps = 31
        val halfTaps = numTaps / 2
        val fc = cutoffHz.toDouble() / sampleRate.toDouble()
        val weights = DoubleArray(numTaps)

        var sumWeights = 0.0
        for (i in 0 until numTaps) {
            val n = i - halfTaps
            val sinc = if (n == 0) 2.0 * PI * fc else sin(2.0 * PI * fc * n) / n
            val blackman = 0.42 - 0.5 * kotlin.math.cos(2.0 * PI * i / (numTaps - 1)) + 0.08 * kotlin.math.cos(4.0 * PI * i / (numTaps - 1))
            weights[i] = sinc * blackman
            sumWeights += weights[i]
        }

        for (i in 0 until numTaps) weights[i] /= sumWeights

        val output = ShortArray(input.size)
        for (i in input.indices) {
            var acc = 0.0
            for (j in 0 until numTaps) {
                val idx = (i - halfTaps + j).coerceIn(0, input.size - 1)
                acc += input[idx] * weights[j]
            }
            output[i] = acc.toInt().coerceIn(-32768, 32767).toShort()
        }

        return output
    }

    private fun normalizeAudio(pcm: ShortArray, targetPeak: Int = 26_000): ShortArray {
        if (pcm.isEmpty()) return pcm
        val maxPeak = pcm.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        if (maxPeak <= 0 || maxPeak == targetPeak) return pcm

        val gain = targetPeak.toDouble() / maxPeak.toDouble()
        if (maxPeak > 16000 && maxPeak < 31000) return pcm

        val normalized = ShortArray(pcm.size)
        for (i in pcm.indices) {
            normalized[i] = (pcm[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        return normalized
    }

    // ─────────────────────────────── I/O Helpers ───────────────────────────────

    private fun writePcm(samples: ShortArray, file: File) {
        FileOutputStream(file).use { fos ->
            val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { buf.putShort(it) }
            fos.write(buf.array())
        }
    }

    private fun writeWavFile(samples: ShortArray, sampleRate: Int, channels: Int, wavFile: File) {
        val pcmByteCount = samples.size * 2
        val totalDataLen = pcmByteCount + 36
        val byteRate = sampleRate * channels * 2

        FileOutputStream(wavFile).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

            header.put("RIFF".toByteArray())
            header.putInt(totalDataLen)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort((channels * 2).toShort())
            header.putShort(16.toShort())
            header.put("data".toByteArray())
            header.putInt(pcmByteCount)

            fos.write(header.array())

            val sampleBuf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { sampleBuf.putShort(it) }
            fos.write(sampleBuf.array())
        }
    }

    private fun readPcm(file: File): ShortArray {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buf.remaining()).also { buf.get(it) }
    }
}
