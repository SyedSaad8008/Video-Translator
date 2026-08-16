package com.example.videotranslator.model

import kotlinx.serialization.Serializable

/** Speaker gender classification. */
enum class Gender {
    MALE,
    FEMALE,
    UNKNOWN;
}

/**
 * One recognised speech segment with start/end timestamps (ms),
 * transcribed source text, all three language translations,
 * per-segment speaker gender, pre-rendered audio file paths,
 * and detected source language.
 */
@Serializable
data class TranslationSegment(
    val startMs: Long,
    val endMs: Long,
    val hindi: String,
    val english: String = "",
    val telugu: String = "",
    val englishAudioPath: String = "",
    val englishSpeedRatio: Float = 1.0f,
    val teluguAudioPath: String = "",
    val teluguSpeedRatio: Float = 1.0f,
    val hindiAudioPath: String = "",
    val hindiSpeedRatio: Float = 1.0f,
    val gender: Gender = Gender.MALE,
    val detectedSourceLanguage: String = "HINDI"  // "HINDI", "TELUGU", "ENGLISH"
)

/** Target language for playback. */
enum class Language(val displayName: String, val locale: String) {
    HINDI("हिंदी", "hi-IN"),
    ENGLISH("English", "en-US"),
    TELUGU("తెలుగు", "te-IN");

    fun displayNameWithOriginalTag(sourceLanguage: Language): String =
        if (this == sourceLanguage) "$displayName (Original)" else displayName
}

/** Overall processing / playback state exposed by the ViewModel. */
sealed interface ProcessingState {
    data object Idle : ProcessingState
    data class Loading(val step: String, val progress: Float = -1f) : ProcessingState
    data object Ready : ProcessingState
    data class Error(val message: String) : ProcessingState
}
