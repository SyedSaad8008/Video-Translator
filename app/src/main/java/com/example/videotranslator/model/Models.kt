package com.example.videotranslator.model

import kotlinx.serialization.Serializable

/** Speaker gender classification. */
@Serializable
enum class Gender {
    MALE,
    FEMALE,
    UNKNOWN;

    companion object {
        fun fromString(value: String): Gender = when (value.uppercase()) {
            "MALE" -> MALE
            "FEMALE" -> FEMALE
            else -> UNKNOWN
        }
    }
}

/** Voice playback mode configured by user. */
@Serializable
enum class VoiceMode(val displayName: String) {
    GENDER_MATCHED("Gender Matched"),
    ORIGINAL_SPEAKER("Original Speaker"),
    FORCE_MALE("Male Voice"),
    FORCE_FEMALE("Female Voice");
}

/**
 * Speaker identity tracked locally during segmentation.
 */
@Serializable
data class Speaker(
    val id: String = "speaker_01",
    val label: String = "Speaker 1",
    val detectedGender: Gender = Gender.UNKNOWN,
    val genderConfidence: Float = 0.5f
)

/**
 * One recognised speech segment with start/end timestamps (ms),
 * speaker identification, voice classification, transcribed source text,
 * all language translations, pre-rendered audio paths, and speed ratios.
 */
@Serializable
data class TranslationSegment(
    val id: String = "",
    val startMs: Long,
    val endMs: Long,
    val speakerId: String = "speaker_01",
    val sourceLanguage: String = "hin_Deva",
    val targetLanguage: String = "eng_Latn",
    val sourceText: String = "",
    val translatedText: String = "",
    val hindi: String = "",
    val english: String = "",
    val telugu: String = "",
    val voiceGender: String = "male",
    val gender: Gender = Gender.MALE,
    val genderConfidence: Float = 1.0f,
    val audioPath: String = "",
    val englishAudioPath: String = "",
    val englishSpeedRatio: Float = 1.0f,
    val teluguAudioPath: String = "",
    val teluguSpeedRatio: Float = 1.0f,
    val hindiAudioPath: String = "",
    val hindiSpeedRatio: Float = 1.0f,
    val detectedSourceLanguage: String = "HINDI"
) {
    val startTimeSec: Double get() = startMs / 1000.0
    val endTimeSec: Double get() = endMs / 1000.0
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Supported on-device translation and speech languages.
 */
enum class Language(
    val displayName: String,
    val locale: String,
    val nllbCode: String
) {
    HINDI("हिंदी", "hi-IN", "hin_Deva"),
    ENGLISH("English", "en-US", "eng_Latn"),
    TELUGU("తెలుగు", "te-IN", "tel_Telu");

    fun displayNameWithOriginalTag(sourceLanguage: Language): String =
        if (this == sourceLanguage) "$displayName (Original)" else displayName

    companion object {
        fun fromNllbCode(code: String): Language = when (code) {
            "hin_Deva" -> HINDI
            "tel_Telu" -> TELUGU
            else -> ENGLISH
        }

        fun fromDisplayName(name: String): Language = when {
            name.contains("हिंदी", ignoreCase = true) || name.contains("Hindi", ignoreCase = true) -> HINDI
            name.contains("తెలుగు", ignoreCase = true) || name.contains("Telugu", ignoreCase = true) -> TELUGU
            else -> ENGLISH
        }
    }
}

/** Category of on-device AI models. */
enum class ModelCategory(val displayName: String) {
    SPEECH_RECOGNITION("Speech-to-Text (Whisper)"),
    TRANSLATION("Neural Translation (NLLB-200)"),
    VOICE_CLASSIFICATION("Voice & Gender Classifier"),
    TEXT_TO_SPEECH("Text-to-Speech Voices")
}

/** Installation and download status of an AI model. */
sealed interface ModelStatus {
    data object NotInstalled : ModelStatus
    data class Downloading(val progress: Float) : ModelStatus
    data object Installed : ModelStatus
    data class Error(val message: String) : ModelStatus
}

/** Metadata for a downloadable on-device AI model. */
data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val category: ModelCategory,
    val sizeBytes: Long,
    val downloadUrl: String,
    val fileName: String,
    val sha256: String = "",
    val isBundledInAssets: Boolean = false,
    val status: ModelStatus = ModelStatus.NotInstalled
) {
    val formattedSize: String
        get() {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1.0) "%.1f MB".format(mb) else "${sizeBytes / 1024} KB"
        }
}

/** Overall processing / playback state exposed by the ViewModel. */
sealed interface ProcessingState {
    data object Idle : ProcessingState
    data class Loading(
        val step: String,
        val progress: Float = -1f,
        val currentStage: Int = 1,
        val totalStages: Int = 7
    ) : ProcessingState
    data object Ready : ProcessingState
    data class Error(val message: String) : ProcessingState
}
