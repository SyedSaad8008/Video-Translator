package com.example.videotranslator.translation

import android.util.Log
import com.example.videotranslator.model.TranslationSegment
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

private const val TAG = "TranslationManager"

/**
 * Stage 3 ML Kit Translation Manager.
 *
 * Translates complete sentence segments into English and Telugu with diagnostic logging.
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
     * Translates complete sentence segments directly with diagnostic logging.
     */
    suspend fun translate(segments: List<TranslationSegment>): List<TranslationSegment> {
        if (segments.isEmpty()) return emptyList()
        Log.d(TAG, "STAGE 3 - Translating ${segments.size} full sentence segments…")

        val result = mutableListOf<TranslationSegment>()

        for ((idx, seg) in segments.withIndex()) {
            val hindiSentence = seg.hindi.trim()
            if (hindiSentence.isBlank()) {
                result.add(seg)
                continue
            }

            val englishSentence = try {
                val raw = hiEnTranslator.translate(hindiSentence).await()
                cleanSentence(raw)
            } catch (e: Exception) {
                Log.w(TAG, "STAGE 3 - HI→EN translation failed for: $hindiSentence", e)
                ""
            }

            val teluguSentence = try {
                val raw = hiTeTranslator.translate(hindiSentence).await()
                cleanSentence(raw)
            } catch (e: Exception) {
                Log.w(TAG, "STAGE 3 - HI→TE translation failed for: $hindiSentence", e)
                ""
            }

            Log.d(TAG, "STAGE 3 - Segment [$idx] (${seg.startMs}ms - ${seg.endMs}ms):")
            Log.d(TAG, "   HI: \"$hindiSentence\"")
            Log.d(TAG, "   EN: \"$englishSentence\"")
            Log.d(TAG, "   TE: \"$teluguSentence\"")

            result.add(
                seg.copy(
                    english = englishSentence,
                    telugu  = teluguSentence
                )
            )
        }

        Log.d(TAG, "STAGE 3 - Direct sentence translation complete for ${result.size} segments ✓")
        return result
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
