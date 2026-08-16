package com.example.videotranslator.stt

import android.content.Context
import android.util.Log
import com.example.videotranslator.model.Language
import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

private const val TAG = "VoskSpeechRecognizer"
private const val MODEL_HI_ASSET_ZIP = "model-hi-small.zip"
private const val MODEL_EN_ASSET_ZIP = "model-en-small.zip"

/**
 * Stage 2 Dual-Model Vosk Speech-to-Text Recognizer & Language Prober.
 *
 * Real Acoustic-Confidence Source Language Detection:
 *  1. Runs a 30-second dual-probe across both Hindi and English Vosk models.
 *  2. Evaluates average word confidence and recognition density (words/sec).
 *  3. Decision rules:
 *     - English model dominates -> ENGLISH
 *     - Hindi model dominates -> HINDI
 *     - Both models fail to recognize clean speech (< 0.12 score) -> TELUGU (by elimination)
 */
class VoskSpeechRecognizer(private val context: Context) {

    private var hiModel: Model? = null
    private var enModel: Model? = null

    private data class WordInfo(
        val word: String,
        val startMs: Long,
        val endMs: Long,
        val confidence: Double
    )

    suspend fun loadModel() = withContext(Dispatchers.IO) {
        if (hiModel != null) return@withContext

        // Load Hindi Vosk Model
        val hiModelDir = File(context.filesDir, "vosk-hi-model")
        var hiVoskRoot = findVoskRoot(hiModelDir)
        if (hiVoskRoot == null || !hiVoskRoot.exists()) {
            Log.d(TAG, "STAGE 2 - Extracting Hindi Vosk model asset…")
            extractZipFromAssets(MODEL_HI_ASSET_ZIP, hiModelDir)
            hiVoskRoot = findVoskRoot(hiModelDir)
        }
        val rootHi = hiVoskRoot ?: throw IllegalStateException("Hindi Vosk model root directory not found")
        Log.d(TAG, "STAGE 2 - Loading Hindi Vosk model from: ${rootHi.absolutePath}")
        hiModel = Model(rootHi.absolutePath)
        Log.d(TAG, "STAGE 2 - Hindi Vosk model loaded successfully ✓")

        // Load English Vosk Model (if present in assets)
        try {
            val enModelDir = File(context.filesDir, "vosk-en-model")
            var enVoskRoot = findVoskRoot(enModelDir)
            if (enVoskRoot == null || !enVoskRoot.exists()) {
                if (context.assets.list("")?.contains(MODEL_EN_ASSET_ZIP) == true) {
                    Log.d(TAG, "STAGE 2 - Extracting English Vosk model asset…")
                    extractZipFromAssets(MODEL_EN_ASSET_ZIP, enModelDir)
                    enVoskRoot = findVoskRoot(enModelDir)
                }
            }
            if (enVoskRoot != null && enVoskRoot.exists()) {
                Log.d(TAG, "STAGE 2 - Loading English Vosk model from: ${enVoskRoot.absolutePath}")
                enModel = Model(enVoskRoot.absolutePath)
                Log.d(TAG, "STAGE 2 - English Vosk model loaded successfully ✓")
            } else {
                Log.w(TAG, "English Vosk asset '$MODEL_EN_ASSET_ZIP' not found, running Hindi-only mode.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load English Vosk model: ${e.localizedMessage}")
        }
    }

    fun close() {
        hiModel?.close()
        enModel?.close()
        hiModel = null
        enModel = null
    }

    /**
     * Probes the first 30 seconds of audio against both Hindi and English models
     * using acoustic recognition confidence and word density.
     */
    suspend fun probeLanguage(pcm: ShortArray): Language = withContext(Dispatchers.IO) {
        val mHi = hiModel ?: return@withContext Language.HINDI
        if (pcm.isEmpty()) return@withContext Language.HINDI

        val probeLength = (16_000 * 30).coerceAtMost(pcm.size)
        val probePcm = pcm.copyOfRange(0, probeLength)
        val durationSec = probeLength / 16000.0

        // Probe Hindi model
        val hiWords = runVoskPass(mHi, probePcm)
        val hiAvgConf = if (hiWords.isNotEmpty()) hiWords.map { it.confidence }.average() else 0.0
        val hiScore = (hiAvgConf * (hiWords.size / durationSec)).toFloat()

        // Probe English model
        var enWords = emptyList<WordInfo>()
        var enAvgConf = 0.0
        var enScore = 0.0f
        val mEn = enModel
        if (mEn != null) {
            enWords = runVoskPass(mEn, probePcm)
            enAvgConf = if (enWords.isNotEmpty()) enWords.map { it.confidence }.average() else 0.0
            enScore = (enAvgConf * (enWords.size / durationSec)).toFloat()
        }

        DiagnosticLogger.log(TAG,
            "REAL ACOUSTIC STT DUAL-PROBE RESULTS (Sample: ${"%.1f".format(durationSec)}s):\n" +
            "   Hindi model probe:   ${hiWords.size} words, avgConf=${"%.2f".format(hiAvgConf)} -> HINDI score=${"%.3f".format(hiScore)}\n" +
            "   English model probe: ${enWords.size} words, avgConf=${"%.2f".format(enAvgConf)} -> ENGLISH score=${"%.3f".format(enScore)}"
        )

        val detected = when {
            // English dominates clearly
            mEn != null && enScore >= 0.15f && enScore > hiScore * 1.25f -> Language.ENGLISH

            // Hindi dominates clearly
            hiScore >= 0.15f && hiScore > enScore * 1.25f -> Language.HINDI

            // Both models failed to recognize clean speech -> Telugu by elimination
            hiScore < 0.12f && enScore < 0.12f -> Language.TELUGU

            // Fallback score comparison
            mEn != null && enScore > hiScore -> Language.ENGLISH
            else -> Language.HINDI
        }

        DiagnosticLogger.log(TAG, "▶ PROBED SOURCE LANGUAGE DETECTED: $detected")
        detected
    }

    suspend fun recognise(
        pcm: ShortArray,
        sourceLanguage: Language = Language.HINDI
    ): List<TranslationSegment> = withContext(Dispatchers.IO) {
        val m = if (sourceLanguage == Language.ENGLISH && enModel != null) enModel!! else (hiModel ?: throw IllegalStateException("Vosk model is not loaded"))
        if (pcm.isEmpty()) return@withContext emptyList()

        val sampleRate = 16_000f
        Log.d(TAG, "STAGE 2 - Starting full Vosk recognition with model for $sourceLanguage: sampleRate=$sampleRate, pcmSamples=${pcm.size} (${"%.2f".format(pcm.size / 16000.0)}s)")

        val allWords = runVoskPass(m, pcm)

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

    private fun runVoskPass(m: Model, pcm: ShortArray): List<WordInfo> {
        val sampleRate = 16_000f
        val chunkSize = 4096
        val rec = Recognizer(m, sampleRate)
        rec.setWords(true)

        val words = mutableListOf<WordInfo>()
        var chunkStart = 0

        while (chunkStart < pcm.size) {
            val chunkEnd = (chunkStart + chunkSize).coerceAtMost(pcm.size)
            val chunk = pcm.copyOfRange(chunkStart, chunkEnd)
            if (rec.acceptWaveForm(chunk, chunk.size)) {
                words.addAll(extractWordsFromResult(rec.result))
            }
            chunkStart = chunkEnd
        }
        words.addAll(extractWordsFromResult(rec.finalResult))
        rec.close()
        return words
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
        val text = words.joinToString(" ") { it.word }.trim()
        if (text.isBlank()) return null

        val startMs = words.first().startMs
        val endMs = words.last().endMs.coerceAtLeast(startMs + 600L)

        return TranslationSegment(
            startMs = startMs,
            endMs = endMs,
            hindi = text
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
