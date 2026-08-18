package com.example.videotranslator.translation

import android.content.Context
import com.example.videotranslator.model.Language
import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

private const val TAG = "TranslationManager"
private const val DIVERGENCE_RETRANSLATE_THRESHOLD = 0.45f

/**
 * Multi-Directional On-Device Translation Manager using NLLB-200.
 *
 * Supports all 6 directional translations locally:
 *   1. Hindi   (hin_Deva) → English (eng_Latn) & Telugu (tel_Telu)
 *   2. English (eng_Latn) → Hindi   (hin_Deva) & Telugu (tel_Telu)
 *   3. Telugu  (tel_Telu) → Hindi   (hin_Deva) & English (eng_Latn)
 *
 * Pipeline features:
 *  1. Disfluency & stutter cleanup
 *  2. Context-aware sentence grouping
 *  3. NLLB-200 INT8 ONNX translation
 *  4. Back-translation self-verification (target1 → source)
 *  5. Adaptive boundary re-clustering on high divergence
 *  6. Fine timestamp duration mapping
 */
class TranslationManager(context: Context? = null) {

    private val disfluencyCleaner = DisfluencyCleaner()
    private val translationContext = TranslationContext()
    private var nllbTranslator: NllbTranslator? = context?.let { NllbTranslator(it) }

    suspend fun downloadModels(): Result<Unit> = withContext(Dispatchers.IO) {
        DiagnosticLogger.log(TAG, "STAGE 4 - Initializing on-device translation engine…")
        return@withContext try {
            nllbTranslator?.loadEngine() ?: Result.success(Unit)
        } catch (e: Exception) {
            val msg = "Translation engine initialization failed: ${e.localizedMessage}"
            DiagnosticLogger.log(TAG, "STAGE 4 ERROR: $msg", e)
            Result.failure(IOException(msg, e))
        }
    }

    /**
     * Translates segments from sourceLanguage into all remaining target languages.
     */
    suspend fun translate(
        fineSegments: List<TranslationSegment>,
        sourceLanguage: Language = Language.HINDI
    ): List<TranslationSegment> = withContext(Dispatchers.Default) {
        if (fineSegments.isEmpty()) {
            DiagnosticLogger.log(TAG, "STAGE 4 - No segments to translate.")
            return@withContext emptyList()
        }

        val startTime = System.currentTimeMillis()
        val targets = Language.entries.filter { it != sourceLanguage }
        val target1 = targets[0]
        val target2 = targets[1]

        DiagnosticLogger.log(TAG,
            "STAGE 4 - NLLB-200 Source: $sourceLanguage (${sourceLanguage.nllbCode}) → Targets: $target1 (${target1.nllbCode}), $target2 (${target2.nllbCode}) | ${fineSegments.size} segments")

        val clusters = clusterSegmentsIntoFullSentences(fineSegments, gapThresholdMs = 1800L)
        DiagnosticLogger.log(TAG, "STAGE 4 - ${clusters.size} coarse sentence clusters")

        val result = mutableListOf<TranslationSegment>()

        for ((cIdx, cluster) in clusters.withIndex()) {
            val rawSourceText = cluster.joinToString(" ") { it.hindi.trim().ifBlank { it.sourceText.trim() } }.trim()
            if (rawSourceText.isBlank()) { result.addAll(cluster); continue }

            // 1. Disfluency cleanup
            val cleanupResult = disfluencyCleaner.clean(rawSourceText)
            val cleanedSource = cleanupResult.cleanedText

            // 2. Translate to target1 and target2 using NLLB engine
            val t1Text = translateSingle(cleanedSource, sourceLanguage, target1)
            val t2Text = translateSingle(cleanedSource, sourceLanguage, target2)

            // 3. Back-translation self-verification (target1 → source)
            val backText = if (t1Text.isNotBlank()) translateSingle(t1Text, target1, sourceLanguage) else ""
            val divergence = computeDivergenceScore(cleanedSource, backText)

            DiagnosticLogger.log(TAG, "Cluster [$cIdx] (${cluster.first().startMs}–${cluster.last().endMs}ms):\n" +
                    "   SOURCE (${sourceLanguage.nllbCode}): \"$cleanedSource\"\n" +
                    "   ${target1.nllbCode}: \"$t1Text\"\n" +
                    "   ${target2.nllbCode}: \"$t2Text\"\n" +
                    "   BACK-TRANSLATION: \"$backText\"\n" +
                    "   DIVERGENCE: ${"%.3f".format(divergence)}${if (divergence > DIVERGENCE_RETRANSLATE_THRESHOLD) " (HIGH ⚠️)" else " ✓"}")

            // 4. Adaptive boundary re-clustering on high divergence
            if (divergence > DIVERGENCE_RETRANSLATE_THRESHOLD && cluster.size > 1) {
                val mid = cluster.size / 2
                val a1 = cluster.subList(0, mid)
                val a2 = cluster.subList(mid, cluster.size)
                val c1 = disfluencyCleaner.clean(a1.joinToString(" ") { it.hindi.trim().ifBlank { it.sourceText.trim() } }).cleanedText
                val c2 = disfluencyCleaner.clean(a2.joinToString(" ") { it.hindi.trim().ifBlank { it.sourceText.trim() } }).cleanedText

                val t1a = translateSingle(c1, sourceLanguage, target1)
                val t2a = translateSingle(c1, sourceLanguage, target2)
                val t1b = translateSingle(c2, sourceLanguage, target1)
                val t2b = translateSingle(c2, sourceLanguage, target2)

                val bka = if (t1a.isNotBlank()) translateSingle(t1a, target1, sourceLanguage) else ""
                val bkb = if (t1b.isNotBlank()) translateSingle(t1b, target1, sourceLanguage) else ""
                val altDiv = (computeDivergenceScore(c1, bka) + computeDivergenceScore(c2, bkb)) / 2f

                if (altDiv < divergence) {
                    DiagnosticLogger.log(TAG, "⚡ RE-CLUSTERING [$cIdx]: ${"%.3f".format(divergence)} → ${"%.3f".format(altDiv)}")
                    result.addAll(mapToSegments(a1, t1a, t2a, sourceLanguage, target1, target2))
                    result.addAll(mapToSegments(a2, t1b, t2b, sourceLanguage, target1, target2))
                    continue
                }
            }

            result.addAll(mapToSegments(cluster, t1Text, t2Text, sourceLanguage, target1, target2))
        }

        val ms = System.currentTimeMillis() - startTime
        DiagnosticLogger.log(TAG, "STAGE 4 - NLLB translation complete for ${result.size} segments in ${ms}ms ✓")
        return@withContext result
    }

    private suspend fun translateSingle(text: String, from: Language, to: Language): String {
        if (text.isBlank() || from == to) return text
        return nllbTranslator?.translate(text, from, to) ?: text
    }

    private fun clusterSegmentsIntoFullSentences(
        segments: List<TranslationSegment>,
        gapThresholdMs: Long = 1800L
    ): List<List<TranslationSegment>> {
        val clusters = mutableListOf<MutableList<TranslationSegment>>()
        var currentCluster = mutableListOf<TranslationSegment>()

        for (seg in segments) {
            if (currentCluster.isEmpty()) {
                currentCluster.add(seg)
                continue
            }
            val prev = currentCluster.last()
            val pause = seg.startMs - prev.endMs
            val dur = seg.endMs - currentCluster.first().startMs

            if (pause >= gapThresholdMs || dur >= 15000L || currentCluster.size >= 25) {
                clusters.add(currentCluster)
                currentCluster = mutableListOf(seg)
            } else {
                currentCluster.add(seg)
            }
        }
        if (currentCluster.isNotEmpty()) clusters.add(currentCluster)
        return clusters
    }

    private fun computeDivergenceScore(s1: String, s2: String): Float {
        if (s1.isBlank() || s2.isBlank()) return 1.0f
        val w1 = s1.split("\\s+".toRegex()).map { it.lowercase() }
        val w2 = s2.split("\\s+".toRegex()).map { it.lowercase() }
        val intersection = w1.intersect(w2.toSet()).size
        val union = w1.union(w2.toSet()).size
        return if (union == 0) 1.0f else (1.0f - (intersection.toFloat() / union.toFloat()))
    }

    private fun mapToSegments(
        cluster: List<TranslationSegment>,
        t1Text: String,
        t2Text: String,
        sourceLanguage: Language,
        target1: Language,
        target2: Language
    ): List<TranslationSegment> {
        val totalMs = (cluster.last().endMs - cluster.first().startMs).coerceAtLeast(1L)
        val t1Words = t1Text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val t2Words = t2Text.split("\\s+".toRegex()).filter { it.isNotBlank() }

        val mapped = mutableListOf<TranslationSegment>()
        var t1Idx = 0
        var t2Idx = 0

        for ((i, seg) in cluster.withIndex()) {
            val ratio = (seg.endMs - seg.startMs).toDouble() / totalMs
            val isLast = i == cluster.lastIndex

            val t1Count = if (isLast) t1Words.size - t1Idx else (t1Words.size * ratio).toInt().coerceAtLeast(1)
            val t2Count = if (isLast) t2Words.size - t2Idx else (t2Words.size * ratio).toInt().coerceAtLeast(1)

            val t1Slice = t1Words.subList(t1Idx, min(t1Idx + t1Count, t1Words.size)).joinToString(" ")
            val t2Slice = t2Words.subList(t2Idx, min(t2Idx + t2Count, t2Words.size)).joinToString(" ")
            t1Idx += t1Count
            t2Idx += t2Count

            val (hindiText, englishText, teluguText) = when (sourceLanguage) {
                Language.HINDI -> Triple(seg.hindi, t1Slice.ifBlank { seg.english }, t2Slice.ifBlank { seg.telugu })
                Language.ENGLISH -> Triple(
                    if (target1 == Language.HINDI) t1Slice.ifBlank { seg.hindi } else t2Slice.ifBlank { seg.hindi },
                    seg.hindi.ifBlank { seg.sourceText },
                    if (target1 == Language.TELUGU) t1Slice.ifBlank { seg.telugu } else t2Slice.ifBlank { seg.telugu }
                )
                Language.TELUGU -> Triple(
                    if (target1 == Language.HINDI) t1Slice.ifBlank { seg.hindi } else t2Slice.ifBlank { seg.hindi },
                    if (target1 == Language.ENGLISH) t1Slice.ifBlank { seg.english } else t2Slice.ifBlank { seg.english },
                    seg.hindi.ifBlank { seg.sourceText }
                )
            }

            mapped.add(
                seg.copy(
                    id = "seg_${mapped.size}",
                    hindi = hindiText,
                    english = englishText,
                    telugu = teluguText,
                    sourceLanguage = sourceLanguage.nllbCode,
                    targetLanguage = target1.nllbCode,
                    sourceText = seg.hindi.ifBlank { seg.sourceText },
                    translatedText = if (sourceLanguage == Language.ENGLISH) hindiText else englishText
                )
            )
        }

        return mapped
    }
}
