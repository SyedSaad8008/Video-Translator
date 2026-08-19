package com.example.videotranslator.ai.benchmark

import com.example.videotranslator.model.Language
import com.example.videotranslator.model.TranslationSegment

/**
 * Standardized Unified Interface for On-Device Local ASR Models.
 */
interface LocalASREngine {
    val modelName: String
    val modelFamily: String
    val quantization: String
    val estimatedModelSizeMb: Float

    suspend fun load(): Result<Unit>
    fun unload()

    suspend fun transcribe(
        pcm: ShortArray,
        language: Language,
        audioDurationSec: Double
    ): ASRResult
}

/**
 * Observable Quantitative Benchmark Metric Result for an ASR Model.
 */
data class ASRResult(
    val modelName: String,
    val modelFamily: String,
    val language: Language,
    val transcript: String,
    val confidence: Float,
    val wordCount: Int,
    val speechCoveragePercent: Float,
    val processingTimeMs: Long,
    val segments: List<TranslationSegment>,
    val qualityScore: Float,
    val notes: String = ""
)

/**
 * Full Multi-Model Comparison Container for one Audio Input.
 */
data class ModelBenchmarkComparison(
    val videoName: String,
    val audioDurationSec: Double,
    val selectedLanguage: Language,
    val results: List<ASRResult>,
    val bestModelName: String,
    val selectionRationale: String
)
