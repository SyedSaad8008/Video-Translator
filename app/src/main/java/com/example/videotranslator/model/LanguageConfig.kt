package com.example.videotranslator.model

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/**
 * Central Language Configuration.
 * Unifies language codes across Speech-to-Text (Whisper/Vosk), Neural Translation (ML Kit / NLLB-200),
 * and Text-to-Speech engines.
 */
data class LanguageConfig(
    val language: Language,
    val displayName: String,
    val isoCode: String,
    val mlKitCode: String,
    val nllbCode: String,
    val whisperCode: String,
    val voskModelDirName: String,
    val defaultLocale: Locale,
    val maleVoiceTag: String,
    val femaleVoiceTag: String
) {
    companion object {
        val HINDI = LanguageConfig(
            language = Language.HINDI,
            displayName = "हिंदी",
            isoCode = "hi",
            mlKitCode = TranslateLanguage.HINDI,
            nllbCode = "hin_Deva",
            whisperCode = "hi",
            voskModelDirName = "vosk-model-small-hi-0.22",
            defaultLocale = Locale.forLanguageTag("hi-IN"),
            maleVoiceTag = "hi-in-male",
            femaleVoiceTag = "hi-in-female"
        )

        val ENGLISH = LanguageConfig(
            language = Language.ENGLISH,
            displayName = "English",
            isoCode = "en",
            mlKitCode = TranslateLanguage.ENGLISH,
            nllbCode = "eng_Latn",
            whisperCode = "en",
            voskModelDirName = "vosk-model-small-en-us-0.15",
            defaultLocale = Locale.US,
            maleVoiceTag = "en-us-male",
            femaleVoiceTag = "en-us-female"
        )

        val TELUGU = LanguageConfig(
            language = Language.TELUGU,
            displayName = "తెలుగు",
            isoCode = "te",
            mlKitCode = TranslateLanguage.TELUGU,
            nllbCode = "tel_Telu",
            whisperCode = "te",
            voskModelDirName = "vosk-model-small-te-0.42",
            defaultLocale = Locale.forLanguageTag("te-IN"),
            maleVoiceTag = "te-in-male",
            femaleVoiceTag = "te-in-female"
        )

        val ALL = listOf(HINDI, ENGLISH, TELUGU)

        fun forLanguage(language: Language): LanguageConfig = when (language) {
            Language.HINDI -> HINDI
            Language.ENGLISH -> ENGLISH
            Language.TELUGU -> TELUGU
        }

        fun fromIsoCode(code: String): LanguageConfig = when (code.lowercase().take(2)) {
            "hi" -> HINDI
            "te" -> TELUGU
            else -> ENGLISH
        }
    }
}
