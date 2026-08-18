package com.example.videotranslator.tts

import com.example.videotranslator.model.Gender
import com.example.videotranslator.model.Language
import java.io.File

/**
 * Interface defining an on-device offline Text-to-Speech synthesis engine.
 */
interface TtsEngine {

    /** Name of the TTS engine (e.g. "Piper Neural TTS", "Android Local TTS"). */
    val engineName: String

    /** Check if engine and voices for target language are ready. */
    fun isLanguageAvailable(language: Language): Boolean

    /** Select voice corresponding to speaker gender and target language. */
    suspend fun selectVoiceForGender(language: Language, gender: Gender): Boolean

    /**
     * Synthesizes text to a WAV audio file on storage.
     * @return Duration in milliseconds of the rendered speech file.
     */
    suspend fun synthesizeToFile(
        text: String,
        outputFile: File,
        language: Language,
        gender: Gender
    ): Long

    /** Release resources when done. */
    fun shutdown()
}
