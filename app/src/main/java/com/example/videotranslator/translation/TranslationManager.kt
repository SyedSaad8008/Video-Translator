package com.example.videotranslator.translation

import android.util.Log
import com.example.videotranslator.model.TranslationSegment
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

private const val TAG = "TranslationManager"

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

    suspend fun downloadModels() {
        Log.d(TAG, "STAGE 3 - Ensuring ML Kit translation models are downloaded…")
        hiEnTranslator.downloadModelIfNeeded().await()
        hiTeTranslator.downloadModelIfNeeded().await()
        Log.d(TAG, "STAGE 3 - ML Kit translation models ready ✓")
    }

    /**
     * Two-Tier Contextual Sentence Translation.
     */
    suspend fun translate(fineSegments: List<TranslationSegment>): List<TranslationSegment> {
        if (fineSegments.isEmpty()) return emptyList()
        Log.d(TAG, "STAGE 3 - Starting Two-Tier Contextual Translation over ${fineSegments.size} fine sync segments…")

        // 1. Group fine segments into coarse full-sentence clusters
        val sentenceClusters = clusterSegmentsIntoFullSentences(fineSegments)
        Log.d(TAG, "STAGE 3 - Clustered ${fineSegments.size} fine segments into ${sentenceClusters.size} coarse full sentences")

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
                Log.w(TAG, "STAGE 3 - Full sentence HI->EN translation failed for: \"$fullHindiText\"", e)
                ""
            }

            val fullTeluguText = try {
                val raw = hiTeTranslator.translate(fullHindiText).await()
                cleanSentence(raw)
            } catch (e: Exception) {
                Log.w(TAG, "STAGE 3 - Full sentence HI->TE translation failed for: \"$fullHindiText\"", e)
                ""
            }

            Log.d(TAG, "STAGE 3 - Coarse Cluster [$cIdx] (${cluster.first().startMs}ms -> ${cluster.last().endMs}ms):")
            Log.d(TAG, "   FULL HINDI:   \"$fullHindiText\"")
            Log.d(TAG, "   FULL ENGLISH: \"$fullEnglishText\"")
            Log.d(TAG, "   FULL TELUGU:  \"$fullTeluguText\"")

            // Map translated words proportionally back to fine sync segments
            val mappedFineSegments = mapTranslatedSentenceToFineSegments(
                cluster = cluster,
                fullEnglishText = fullEnglishText,
                fullTeluguText = fullTeluguText
            )
            result.addAll(mappedFineSegments)
        }

        Log.d(TAG, "STAGE 3 - Two-Tier Contextual Translation complete for ${result.size} sync segments ✓")
        return result
    }

    /**
     * Groups consecutive fine segments into full grammatical sentences.
     */
    private fun clusterSegmentsIntoFullSentences(segments: List<TranslationSegment>): List<List<TranslationSegment>> {
        val clusters = mutableListOf<List<TranslationSegment>>()
        val currentCluster = mutableListOf<TranslationSegment>()

        val MAX_GAP_MS = 1800L      // Max allowed pause between segments in a sentence
        val MAX_WORD_COUNT = 35     // Max words per coarse sentence
        val MAX_DURATION_MS = 16000L // Max duration per coarse sentence

        for (seg in segments) {
            if (currentCluster.isEmpty()) {
                currentCluster.add(seg)
                continue
            }

            val prevSeg = currentCluster.last()
            val gapMs = seg.startMs - prevSeg.endMs
            val totalWords = currentCluster.sumOf { countWords(it.hindi) } + countWords(seg.hindi)
            val totalDurationMs = seg.endMs - currentCluster.first().startMs

            val shouldSplit = gapMs > MAX_GAP_MS ||
                              totalWords > MAX_WORD_COUNT ||
                              totalDurationMs > MAX_DURATION_MS

            if (shouldSplit) {
                clusters.add(ArrayList(currentCluster))
                currentCluster.clear()
            }
            currentCluster.add(seg)
        }

        if (currentCluster.isNotEmpty()) {
            clusters.add(ArrayList(currentCluster))
        }
        return clusters
    }

    /**
     * Proportionally distributes translated sentence words across fine sync segments based on duration share.
     */
    private fun mapTranslatedSentenceToFineSegments(
        cluster: List<TranslationSegment>,
        fullEnglishText: String,
        fullTeluguText: String
    ): List<TranslationSegment> {
        if (cluster.size == 1) {
            return listOf(
                cluster[0].copy(
                    english = fullEnglishText,
                    telugu  = fullTeluguText
                )
            )
        }

        val totalClusterDuration = (cluster.last().endMs - cluster.first().startMs).toDouble().coerceAtLeast(1.0)
        val enWords = fullEnglishText.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val teWords = fullTeluguText.split("\\s+".toRegex()).filter { it.isNotBlank() }

        val mappedList = mutableListOf<TranslationSegment>()
        var enWordIdx = 0
        var teWordIdx = 0

        for ((i, seg) in cluster.withIndex()) {
            val segDuration = (seg.endMs - seg.startMs).toDouble().coerceAtLeast(1.0)
            val shareFraction = segDuration / totalClusterDuration

            val isLast = (i == cluster.size - 1)

            val enTake = if (isLast) (enWords.size - enWordIdx) else (enWords.size * shareFraction).toInt().coerceAtLeast(1)
            val teTake = if (isLast) (teWords.size - teWordIdx) else (teWords.size * shareFraction).toInt().coerceAtLeast(1)

            val segEnWords = enWords.subList(enWordIdx, (enWordIdx + enTake).coerceAtMost(enWords.size))
            val segTeWords = teWords.subList(teWordIdx, (teWordIdx + teTake).coerceAtMost(teWords.size))

            enWordIdx = (enWordIdx + enTake).coerceAtMost(enWords.size)
            teWordIdx = (teWordIdx + teTake).coerceAtMost(teWords.size)

            val segEnText = cleanSentence(segEnWords.joinToString(" "))
            val segTeText = cleanSentence(segTeWords.joinToString(" "))

            Log.d(TAG, "   Mapped Seg [$i] (${seg.startMs}ms - ${seg.endMs}ms):")
            Log.d(TAG, "      HI: \"${seg.hindi}\"")
            Log.d(TAG, "      EN: \"$segEnText\"")
            Log.d(TAG, "      TE: \"$segTeText\"")

            mappedList.add(
                seg.copy(
                    english = segEnText,
                    telugu  = segTeText
                )
            )
        }
        return mappedList
    }

    private fun countWords(text: String): Int {
        return text.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }.size
    }

    private fun cleanSentence(raw: String): String {
        var text = raw.trim().replace("\\s+".toRegex(), " ")
        if (text.isEmpty()) return ""

        if (text.isNotEmpty() && text[0].isLowerCase()) {
            text = text.replaceFirstChar { it.uppercase() }
        }
        return text
    }

    fun close() {
        hiEnTranslator.close()
        hiTeTranslator.close()
    }
}
