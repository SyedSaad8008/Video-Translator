package com.example.videotranslator.ai.translation

import android.content.Context
import com.example.videotranslator.model.Language
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "TranslationTestSuite"

/**
 * Automated Verification & Regression Suite for On-Device Translation.
 * Tests all 6 directions (HI ↔ EN ↔ TE) and exports results to translation_benchmark_results.json.
 */
class TranslationTestSuite(private val context: Context) {

    private val engine = NllbTranslationEngine(context)

    data class TestCase(
        val name: String,
        val src: Language,
        val tgt: Language,
        val input: String,
        val expectedKeywords: List<String> = emptyList()
    )

    data class TestResult(
        val testCase: TestCase,
        val output: String,
        val passed: Boolean,
        val latencyMs: Long,
        val message: String
    )

    suspend fun runAllTests(): List<TestResult> = withContext(Dispatchers.IO) {
        DiagnosticLogger.log(TAG, "══════════ STARTING 6-WAY NEURAL TRANSLATION BENCHMARK ══════════")
        engine.load()

        val tests = listOf(
            TestCase(
                name = "Hindi to English Direct",
                src = Language.HINDI,
                tgt = Language.ENGLISH,
                input = "मैं आज कॉलेज जा रहा हूँ।"
            ),
            TestCase(
                name = "Hindi to Telugu Pivot",
                src = Language.HINDI,
                tgt = Language.TELUGU,
                input = "मैं आज कॉलेज जा रहा हूँ।"
            ),
            TestCase(
                name = "Telugu to English Direct",
                src = Language.TELUGU,
                tgt = Language.ENGLISH,
                input = "నేను ఈరోజు కాలేజీకి వెళ్తున్నాను."
            ),
            TestCase(
                name = "Telugu to Hindi Pivot",
                src = Language.TELUGU,
                tgt = Language.HINDI,
                input = "నేను ఈరోజు కాలేజీకి వెళ్తున్నాను."
            ),
            TestCase(
                name = "English to Hindi Direct",
                src = Language.ENGLISH,
                tgt = Language.HINDI,
                input = "I am going to college today."
            ),
            TestCase(
                name = "English to Telugu Direct",
                src = Language.ENGLISH,
                tgt = Language.TELUGU,
                input = "I am going to college today."
            ),
            TestCase(
                name = "Hindustani with Urdu Vocabulary",
                src = Language.HINDI,
                tgt = Language.ENGLISH,
                input = "मुझे इस काम की इजाज़त चाहिए, यह बहुत ज़रूरी सवाल है।"
            ),
            TestCase(
                name = "Telugu Complex Sentence",
                src = Language.TELUGU,
                tgt = Language.ENGLISH,
                input = "హలో నేను సాద్ మీరు ఎవరు మీ పేరు ఏమిటి"
            )
        )

        val results = mutableListOf<TestResult>()

        for (test in tests) {
            val startTime = System.currentTimeMillis()
            try {
                val output = engine.translate(test.input, test.src, test.tgt)
                val elapsed = System.currentTimeMillis() - startTime
                val pass = output.isNotBlank() && output.trim() != test.input.trim()
                DiagnosticLogger.log(
                    TAG,
                    "TEST [${test.name}] [${test.src.name} -> ${test.tgt.name}]:\n  IN:  \"${test.input}\"\n  OUT: \"$output\"\n  LATENCY: ${elapsed}ms | STATUS: ${if (pass) "PASS ✓" else "FAIL ✗"}"
                )
                results.add(
                    TestResult(
                        testCase = test,
                        output = output,
                        passed = pass,
                        latencyMs = elapsed,
                        message = if (pass) "Verified translation output" else "Output matches input or empty"
                    )
                )
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startTime
                DiagnosticLogger.log(TAG, "TEST [${test.name}] ERROR: ${e.message}")
                results.add(
                    TestResult(
                        testCase = test,
                        output = "",
                        passed = false,
                        latencyMs = elapsed,
                        message = "Exception: ${e.message}"
                    )
                )
            }
        }

        exportJson(results)
        results
    }

    private fun exportJson(results: List<TestResult>) {
        try {
            val json = JSONObject()
            json.put("benchmark_timestamp", System.currentTimeMillis())
            json.put("total_tests", results.size)
            json.put("passed_tests", results.count { it.passed })

            val testsArray = JSONArray()
            for (res in results) {
                val tObj = JSONObject()
                tObj.put("test_name", res.testCase.name)
                tObj.put("source_language", res.testCase.src.name)
                tObj.put("target_language", res.testCase.tgt.name)
                tObj.put("input_text", res.testCase.input)
                tObj.put("output_text", res.output)
                tObj.put("passed", res.passed)
                tObj.put("latency_ms", res.latencyMs)
                tObj.put("message", res.message)
                testsArray.put(tObj)
            }
            json.put("results", testsArray)

            val outputFile = File(context.filesDir, "translation_benchmark_results.json")
            outputFile.writeText(json.toString(2))
            DiagnosticLogger.log(TAG, "Exported translation benchmark JSON: ${outputFile.absolutePath} ✓")
        } catch (_: Exception) {}
    }
}
