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

private const val TAG = "TranslationManager"
private const val MODEL_DOWNLOAD_TIMEOUT_MS = 30_000L // 30 second timeout

/**
 * Stage 3 ML Kit Two-Tier Contextual Translation Manager.
 *
 * Decouples full-sentence translation context from fine-grained audio timing sync segments:
 *  1. **Coarse Sentence Clustering**: Groups consecutive fine-grained sync segments into complete semantic sentences.
 *  2. **Neural Contextual Translation**: Translates the full coarse sentence in ML Kit with rich grammatical context.
 *  3. **Proportional Mapping**: Maps translated sentence words back onto fine audio sync segments proportionally by duration share.
 */
class TranslationManager {

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

    suspend fun downloadModels(): Result<Unit> {
        DiagnosticLogger.log(TAG, "STAGE 3 - Ensuring ML Kit translation models (HI->EN, HI->TE) are ready…")
        return try {
            withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                DiagnosticLogger.log(TAG, "Checking/Downloading Hindi->English NMT model…")
                hiEnTranslator.downloadModelIfNeeded().await()

                DiagnosticLogger.log(TAG, "Checking/Downloading Hindi->Telugu NMT model…")
                hiTeTranslator.downloadModelIfNeeded().await()
            }
            DiagnosticLogger.log(TAG, "STAGE 3 - ML Kit translation models downloaded & verified ✓")
            Result.success(Unit)
        } catch (e: Exception) {
            val errorMsg = "Failed to download ML Kit translation models. Internet connection required on initial app setup. (${e.localizedMessage})"
            DiagnosticLogger.log(TAG, "STAGE 3 ERROR: $errorMsg", e)
            Result.failure(IOException(errorMsg, e))
        }
    }

    /**
     * Two-Tier Contextual Sentence Translation.
     */
    suspend fun translate(fineSegments: List<TranslationSegment>): List<TranslationSegment> {
        if (fineSegments.isEmpty()) {
            DiagnosticLogger.log(TAG, "STAGE 3 - No fine segments to translate.")
            return emptyList()
        }
        val startTime = System.currentTimeMillis()
        DiagnosticLogger.log(TAG, "STAGE 3 - Starting Two-Tier Contextual Translation over ${fineSegments.size} fine sync segments…")

        // 1. Group fine segments into coarse full-sentence clusters
        val sentenceClusters = clusterSegmentsIntoFullSentences(fineSegments)
        DiagnosticLogger.log(TAG, "STAGE 3 - Clustered ${fineSegments.size} fine segments into ${sentenceClusters.size} coarse full sentences")

        val result = mutableListOf<TranslationSegment>()

        for ((cIdx, cluster) in sentenceClusters.withIndex()) {
            val fullHindiText = cluster.joinToString(" ") { it.hindi.trim() }.trim()
            if (fullHindiText.isBlank()) {
                result.addAll(cluster)
                continue
            }

            // Translate coarse full sentence for rich grammatical context
            val fullEnglishText = try {
                val raw = hiEnTranslator.translate(fullHindiText).await()
                cleanSentence(raw)
            } catch (e: Exception) {
                DiagnosticLogger.log(TAG, "STAGE 3 - Full sentence HI->EN translation failed for: \"$fullHindiText\"", e)
                ""
            }

            val fullTeluguText = try {
                val raw = hiTeTranslator.translate(fullHindiText).await()
                cleanSentence(raw)
            } catch (e: Exception) {
                DiagnosticLogger.log(TAG, "STAGE 3 - Full sentence HI->TE translation failed for: \"$fullHindiText\"", e)
                ""
            }

            DiagnosticLogger.log(TAG, "STAGE 3 - Coarse Cluster [$cIdx] (${cluster.first().startMs}ms -> ${cluster.last().endMs}ms):")
            DiagnosticLogger.log(TAG, "   FULL HINDI:   \"$fullHindiText\"")
            DiagnosticLogger.log(TAG, "   FULL ENGLISH: \"$fullEnglishText\"")
            DiagnosticLogger.log(TAG, "   FULL TELUGU:  \"$fullTeluguText\"")

            // Map translated words proportionally back to fine sync segments
            val mappedFineSegments = mapTranslatedSentenceToFineSegments(
                cluster = cluster,
                fullEnglishText = fullEnglishText,
                fullTeluguText = fullTeluguText
            )
            result.addAll(mappedFineSegments)
        }

        val duration = System.currentTimeMillis() - startTime
        DiagnosticLogger.log(TAG, "STAGE 3 - Two-Tier Contextual Translation complete for ${result.size} sync segments in ${duration}ms ✓")
        return result
    }

    /**
     * Groups consecutive fine segments into full grammatical sentences.
     * Rule: Merges segments if gap <= 1800ms and total words <= 35.
     */
    private fun clusterSegmentsIntoFullSentences(segments: List<TranslationSegment>): List<List<TranslationSegment>> {
        val clusters = mutableListOf<MutableList<TranslationSegment>>()
        var currentCluster = mutableListOf<TranslationSegment>()

        for (seg in segments) {
            if (currentCluster.isEmpty()) {
                currentCluster.add(seg)
            } else {
                val lastSeg = currentCluster.last()
                val gapMs = seg.startMs - lastSeg.endMs
                val totalWords = currentCluster.sumOf { countWords(it.hindi) } + countWords(seg.hindi)

                if (gapMs <= 1800L && totalWords <= 35) {
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

    /**
     * Maps translated full-sentence text back to individual fine sync segments
     * proportionally based on each segment's duration share in the coarse cluster.
     */
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
