package com.example.videotranslator.model

import kotlinx.serialization.Serializable

/**
 * One recognised speech segment with start/end timestamps (ms) and the
 * original Hindi text plus its translations.
 */
@Serializable
data class TranslationSegment(
    val startMs: Long,
    val endMs: Long,
    val hindi: String,
    val english: String = "",
    val telugu: String = ""
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
