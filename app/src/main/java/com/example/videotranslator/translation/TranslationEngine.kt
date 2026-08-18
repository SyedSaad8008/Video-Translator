package com.example.videotranslator.translation

import com.example.videotranslator.model.Language
import com.example.videotranslator.model.TranslationSegment

/**
 * Interface defining an on-device Neural Machine Translation engine.
 * Must operate 100% locally with zero cloud API dependencies.
 */
interface TranslationEngine {

    /** Name / Identifier of the translation engine (e.g., "NLLB-200 INT8"). */
    val engineName: String

    /** Check whether required translation models are ready on-device. */
    fun isReady(): Boolean

    /** Ensure required model weights are initialized into memory. */
    suspend fun loadEngine(): Result<Unit>

    /** Unload model weights from memory to conserve RAM when idle. */
    fun unloadEngine()

    /**
     * Translates a single text sentence from source to target language.
     */
    suspend fun translate(
        text: String,
        sourceLanguage: Language,
        targetLanguage: Language
    ): String

    /**
     * Translates a sequence of timestamped translation segments with context windowing.
     */
    suspend fun translateSegments(
        segments: List<TranslationSegment>,
        sourceLanguage: Language,
        targetLanguage: Language
    ): List<TranslationSegment>
}
