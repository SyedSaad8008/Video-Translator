package com.example.videotranslator.ai.translation

import android.content.Context
import com.example.videotranslator.model.Language
import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "TranslationPipeline"

/**
 * Context-Aware Translation Pipeline Coordinator.
 * Translates transcription segments into English, Hindi, and Telugu using sliding context window.
 */
class TranslationPipeline(private val context: Context) {

    private val engine = NllbTranslationEngine(context)

    suspend fun load() = engine.load()
    fun close() = engine.close()

    /**
     * Translates a list of transcription segments across target languages.
     */
    suspend fun translateSegments(
        segments: List<TranslationSegment>,
        sourceLanguage: Language
    ): List<TranslationSegment> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyList()

        DiagnosticLogger.log(TAG, "STAGE 4 - Translating ${segments.size} segments from ${sourceLanguage.displayName} across all target tracks…")

        val results = mutableListOf<TranslationSegment>()

        for ((i, seg) in segments.withIndex()) {
            val prevText = if (i > 0) segments[i - 1].sourceText else ""
            val nextText = if (i < segments.size - 1) segments[i + 1].sourceText else ""

            val rawSource = seg.sourceText.ifBlank {
                when (sourceLanguage) {
                    Language.ENGLISH -> seg.english
                    Language.TELUGU  -> seg.telugu
                    Language.HINDI   -> seg.hindi
                }
            }
            val cleanedSource = cleanDisfluencies(rawSource)

            if (cleanedSource.isBlank()) {
                results.add(seg)
                continue
            }

            // 1. Translate to English
            val enText = if (sourceLanguage == Language.ENGLISH) cleanedSource
            else engine.translate(cleanedSource, sourceLanguage, Language.ENGLISH, prevText, nextText)

            // 2. Translate to Telugu
            val teText = if (sourceLanguage == Language.TELUGU) cleanedSource
            else engine.translate(cleanedSource, sourceLanguage, Language.TELUGU, prevText, nextText)

            // 3. Translate to Hindi
            val hiText = if (sourceLanguage == Language.HINDI) cleanedSource
            else engine.translate(cleanedSource, sourceLanguage, Language.HINDI, prevText, nextText)

            results.add(
                seg.copy(
                    sourceText = cleanedSource,
                    english = enText,
                    telugu = teText,
                    hindi = hiText
                )
            )
        }

        DiagnosticLogger.log(TAG, "STAGE 4 - Contextual translation complete for ${results.size} segments ✓")
        results
    }

    private fun cleanDisfluencies(text: String): String {
        return text
            .replace(Regex("(?i)\\b(um|uh|erm|ah|like|you know|matlab|yani|ante)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
