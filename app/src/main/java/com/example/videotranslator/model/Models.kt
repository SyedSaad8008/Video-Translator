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
 * original Hindi text, translations, per-segment speaker gender,
 * and pre-rendered audio file metadata.
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
    val gender: Gender = Gender.MALE
)

/** Target language for playback. */
enum class Language(val displayName: String, val locale: String) {
    HINDI("हिंदी (Original)", "hi-IN"),
    ENGLISH("English", "en-US"),
    TELUGU("తెలుగు", "te-IN");
}

/** Overall processing / playback state exposed by the ViewModel. */
sealed interface ProcessingState {
    data object Idle : ProcessingState
    data class Loading(val step: String, val progress: Float = -1f) : ProcessingState
    data object Ready : ProcessingState
    data class Error(val message: String) : ProcessingState
}
