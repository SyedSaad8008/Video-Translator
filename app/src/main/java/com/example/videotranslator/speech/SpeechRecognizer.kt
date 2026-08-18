package com.example.videotranslator.speech

import com.example.videotranslator.model.Language
import com.example.videotranslator.model.TranslationSegment

/**
 * Interface defining an on-device Speech-to-Text recognizer.
 * Must execute 100% locally without cloud transmission.
 */
interface SpeechRecognizer {

    /** Name of the STT engine. */
    val engineName: String

    /** Check if models are loaded. */
    fun isReady(): Boolean

    /** Pre-warm or load model weights. */
    suspend fun loadModel(): Result<Unit>

    /** Unload model to conserve RAM. */
    fun close()

    /**
     * Probes audio sample to identify the spoken source language.
     */
    suspend fun probeLanguage(pcm: ShortArray): Language

    /**
     * Transcribes PCM audio into timestamped speech segments.
     */
    suspend fun recognize(
        pcm: ShortArray,
        sourceLanguage: Language
    ): List<TranslationSegment>
}
