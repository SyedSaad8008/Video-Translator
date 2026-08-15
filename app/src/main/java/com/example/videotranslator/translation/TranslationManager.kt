package com.example.videotranslator.translation

import android.util.Log
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
private const val MODEL_DOWNLOAD_TIMEOUT_MS = 30_000L // 30 second timeout
private const val DIVERGENCE_RETRANSLATE_THRESHOLD = 0.45f // Divergence threshold to trigger boundary re-clustering

/**
 * Stage 3 ML Kit Two-Tier Contextual Translation Manager with Self-Verification.
 *
 * Capabilities:
 *  1. **Disfluency Cleanup**: Removes stutters, hesitation fillers, and false-starts before NMT while protecting expressive interjections.
 *  2. **Coarse Sentence Clustering**: Groups consecutive fine sync segments into complete sentences.
 *  3. **Neural Contextual Translation**: Translates coarse Hindi to English & Telugu via ML Kit.
 *  4. **Back-Translation Self-Verification**: Translates English -> Hindi to compute a round-trip divergence score.
 *  5. **Adaptive Boundary Re-Clustering**: For clusters exceeding divergence threshold (low confidence), attempts alternative sentence boundary grouping to improve round-trip fidelity.
 *  6. **Proportional Duration Mapping**: Maps translated sentence words back onto fine audio sync segments without disrupting lip-sync timing.
 */
class TranslationManager {

    private val disfluencyCleaner = DisfluencyCleaner()

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

    suspend fun downloadModels(): Result<Unit> {
        DiagnosticLogger.log(TAG, "STAGE 3 - Ensuring ML Kit NMT models (HI->EN, HI->TE, EN->HI) are ready…")
        return try {
            withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                DiagnosticLogger.log(TAG, "Checking/Downloading Hindi->English model…")
                hiEnTranslator.downloadModelIfNeeded().await()

                DiagnosticLogger.log(TAG, "Checking/Downloading Hindi->Telugu model…")
                hiTeTranslator.downloadModelIfNeeded().await()

                DiagnosticLogger.log(TAG, "Checking/Downloading English->Hindi verification model…")
                enHiTranslator.downloadModelIfNeeded().await()
            }
            DiagnosticLogger.log(TAG, "STAGE 3 - ML Kit translation & verification models verified ✓")
            Result.success(Unit)
        } catch (e: Exception) {
            val errorMsg = "Failed to download ML Kit translation models. (${e.localizedMessage})"
            DiagnosticLogger.log(TAG, "STAGE 3 ERROR: $errorMsg", e)
            Result.failure(IOException(errorMsg, e))
        }
    }

    suspend fun translate(fineSegments: List<TranslationSegment>): List<TranslationSegment> {
        if (fineSegments.isEmpty()) {
            DiagnosticLogger.log(TAG, "STAGE 3 - No fine segments to translate.")
            return emptyList()
        }
        val startTime = System.currentTimeMillis()
        DiagnosticLogger.log(TAG, "STAGE 3 - Starting Translation & Back-Translation Verification over ${fineSegments.size} sync segments…")

        // 1. Initial Coarse Sentence Clustering
        val initialClusters = clusterSegmentsIntoFullSentences(fineSegments, gapThresholdMs = 1800L)
        DiagnosticLogger.log(TAG, "STAGE 3 - Formed ${initialClusters.size} initial coarse sentence clusters")

        val result = mutableListOf<TranslationSegment>()

        for ((cIdx, cluster) in initialClusters.withIndex()) {
            val rawHindiText = cluster.joinToString(" ") { it.hindi.trim() }.trim()
            if (rawHindiText.isBlank()) {
                result.addAll(cluster)
                continue
            }

            // 2. Disfluency cleanup pass
            val cleanupRes = disfluencyCleaner.clean(rawHindiText)
            val cleanedHindi = cleanupRes.cleanedText

            // 3. Primary translation + Back-translation verification
            var translationCandidate = processClusterTranslation(cleanedHindi)
            var bestDivergence = translationCandidate.divergenceScore

            DiagnosticLogger.log(TAG, "STAGE 3 - Cluster [$cIdx] (${cluster.first().startMs}ms -> ${cluster.last().endMs}ms):\n" +
                    "   RAW HINDI:     \"$rawHindiText\"\n" +
                    "   CLEANED HINDI: \"$cleanedHindi\"\n" +
                    "   ENGLISH:       \"${translationCandidate.englishText}\"\n" +
                    "   TELUGU:        \"${translationCandidate.teluguText}\"\n" +
                    "   ROUND-TRIP HI: \"${translationCandidate.backTranslatedHindi}\"\n" +
                    "   DIVERGENCE:    ${"%.3f".format(bestDivergence)} ${if (bestDivergence > DIVERGENCE_RETRANSLATE_THRESHOLD) "(HIGH DIVERGENCE ⚠️)" else "✓"}")

            // 4. Adaptive boundary re-clustering if divergence is high & cluster has multiple segments
            if (bestDivergence > DIVERGENCE_RETRANSLATE_THRESHOLD && cluster.size > 1) {
                DiagnosticLogger.log(TAG, "STAGE 3 - High divergence (${"%.3f".format(bestDivergence)} > $DIVERGENCE_RETRANSLATE_THRESHOLD) → Attempting adaptive boundary re-clustering…")

                // Split cluster at midpoint to test smaller sentence boundaries
                val midIndex = cluster.size / 2
                val subCluster1 = cluster.subList(0, midIndex)
                val subCluster2 = cluster.subList(midIndex, cluster.size)

                val alt1Cleaned = disfluencyCleaner.clean(subCluster1.joinToString(" ") { it.hindi.trim() }).cleanedText
                val alt2Cleaned = disfluencyCleaner.clean(subCluster2.joinToString(" ") { it.hindi.trim() }).cleanedText

                val altTrans1 = processClusterTranslation(alt1Cleaned)
                val altTrans2 = processClusterTranslation(alt2Cleaned)

                val combinedAltDivergence = (altTrans1.divergenceScore + altTrans2.divergenceScore) / 2.0f

                DiagnosticLogger.log(TAG, "STAGE 3 - Adaptive Boundary Re-Clustering Candidate:\n" +
                        "   Sub-Cluster 1 Divergence: ${"%.3f".format(altTrans1.divergenceScore)}\n" +
                        "   Sub-Cluster 2 Divergence: ${"%.3f".format(altTrans2.divergenceScore)}\n" +
                        "   Average Combined: ${"%.3f".format(combinedAltDivergence)}")

                if (combinedAltDivergence < bestDivergence) {
                    DiagnosticLogger.log(TAG, "⚡ BOUNDARY RE-CLUSTERING ACCEPTED: Reduced divergence from ${"%.3f".format(bestDivergence)} → ${"%.3f".format(combinedAltDivergence)}")
                    
                    val mapped1 = mapTranslatedSentenceToFineSegments(subCluster1, altTrans1.englishText, altTrans1.teluguText)
                    val mapped2 = mapTranslatedSentenceToFineSegments(subCluster2, altTrans2.englishText, altTrans2.teluguText)
                    result.addAll(mapped1)
                    result.addAll(mapped2)
                    continue
                } else {
                    DiagnosticLogger.log(TAG, "Boundary re-clustering did not lower divergence. Retaining original cluster translation.")
                }
            }

            // 5. Map translated sentence words proportionally onto fine sync segments
            val mappedFineSegments = mapTranslatedSentenceToFineSegments(
                cluster = cluster,
                fullEnglishText = translationCandidate.englishText,
                fullTeluguText = translationCandidate.teluguText
            )
            result.addAll(mappedFineSegments)
        }

        val duration = System.currentTimeMillis() - startTime
        DiagnosticLogger.log(TAG, "STAGE 3 - Contextual Translation & Verification complete for ${result.size} segments in ${duration}ms ✓")
        return result
    }

    private data class ClusterTranslationResult(
        val englishText: String,
        val teluguText: String,
        val backTranslatedHindi: String,
        val divergenceScore: Float
    )

    private suspend fun processClusterTranslation(cleanedHindi: String): ClusterTranslationResult {
        val englishText = try {
            val raw = hiEnTranslator.translate(cleanedHindi).await()
            cleanSentence(raw)
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "STAGE 3 - HI->EN translation failed for: \"$cleanedHindi\"", e)
            ""
        }

        val teluguText = try {
            val raw = hiTeTranslator.translate(cleanedHindi).await()
            cleanSentence(raw)
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "STAGE 3 - HI->TE translation failed for: \"$cleanedHindi\"", e)
            ""
        }

        // Back-translation self-verification (EN -> HI)
        val backTranslatedHindi = if (englishText.isNotBlank()) {
            try {
                val raw = enHiTranslator.translate(englishText).await()
                cleanSentence(raw)
            } catch (e: Exception) {
                ""
            }
        } else ""

        val divergenceScore = computeDivergenceScore(cleanedHindi, backTranslatedHindi)

        return ClusterTranslationResult(
            englishText = englishText,
            teluguText = teluguText,
            backTranslatedHindi = backTranslatedHindi,
            divergenceScore = divergenceScore
        )
    }

    /**
     * Calculates normalized string edit distance divergence score (0.0 = identical, 1.0 = completely divergent).
     */
    private fun computeDivergenceScore(original: String, backTranslated: String): Float {
        val s1 = original.trim()
        val s2 = backTranslated.trim()
        if (s1.isBlank() || s2.isBlank()) return 1.0f

        val dist = levenshteinDistance(s1, s2)
        val maxLen = max(s1.length, s2.length)
        return (dist.toFloat() / maxLen.toFloat()).coerceIn(0.0f, 1.0f)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    private fun clusterSegmentsIntoFullSentences(
        segments: List<TranslationSegment>,
        gapThresholdMs: Long
    ): List<List<TranslationSegment>> {
        val clusters = mutableListOf<MutableList<TranslationSegment>>()
        var currentCluster = mutableListOf<TranslationSegment>()

        for (seg in segments) {
            if (currentCluster.isEmpty()) {
                currentCluster.add(seg)
            } else {
                val lastSeg = currentCluster.last()
                val gapMs = seg.startMs - lastSeg.endMs
                val segWordCount = countWords(seg.hindi)
                val totalWords = currentCluster.sumOf { countWords(it.hindi) } + segWordCount

                val isStandaloneInterjection = segWordCount <= 2 && gapMs >= 600L

                if (gapMs <= gapThresholdMs && totalWords <= 35 && !isStandaloneInterjection) {
                    currentCluster.add(seg)
                } else {
                    clusters.add(currentCluster)
                    currentCluster = mutableListOf(seg)
                }
            }
        }
        if (currentCluster.isNotEmpty()) {
            clusters.add(currentCluster)
        }
        return clusters
    }

    private fun mapTranslatedSentenceToFineSegments(
        cluster: List<TranslationSegment>,
        fullEnglishText: String,
        fullTeluguText: String
    ): List<TranslationSegment> {
        if (cluster.size == 1) {
            return listOf(
                cluster[0].copy(
                    english = fullEnglishText.ifBlank { cluster[0].english },
                    telugu  = fullTeluguText.ifBlank { cluster[0].telugu }
                )
            )
        }

        val totalClusterDurationMs = (cluster.last().endMs - cluster.first().startMs).coerceAtLeast(1L)
        val englishWords = fullEnglishText.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val teluguWords  = fullTeluguText.split("\\s+".toRegex()).filter { it.isNotBlank() }

        val mappedSegments = mutableListOf<TranslationSegment>()
        var enWordIdx = 0
        var teWordIdx = 0

        for ((idx, seg) in cluster.withIndex()) {
            val segDurationMs = (seg.endMs - seg.startMs).coerceAtLeast(1L)
            val durationRatio = segDurationMs.toDouble() / totalClusterDurationMs

            val enCount = if (idx == cluster.lastIndex) {
                englishWords.size - enWordIdx
            } else {
                (englishWords.size * durationRatio).toInt().coerceAtLeast(1)
            }

            val teCount = if (idx == cluster.lastIndex) {
                teluguWords.size - teWordIdx
            } else {
                (teluguWords.size * durationRatio).toInt().coerceAtLeast(1)
            }

            val segEnWords = mutableListOf<String>()
            var takeEn = 0
            while (enWordIdx < englishWords.size && takeEn < enCount) {
                segEnWords.add(englishWords[enWordIdx])
                enWordIdx++
                takeEn++
            }

            val segTeWords = mutableListOf<String>()
            var takeTe = 0
            while (teWordIdx < teluguWords.size && takeTe < teCount) {
                segTeWords.add(teluguWords[teWordIdx])
                teWordIdx++
                takeTe++
            }

            mappedSegments.add(
                seg.copy(
                    english = segEnWords.joinToString(" ").ifBlank { seg.english },
                    telugu  = segTeWords.joinToString(" ").ifBlank { seg.telugu }
                )
            )
        }

        return mappedSegments
    }

    private fun countWords(text: String): Int =
        text.trim().split("\\s+".toRegex()).count { it.isNotBlank() }

    private fun cleanSentence(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ")
}
