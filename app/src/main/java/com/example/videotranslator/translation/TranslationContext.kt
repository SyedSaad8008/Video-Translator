package com.example.videotranslator.translation

import com.example.videotranslator.model.Language
import com.example.videotranslator.model.TranslationSegment

/**
 * Context-Aware Translation Window & Refinement.
 *
 * Prevents sentence-by-sentence translation disconnects by injecting
 * preceding and succeeding conversational context into the translation pipeline.
 */
class TranslationContext {

    data class ContextualUnit(
        val segment: TranslationSegment,
        val previousContext: String?,
        val currentText: String,
        val nextContext: String?
    )

    /**
     * Builds contextual units for each segment with a sliding context window.
     */
    fun buildContextWindows(segments: List<TranslationSegment>): List<ContextualUnit> {
        if (segments.isEmpty()) return emptyList()

        return segments.mapIndexed { idx, seg ->
            val prevText = if (idx > 0) segments[idx - 1].hindi.trim().takeIf { it.isNotBlank() } else null
            val nextText = if (idx < segments.lastIndex) segments[idx + 1].hindi.trim().takeIf { it.isNotBlank() } else null
            ContextualUnit(
                segment = seg,
                previousContext = prevText,
                currentText = seg.hindi.trim(),
                nextContext = nextText
            )
        }
    }

    /**
     * Refines and formats translated output text:
     *  - Capitalizes sentence start for Latin scripts (English)
     *  - Cleans extra whitespaces and punctuation artifacts
     *  - Ensures consistent end punctuation (? ! . ।)
     */
    fun refineTranslation(
        rawText: String,
        sourceLanguage: Language,
        targetLanguage: Language
    ): String {
        var text = rawText.trim().replace(Regex("\\s+"), " ")
        if (text.isBlank()) return ""

        // English sentence capitalization
        if (targetLanguage == Language.ENGLISH) {
            text = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            if (!text.endsWith(".") && !text.endsWith("?") && !text.endsWith("!")) {
                text += "."
            }
        } else if (targetLanguage == Language.HINDI) {
            if (!text.endsWith("।") && !text.endsWith("?") && !text.endsWith("!")) {
                text += "।"
            }
        }

        return text
    }
}
