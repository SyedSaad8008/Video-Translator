package com.example.videotranslator.ai.benchmark

import android.content.Context
import android.util.Log
import com.example.videotranslator.model.Language
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "AsrBenchmarkRunner"

/**
 * On-Device Local ASR Model Benchmark Suite.
 * Benchmarks 3 distinct ASR engines (Kaldi/Vosk, AI4Bharat Indic-Conformer, OpenAI Whisper)
 * against the EXACT SAME audio file, exports metrics to JSON, and logs formatted comparative reports.
 */
class AsrBenchmarkRunner(private val context: Context) {

    private val kaldiVosk = KaldiVoskEngine(context)
    private val indicConformer = IndicConformerEngine(context)
    private val whisperCpp = WhisperCppEngine(context)

    suspend fun runBenchmark(
        pcm: ShortArray,
        language: Language,
        videoName: String = "test_audio.mp4"
    ): ModelBenchmarkComparison = withContext(Dispatchers.IO) {
        val durationSec = pcm.size / 16000.0
        DiagnosticLogger.log(TAG, "══════════ STARTING LOCAL ASR MODEL BENCHMARK ══════════")
        DiagnosticLogger.log(TAG, "Input: $videoName (${"%.1f".format(durationSec)}s audio, Language: ${language.displayName})")

        val results = mutableListOf<ASRResult>()

        // 1. Run Model A: Kaldi/Vosk
        try {
            kaldiVosk.load()
            val resA = kaldiVosk.transcribe(pcm, language, durationSec)
            results.add(resA)
            kaldiVosk.unload()
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "Model A benchmark error: ${e.message}")
        }

        // 2. Run Model B: AI4Bharat Indic-Conformer
        try {
            indicConformer.load()
            val resB = indicConformer.transcribe(pcm, language, durationSec)
            results.add(resB)
            indicConformer.unload()
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "Model B benchmark error: ${e.message}")
        }

        // 3. Run Model C: OpenAI Whisper
        try {
            whisperCpp.load()
            val resC = whisperCpp.transcribe(pcm, language, durationSec)
            results.add(resC)
            whisperCpp.unload()
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "Model C benchmark error: ${e.message}")
        }

        // Determine best model by quality score
        val best = results.maxByOrNull { it.qualityScore }
        val bestName = best?.modelName ?: "Kaldi/Vosk"
        val rationale = if (best != null) {
            "Highest speech coverage (${"%.1f".format(best.speechCoveragePercent)}%), ${best.wordCount} words decoded in ${best.processingTimeMs}ms with score ${"%.1f".format(best.qualityScore)}/100."
        } else "Default"

        val comparison = ModelBenchmarkComparison(
            videoName = videoName,
            audioDurationSec = durationSec,
            selectedLanguage = language,
            results = results,
            bestModelName = bestName,
            selectionRationale = rationale
        )

        // Export JSON file: asr_benchmark_results.json
        exportJson(comparison)

        // Log Formatted Report
        logComparisonReport(comparison)

        comparison
    }

    private fun exportJson(comparison: ModelBenchmarkComparison) {
        try {
            val json = JSONObject()
            json.put("video", comparison.videoName)
            json.put("audio_duration_sec", comparison.audioDurationSec)
            json.put("selected_language", comparison.selectedLanguage.name)
            json.put("best_model", comparison.bestModelName)
            json.put("selection_rationale", comparison.selectionRationale)

            val modelsArray = JSONArray()
            for (res in comparison.results) {
                val mObj = JSONObject()
                mObj.put("model_name", res.modelName)
                mObj.put("model_family", res.modelFamily)
                mObj.put("language", res.language.name)
                mObj.put("transcript", res.transcript)
                mObj.put("confidence", res.confidence)
                mObj.put("word_count", res.wordCount)
                mObj.put("speech_coverage_percent", res.speechCoveragePercent)
                mObj.put("processing_time_ms", res.processingTimeMs)
                mObj.put("quality_score", res.qualityScore)
                mObj.put("notes", res.notes)
                modelsArray.put(mObj)
            }
            json.put("models", modelsArray)

            val outputFile = File(context.filesDir, "asr_benchmark_results.json")
            outputFile.writeText(json.toString(2))
            DiagnosticLogger.log(TAG, "Exported benchmark JSON: ${outputFile.absolutePath} (${outputFile.length()} bytes) ✓")
        } catch (e: Exception) {
            Log.w(TAG, "Failed exporting benchmark JSON: ${e.message}")
        }
    }

    private fun logComparisonReport(comp: ModelBenchmarkComparison) {
        val sb = StringBuilder()
        sb.append("\n========================================\n")
        sb.append("LOCAL MODEL BENCHMARK\n")
        sb.append("========================================\n")
        sb.append("VIDEO:             ${comp.videoName}\n")
        sb.append("ACTUAL LANGUAGE:   ${comp.selectedLanguage.displayName}\n")
        sb.append("AUDIO DURATION:    ${"%.1f".format(comp.audioDurationSec)} sec\n")

        for ((idx, res) in comp.results.withIndex()) {
            sb.append("----------------------------------------\n")
            sb.append("MODEL ${idx + 1}: ${res.modelName}\n")
            sb.append("----------------------------------------\n")
            sb.append("Transcript:\n\"${res.transcript}\"\n")
            sb.append("Word Count:        ${res.wordCount} words\n")
            sb.append("Speech Coverage:   ${"%.1f".format(res.speechCoveragePercent)}%\n")
            sb.append("Confidence:        ${"%.2f".format(res.confidence)}\n")
            sb.append("Processing Time:   ${res.processingTimeMs} ms\n")
            sb.append("Quality Score:     ${"%.1f".format(res.qualityScore)} / 100\n")
        }

        sb.append("----------------------------------------\n")
        sb.append("BEST MODEL FOR THIS VIDEO:\n${comp.bestModelName}\n")
        sb.append("REASON:\n${comp.selectionRationale}\n")
        sb.append("========================================\n")

        DiagnosticLogger.log("LOCAL_BENCHMARK", sb.toString())
    }
}
