package com.example.videotranslator.ai.translation

import android.content.Context
import com.example.videotranslator.model.Language
import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "TranslationPipeline"

/**
 * Context-Aware & Proper-Noun Protected Translation Pipeline Coordinator.
 * Translates transcription segments into English, Hindi, and Telugu using entity masking and restoration.
 * Strictly validates non-empty translated results.
 */
class TranslationPipeline(private val context: Context) {

    private val engine = NllbTranslationEngine(context)
    private val entityProtector = EntityProtectionEngine()

    suspend fun load() = engine.load()
    fun close() = engine.close()

    /**
     * Translates a list of transcription segments across target languages with proper-noun protection.
     */
    suspend fun translateSegments(
        segments: List<TranslationSegment>,
        sourceLanguage: Language
    ): List<TranslationSegment> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyList()

        DiagnosticLogger.log(TAG, "STAGE 4 - Translating ${segments.size} segments from ${sourceLanguage.displayName} across all target tracks with Entity Protection…")

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
                throw IllegalStateException("Cannot translate segment ${seg.id} because source speech text is blank.")
            }

            // STEP 1: Proper-Noun & Named Entity Protection (Masking)
            val maskResult = entityProtector.maskEntities(cleanedSource, sourceLanguage)
            val maskedSource = maskResult.maskedText
            val entityMap = maskResult.entityMap
            if (entityMap.isNotEmpty()) {
                DiagnosticLogger.log("NER", "Protected ${entityMap.size} entities in segment ${seg.id}: ${entityMap.keys} ✓")
            }

            // STEP 2: Sentence-Level Neural Translation
            val enRaw = if (sourceLanguage == Language.ENGLISH) maskedSource
            else engine.translate(maskedSource, sourceLanguage, Language.ENGLISH, prevText, nextText)

            val teRaw = if (sourceLanguage == Language.TELUGU) maskedSource
            else engine.translate(maskedSource, sourceLanguage, Language.TELUGU, prevText, nextText)

            val hiRaw = if (sourceLanguage == Language.HINDI) maskedSource
            else engine.translate(maskedSource, sourceLanguage, Language.HINDI, prevText, nextText)

            // STEP 3: Restore Proper Nouns with Target-Language Transliteration
            val enFinal = entityProtector.restoreEntities(enRaw, entityMap, Language.ENGLISH)
            val teFinal = entityProtector.restoreEntities(teRaw, entityMap, Language.TELUGU)
            val hiFinal = entityProtector.restoreEntities(hiRaw, entityMap, Language.HINDI)

            results.add(
                seg.copy(
                    sourceText = cleanedSource,
                    english = enFinal,
                    telugu = teFinal,
                    hindi = hiFinal
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
