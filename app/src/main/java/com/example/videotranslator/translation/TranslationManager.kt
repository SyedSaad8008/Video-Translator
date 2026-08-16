package com.example.videotranslator.translation

import com.example.videotranslator.model.Language
import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.util.DiagnosticLogger
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

private const val TAG = "TranslationManager"
private const val MODEL_DOWNLOAD_TIMEOUT_MS = 30_000L
private const val DIVERGENCE_RETRANSLATE_THRESHOLD = 0.45f

/**
 * Multi-Directional ML Kit NMT Translation Manager with Self-Verification.
 *
 * Supports all three source languages (Hindi, Telugu, English) translating
 * automatically into the other two target languages:
 *   Hindi   → English + Telugu
 *   Telugu  → Hindi   + English
 *   English → Hindi   + Telugu
 *
 * Additional features:
 *  1. Disfluency cleanup before NMT
 *  2. Back-translation self-verification (Target-1 → Source) with divergence score
 *  3. Adaptive boundary re-clustering for low-confidence clusters
 *  4. Duration-proportional word mapping back onto fine audio sync segments
 */
class TranslationManager {

    private val disfluencyCleaner = DisfluencyCleaner()

    // ── All 6 directional ML Kit translator clients ────────────────────────────
    private val hiEnTranslator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.HINDI)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )
    private val hiTeTranslator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.HINDI)
            .setTargetLanguage(TranslateLanguage.TELUGU)
            .build()
    )
    private val enHiTranslator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.HINDI)
            .build()
    )
    private val enTeTranslator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TELUGU)
            .build()
    )
    private val teHiTranslator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.TELUGU)
            .setTargetLanguage(TranslateLanguage.HINDI)
            .build()
    )
    private val teEnTranslator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.TELUGU)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )

    suspend fun downloadModels(): Result<Unit> {
        DiagnosticLogger.log(TAG, "STAGE 3 - Ensuring all 3-language NMT models are ready (HI↔EN, HI↔TE, EN↔TE)…")
        return try {
            withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                listOf(
                    hiEnTranslator, hiTeTranslator,
                    enHiTranslator, enTeTranslator,
                    teHiTranslator, teEnTranslator
                ).forEachIndexed { i, client ->
                    val tag = listOf("HI→EN","HI→TE","EN→HI","EN→TE","TE→HI","TE→EN")[i]
                    DiagnosticLogger.log(TAG, "Checking/Downloading $tag model…")
                    client.downloadModelIfNeeded().await()
                }
            }
            DiagnosticLogger.log(TAG, "STAGE 3 - All NMT models ready ✓")
            Result.success(Unit)
        } catch (e: Exception) {
            val msg = "Failed to download NMT models: ${e.localizedMessage}"
            DiagnosticLogger.log(TAG, "STAGE 3 ERROR: $msg", e)
            Result.failure(IOException(msg, e))
        }
    }

    /**
     * Translates fine sync segments from the detected source language into
     * the two remaining target languages, with disfluency cleanup and
     * back-translation self-verification.
     *
     * Source language text is preserved as-is in the relevant field.
     * Target language fields are populated with translated text.
     */
    suspend fun translate(
        fineSegments: List<TranslationSegment>,
        sourceLanguage: Language = Language.HINDI
    ): List<TranslationSegment> {
        if (fineSegments.isEmpty()) {
            DiagnosticLogger.log(TAG, "STAGE 3 - No segments to translate.")
            return emptyList()
        }

        val startTime = System.currentTimeMillis()
        val targets = Language.entries.filter { it != sourceLanguage }
        val target1 = targets[0]
        val target2 = targets[1]

        DiagnosticLogger.log(TAG,
            "STAGE 3 - Source: $sourceLanguage → Targets: $target1, $target2 | ${fineSegments.size} segments")

        val clusters = clusterSegmentsIntoFullSentences(fineSegments, gapThresholdMs = 1800L)
        DiagnosticLogger.log(TAG, "STAGE 3 - ${clusters.size} coarse sentence clusters")

        val result = mutableListOf<TranslationSegment>()

        for ((cIdx, cluster) in clusters.withIndex()) {
            // Raw source text is stored in the `hindi` field by Vosk regardless of actual language
            val rawSourceText = cluster.joinToString(" ") { it.hindi.trim() }.trim()
            if (rawSourceText.isBlank()) { result.addAll(cluster); continue }

            // 1. Disfluency cleanup
            val cleanupResult = disfluencyCleaner.clean(rawSourceText)
            val cleanedSource = cleanupResult.cleanedText

            // 2. Translate to target1 and target2
            val t1Text = translateText(cleanedSource, sourceLanguage, target1)
            val t2Text = translateText(cleanedSource, sourceLanguage, target2)

            // 3. Back-translation self-verification (target1 → source)
            val backText     = if (t1Text.isNotBlank()) translateText(t1Text, target1, sourceLanguage) else ""
            val divergence   = computeDivergenceScore(cleanedSource, backText)

            DiagnosticLogger.log(TAG, "Cluster [$cIdx] (${cluster.first().startMs}–${cluster.last().endMs}ms):\n" +
                    "   SOURCE (${sourceLanguage}): \"$cleanedSource\"\n" +
                    "   ${target1}: \"$t1Text\"\n" +
                    "   ${target2}: \"$t2Text\"\n" +
                    "   BACK-TRANSLATION: \"$backText\"\n" +
                    "   DIVERGENCE: ${"%.3f".format(divergence)}${if (divergence > DIVERGENCE_RETRANSLATE_THRESHOLD) " (HIGH ⚠️)" else " ✓"}")

            // 4. Adaptive boundary re-clustering on high divergence
            if (divergence > DIVERGENCE_RETRANSLATE_THRESHOLD && cluster.size > 1) {
                val mid = cluster.size / 2
                val a1 = cluster.subList(0, mid)
                val a2 = cluster.subList(mid, cluster.size)
                val c1 = disfluencyCleaner.clean(a1.joinToString(" ") { it.hindi.trim() }).cleanedText
                val c2 = disfluencyCleaner.clean(a2.joinToString(" ") { it.hindi.trim() }).cleanedText

                val t1a = translateText(c1, sourceLanguage, target1)
                val t2a = translateText(c1, sourceLanguage, target2)
                val t1b = translateText(c2, sourceLanguage, target1)
                val t2b = translateText(c2, sourceLanguage, target2)

                val bka = if (t1a.isNotBlank()) translateText(t1a, target1, sourceLanguage) else ""
                val bkb = if (t1b.isNotBlank()) translateText(t1b, target1, sourceLanguage) else ""
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
        DiagnosticLogger.log(TAG, "STAGE 3 - Translation complete for ${result.size} segments in ${ms}ms ✓")
        return result
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private suspend fun translateText(text: String, from: Language, to: Language): String {
        if (text.isBlank()) return ""
        return try {
            val raw = translatorFor(from, to).translate(text).await()
            cleanSentence(raw)
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "$from→$to translation failed: ${e.localizedMessage}")
            ""
        }
    }

    private fun translatorFor(from: Language, to: Language) = when {
        from == Language.HINDI   && to == Language.ENGLISH  -> hiEnTranslator
        from == Language.HINDI   && to == Language.TELUGU   -> hiTeTranslator
        from == Language.ENGLISH && to == Language.HINDI    -> enHiTranslator
        from == Language.ENGLISH && to == Language.TELUGU   -> enTeTranslator
        from == Language.TELUGU  && to == Language.HINDI    -> teHiTranslator
        from == Language.TELUGU  && to == Language.ENGLISH  -> teEnTranslator
        else -> throw IllegalArgumentException("No translator for $from→$to")
    }

    /**
     * Maps translated target1 and target2 text back into the segment fields,
     * preserving source text in its own field.
     */
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
        var t1Idx = 0; var t2Idx = 0

        for ((i, seg) in cluster.withIndex()) {
            val ratio = (seg.endMs - seg.startMs).toDouble() / totalMs
            val isLast = i == cluster.lastIndex

            val t1Count = if (isLast) t1Words.size - t1Idx else (t1Words.size * ratio).toInt().coerceAtLeast(1)
            val t2Count = if (isLast) t2Words.size - t2Idx else (t2Words.size * ratio).toInt().coerceAtLeast(1)

            val t1Slice = t1Words.subList(t1Idx, min(t1Idx + t1Count, t1Words.size)).joinToString(" ")
            val t2Slice = t2Words.subList(t2Idx, min(t2Idx + t2Count, t2Words.size)).joinToString(" ")
            t1Idx += t1Count; t2Idx += t2Count

            // Populate all three language fields; source field stays as-is
            val (hindiText, englishText, teluguText) = when (sourceLanguage) {
                Language.HINDI -> Triple(seg.hindi, /* t1 */ t1Slice.ifBlank { seg.english }, /* t2 */ t2Slice.ifBlank { seg.telugu })
                Language.ENGLISH -> Triple(
                    if (target1 == Language.HINDI) t1Slice.ifBlank { seg.hindi } else t2Slice.ifBlank { seg.hindi },
                    seg.hindi, // original English stored in hindi field by Vosk
                    if (target1 == Language.TELUGU) t1Slice.ifBlank { seg.telugu } else t2Slice.ifBlank { seg.telugu }
                )
                Language.TELUGU -> Triple(
                    if (target1 == Language.HINDI) t1Slice.ifBlank { seg.hindi } else t2Slice.ifBlank { seg.hindi },
                    if (target1 == Language.ENGLISH) t1Slice.ifBlank { seg.english } else t2Slice.ifBlank { seg.english },
                    seg.hindi  // original Telugu stored in hindi field by Vosk
                )
            }

            mapped.add(seg.copy(
                hindi   = hindiText,
                english = englishText,
                telugu  = teluguText,
                detectedSourceLanguage = sourceLanguage.name
            ))
        }
        return mapped
    }

    private fun computeDivergenceScore(original: String, backTranslated: String): Float {
        val s1 = original.trim(); val s2 = backTranslated.trim()
        if (s1.isBlank() || s2.isBlank()) return 1.0f
        val dist = levenshteinDistance(s1, s2)
        return (dist.toFloat() / max(s1.length, s2.length).toFloat()).coerceIn(0f, 1f)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) for (j in 1..s2.length) {
            val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
            dp[i][j] = min(min(dp[i-1][j] + 1, dp[i][j-1] + 1), dp[i-1][j-1] + cost)
        }
        return dp[s1.length][s2.length]
    }

    private fun clusterSegmentsIntoFullSentences(
        segments: List<TranslationSegment>,
        gapThresholdMs: Long
    ): List<List<TranslationSegment>> {
        val clusters = mutableListOf<MutableList<TranslationSegment>>()
        var current = mutableListOf<TranslationSegment>()
        for (seg in segments) {
            if (current.isEmpty()) { current.add(seg); continue }
            val gap = seg.startMs - current.last().endMs
            val words = current.sumOf { it.hindi.trim().split("\\s+".toRegex()).count { w -> w.isNotBlank() } } +
                    seg.hindi.trim().split("\\s+".toRegex()).count { it.isNotBlank() }
            val isInterjection = seg.hindi.trim().split("\\s+".toRegex()).count { it.isNotBlank() } <= 2 && gap >= 600L
            if (gap <= gapThresholdMs && words <= 35 && !isInterjection) {
                current.add(seg)
            } else {
                clusters.add(current); current = mutableListOf(seg)
            }
        }
        if (current.isNotEmpty()) clusters.add(current)
        return clusters
    }

    private fun cleanSentence(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")
}
