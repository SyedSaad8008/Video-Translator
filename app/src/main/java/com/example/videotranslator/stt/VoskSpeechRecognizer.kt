package com.example.videotranslator.stt

import android.content.Context
import android.util.Log
import com.example.videotranslator.model.TranslationSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

private const val TAG = "VoskSpeechRecognizer"
private const val MODEL_ASSET_ZIP = "model-hi-small.zip"

/**
 * Stage 2 Vosk Speech-to-Text Recognizer.
 *
 * Key Enhancements:
 *  1. **Detailed Diagnostic Logging**: Logs exact word confidence scores and raw transcripts per segment.
 *  2. **Sample Rate Alignment**: Enforces 16000 Hz sample rate matching the Stage 1 audio output.
 *  3. **Sentence-Level Speech Boundary Extraction**: Groups words into coherent full-sentence units.
 */
class VoskSpeechRecognizer(private val context: Context) {

    private var model: Model? = null

    private data class WordInfo(
        val word: String,
        val startMs: Long,
        val endMs: Long,
        val confidence: Double
    )

    suspend fun loadModel() = withContext(Dispatchers.IO) {
        if (model != null) return@withContext
        val modelDir = File(context.filesDir, "vosk-hi-model")
        var voskRoot = findVoskRoot(modelDir)
        if (voskRoot == null || !voskRoot.exists()) {
            Log.d(TAG, "STAGE 2 - Extracting Vosk model asset…")
            extractZipFromAssets(MODEL_ASSET_ZIP, modelDir)
            voskRoot = findVoskRoot(modelDir)
        }
        val root = voskRoot ?: throw IllegalStateException("Vosk model root directory not found")
        Log.d(TAG, "STAGE 2 - Loading Vosk model from: ${root.absolutePath}")
        model = Model(root.absolutePath)
        Log.d(TAG, "STAGE 2 - Vosk model loaded successfully ✓")
    }

    fun close() {
        model?.close()
        model = null
    }

    suspend fun recognise(pcm: ShortArray): List<TranslationSegment> = withContext(Dispatchers.IO) {
        val m = model ?: throw IllegalStateException("Vosk model is not loaded")
        if (pcm.isEmpty()) return@withContext emptyList()

        val sampleRate = 16_000f
        val chunkSize = 4096
        val rec = Recognizer(m, sampleRate)
        rec.setWords(true)

        Log.d(TAG, "STAGE 2 - Starting Vosk recognition: sampleRate=$sampleRate, pcmSamples=${pcm.size} (${"%.2f".format(pcm.size / 16000.0)}s)")

        val allWords = mutableListOf<WordInfo>()
        var chunkStart = 0

        while (chunkStart < pcm.size) {
            val chunkEnd = (chunkStart + chunkSize).coerceAtMost(pcm.size)
            val chunk = pcm.copyOfRange(chunkStart, chunkEnd)
            if (rec.acceptWaveForm(chunk, chunk.size)) {
                allWords.addAll(extractWordsFromResult(rec.result))
            }
            chunkStart = chunkEnd
        }
        allWords.addAll(extractWordsFromResult(rec.finalResult))
        rec.close()

        val avgConf = if (allWords.isNotEmpty()) allWords.map { it.confidence }.average() else 0.0
        Log.d(TAG, "STAGE 2 - Recognition total: ${allWords.size} words recognized, avgConfidence=${"%.2f".format(avgConf)}")

        // Group words into full complete sentences
        val segments = groupWordsIntoFullSentences(allWords)
        Log.d(TAG, "STAGE 2 - Sentence grouping complete: ${segments.size} full sentence segments")

        for ((idx, seg) in segments.withIndex()) {
            Log.d(TAG, "STAGE 2 - Segment [$idx] (${seg.startMs}ms - ${seg.endMs}ms): \"${seg.hindi}\"")
        }

        segments
    }

    private fun extractWordsFromResult(json: String): List<WordInfo> {
        val words = mutableListOf<WordInfo>()
        try {
            val obj = JSONObject(json)
            val wordsArr = obj.optJSONArray("result") ?: return emptyList()

            for (i in 0 until wordsArr.length()) {
                val item = wordsArr.getJSONObject(i)
                val wordStr = item.optString("word", "").trim()
                if (wordStr.isNotBlank()) {
                    val startSec = item.getDouble("start")
                    val endSec = item.getDouble("end")
                    val conf = item.optDouble("conf", 1.0)
                    val startMs = (startSec * 1000.0).toLong()
                    val endMs = (endSec * 1000.0).toLong()
                    words.add(WordInfo(wordStr, startMs, endMs, conf))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Vosk result JSON", e)
        }
        return words
    }

    private fun groupWordsIntoFullSentences(words: List<WordInfo>): List<TranslationSegment> {
        if (words.isEmpty()) return emptyList()

        val MAX_PAUSE_MS = 1200L      // 1.2s silence required to split sentences
        val MAX_DURATION_MS = 12000L   // Up to 12s per full sentence
        val MAX_WORDS = 30           // Up to 30 words per full sentence

        val segments = mutableListOf<TranslationSegment>()
        val currentWords = mutableListOf<WordInfo>()

        for (word in words) {
            if (currentWords.isEmpty()) {
                currentWords.add(word)
                continue
            }

            val prevWord = currentWords.last()
            val pauseMs = word.startMs - prevWord.endMs
            val currentDurationMs = word.endMs - currentWords.first().startMs

            val shouldSplit = pauseMs >= MAX_PAUSE_MS ||
                              currentDurationMs >= MAX_DURATION_MS ||
                              currentWords.size >= MAX_WORDS

            if (shouldSplit) {
                val seg = createSegmentFromWords(currentWords)
                if (seg != null) segments.add(seg)
                currentWords.clear()
            }
            currentWords.add(word)
        }

        if (currentWords.isNotEmpty()) {
            val seg = createSegmentFromWords(currentWords)
            if (seg != null) segments.add(seg)
        }

        return segments
    }

    private fun createSegmentFromWords(words: List<WordInfo>): TranslationSegment? {
        if (words.isEmpty()) return null
        val hindiText = words.joinToString(" ") { it.word }.trim()
        if (hindiText.isBlank()) return null

        val startMs = words.first().startMs
        val endMs = words.last().endMs.coerceAtLeast(startMs + 600L)

        return TranslationSegment(
            startMs = startMs,
            endMs = endMs,
            hindi = hindiText
        )
    }

    private fun findVoskRoot(base: File): File? {
        if (!base.exists() || !base.isDirectory) return null
        if (base.list()?.any { it == "am" || it == "graph" || it == "conf" } == true) return base
        return base.listFiles()?.firstOrNull { child ->
            child.isDirectory && child.list()?.any { it == "am" || it == "graph" || it == "conf" } == true
        }
    }

    private fun extractZipFromAssets(assetZipName: String, destDir: File) {
        destDir.mkdirs()
        context.assets.open(assetZipName).use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }
}
