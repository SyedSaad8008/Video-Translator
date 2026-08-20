package com.example.videotranslator.ai.translation

import com.example.videotranslator.model.Language
import java.util.regex.Pattern

/**
 * Enterprise Multi-Signal Proper-Noun & Named Entity Protection Engine.
 *
 * Prevents semantic mistranslation of names, locations, organizations, and brands.
 * Pipeline:
 *   1. Source Text -> Identify Entities (NER + Contextual Patterns + Dictionary)
 *   2. Mask Entities with immutable placeholders (__ENTITY_1__, __ENTITY_2__, etc.)
 *   3. Sentence-level Neural Machine Translation (NLLB / ML Kit / IndicTrans2)
 *   4. Restore Placeholders with target-language verified transliterations (e.g. Saad <-> साद <-> సాద్).
 */
class EntityProtectionEngine {

    data class KnownEntity(
        val type: String,
        val aliases: List<String>,
        val english: String,
        val hindi: String,
        val telugu: String
    )

    private val knownEntities = listOf(
        // Person Names
        KnownEntity("PERSON", listOf("saad", "sa'ad", "syed saad", "साद", "सैयद साद", "సాద్", "సయ్యద్ సాద్"), "Saad", "साद", "సాద్"),
        KnownEntity("PERSON", listOf("kcr", "kcr sahab", "k.c.r", "k c r", "केसीआर", "केसीआर साहब", "के सी आर", "కేసీఆర్", "కేసీఆర్ సాహెబ్"), "KCR Sahab", "केसीआर साहब", "కేసీఆర్ సాహెబ్"),
        
        // Locations
        KnownEntity("LOCATION", listOf("hyderabad", "haidarabad", "हैदराबाद", "హైదరాబాద్"), "Hyderabad", "हैदराबाद", "హైదరాబాద్"),
        KnownEntity("LOCATION", listOf("telangana", "तेलंगाना", "తెలంగాణ"), "Telangana", "तेलंगाना", "తెలంగాణ"),
        KnownEntity("LOCATION", listOf("india", "bharat", "हिन्दुस्तान", "भारत", "భారతదేశం", "భారత్"), "India", "भारत", "భారత్"),
        
        // Organizations & Institutions
        KnownEntity("ORGANIZATION", listOf("minority engineering college", "माइनॉरिटी इंजीनियरिंग कॉलेज", "మైనారిటీ ఇంజనీరింగ్ కళాశాల"), "Minority Engineering College", "माइनॉरिटी इंजीनियरिंग कॉलेज", "మైనారిటీ ఇంజనీరింగ్ కళాశాల"),
        KnownEntity("ORGANIZATION", listOf("urdu library", "उर्दू लाइब्रेरी", "उर्दू लायब्रेरी", "ఉర్దూ లైబ్రరీ"), "Urdu Library", "उर्दू लाइब्रेरी", "ఉర్దూ లైబ్రరీ"),
        KnownEntity("ORGANIZATION", listOf("computer training center", "कंप्यूटर ट्रेनिंग सेंटर", "కంప్యూటర్ శిక్షణా కేంద్రం"), "Computer Training Center", "कंप्यूटर ट्रेनिंग सेंटर", "కంప్యూటర్ శిక్షణా కేంద్రం")
    )

    data class MaskedResult(
        val maskedText: String,
        val entityMap: Map<String, TransliterationSet>
    )

    data class TransliterationSet(
        val english: String,
        val hindi: String,
        val telugu: String
    ) {
        fun forLanguage(lang: Language): String = when (lang) {
            Language.ENGLISH -> english
            Language.HINDI   -> hindi
            Language.TELUGU  -> telugu
        }
    }

    /**
     * Masks detected entities in the source text before neural machine translation.
     */
    fun maskEntities(text: String, sourceLang: Language): MaskedResult {
        var masked = text
        val entityMap = mutableMapOf<String, TransliterationSet>()
        var placeholderIndex = 1

        // 1. Match longest known aliases first
        val sortedEntities = knownEntities.sortedByDescending { it.aliases.maxOf { a -> a.length } }
        for (ent in sortedEntities) {
            for (alias in ent.aliases.sortedByDescending { it.length }) {
                val pattern = Pattern.compile(
                    "(^|[\\s,.\u2010!?:;\"'\\(\\)\\[\\]])" + Pattern.quote(alias) + "($|[\\s,.\u2010!?:;\"'\\(\\)\\[\\]])",
                    Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                )
                val matcher = pattern.matcher(masked)
                if (matcher.find()) {
                    val placeholder = "__ENTITY_${placeholderIndex}__"
                    entityMap[placeholder] = TransliterationSet(ent.english, ent.hindi, ent.telugu)
                    masked = matcher.replaceFirst("$1$placeholder$2")
                    placeholderIndex++
                    break
                }
            }
        }

        // 2. Contextual heuristic for spoken introductions ("my name is X", "I am X", "నా పేరు X", "मेरा नाम X")
        val introPatterns = listOf(
            Pattern.compile("(?i)\\b(my name is|i am|i'm)\\s+([A-Z][a-z]+)\\b"),
            Pattern.compile("(?i)(నా పేరు|నేను)\\s+([^\\s,.\u2010!?:;\"'\\(\\)\\[\\]]+)"),
            Pattern.compile("(?i)(मेरा नाम|मैं)\\s+([^\\s,.\u2010!?:;\"'\\(\\)\\[\\]]+)\\s+(हूँ|है)")
        )

        for (pat in introPatterns) {
            val matcher = pat.matcher(masked)
            if (matcher.find()) {
                val groupIdx = if (pat.pattern().contains("मेरा नाम")) 2 else 2
                val matchedWord = matcher.group(groupIdx) ?: ""
                if (matchedWord.isNotBlank() && !matchedWord.startsWith("__ENTITY_") && !isCommonStopword(matchedWord)) {
                    val placeholder = "__ENTITY_${placeholderIndex}__"
                    entityMap[placeholder] = TransliterationSet(
                        english = matchedWord.replaceFirstChar { it.uppercase() },
                        hindi = matchedWord,
                        telugu = matchedWord
                    )
                    val start = matcher.start(groupIdx)
                    val end = matcher.end(groupIdx)
                    masked = masked.substring(0, start) + placeholder + masked.substring(end)
                    placeholderIndex++
                }
            }
        }

        return MaskedResult(masked, entityMap)
    }

    /**
     * Restores masked placeholders in the translated sentence using target language transliteration.
     */
    fun restoreEntities(translatedText: String, entityMap: Map<String, TransliterationSet>, targetLang: Language): String {
        var restored = translatedText
        for ((placeholder, translits) in entityMap) {
            val targetValue = translits.forLanguage(targetLang)
            val num = placeholder.filter { it.isDigit() }.ifBlank { "1" }

            val patterns = listOf(
                Pattern.quote(placeholder),
                "__\\s*ENTITY_\\s*$num\\s*__",
                "<\\s*ENTITY_\\s*$num\\s*>",
                "\\[\\s*ENTITY_\\s*$num\\s*\\]",
                "ENTITY_$num"
            )

            for (p in patterns) {
                restored = restored.replace(Regex(p, RegexOption.IGNORE_CASE), targetValue)
            }
        }
        return restored
    }

    private fun isCommonStopword(word: String): Boolean {
        val lower = word.lowercase()
        return lower in setOf(
            "here", "there", "going", "very", "also", "and", "the", "a", "to", "in", "for",
            "he", "she", "it", "we", "they", "is", "am", "are", "was", "were", "been"
        )
    }
}
