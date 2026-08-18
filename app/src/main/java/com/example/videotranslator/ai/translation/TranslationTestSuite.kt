package com.example.videotranslator.ai.translation

import android.content.Context
import com.example.videotranslator.model.Language
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "TranslationTestSuite"

/**
 * Automated Verification Test Suite for On-Device 6-Way Multilingual Translation Engine.
 * Tests:
 *  1. Hindi -> English
 *  2. Telugu -> English
 *  3. English -> Hindi
 *  4. English -> Telugu
 *  5. Telugu -> Hindi
 *  6. Hindi -> Telugu
 *  7. Hindustani / Urdu mixed vocabulary (ज़रूरी, इजाज़त, मोहब्बत, ज़िंदगी, सवाल, जवाब)
 *  8. Long multi-clause conversational sentences
 */
class TranslationTestSuite(private val context: Context) {

    private val engine = NllbTranslationEngine(context)

    data class TestResult(
        val testName: String,
        val sourceLanguage: Language,
        val targetLanguage: Language,
        val inputText: String,
        val outputText: String,
        val isSuccess: Boolean
    )

    suspend fun runAllTests(): List<TestResult> = withContext(Dispatchers.IO) {
        DiagnosticLogger.log(TAG, "══════════ STARTING ON-DEVICE TRANSLATION TEST SUITE ══════════")
        engine.load()

        val tests = listOf(
            TestCase(
                name = "Hindi to English Basic",
                src = Language.HINDI,
                tgt = Language.ENGLISH,
                input = "मैं आज कॉलेज जा रहा हूँ।"
            ),
            TestCase(
                name = "Telugu to English Basic",
                src = Language.TELUGU,
                tgt = Language.ENGLISH,
                input = "నేను ఈరోజు కాలేజీకి వెళ్తున్నాను."
            ),
            TestCase(
                name = "English to Hindi Basic",
                src = Language.ENGLISH,
                tgt = Language.HINDI,
                input = "I am going to college today."
            ),
            TestCase(
                name = "English to Telugu Basic",
                src = Language.ENGLISH,
                tgt = Language.TELUGU,
                input = "I am going to college today."
            ),
            TestCase(
                name = "Telugu to Hindi Direct/Pivot",
                src = Language.TELUGU,
                tgt = Language.HINDI,
                input = "నేను ఈరోజు కాలేజీకి వెళ్తున్నాను."
            ),
            TestCase(
                name = "Hindi to Telugu Direct/Pivot",
                src = Language.HINDI,
                tgt = Language.TELUGU,
                input = "मैं आज कॉलेज जा रहा हूँ।"
            ),
            TestCase(
                name = "Hindustani with Urdu Vocabulary (इजाज़त / ज़रूरी)",
                src = Language.HINDI,
                tgt = Language.ENGLISH,
                input = "मुझे इस काम की इजाज़त चाहिए, यह बहुत ज़रूरी सवाल है।"
            ),
            TestCase(
                name = "Long Multi-Clause Sentence",
                src = Language.HINDI,
                tgt = Language.ENGLISH,
                input = "वह वहाँ गया था और उसने मुझे फोन किया ताकि हम आगे की योजना बना सकें।"
            )
        )

        val results = mutableListOf<TestResult>()

        for (test in tests) {
            try {
                val output = engine.translate(test.input, test.src, test.tgt)
                val pass = output.isNotBlank() && output != test.input
                DiagnosticLogger.log(
                    TAG,
                    "TEST [${test.name}] [${test.src.name} -> ${test.tgt.name}]:\n  IN: \"${test.input}\"\n  OUT: \"$output\"\n  STATUS: ${if (pass) "PASS ✓" else "FAIL ✗"}"
                )
                results.add(
                    TestResult(
                        testName = test.name,
                        sourceLanguage = test.src,
                        targetLanguage = test.tgt,
                        inputText = test.input,
                        outputText = output,
                        isSuccess = pass
                    )
                )
            } catch (e: Exception) {
                DiagnosticLogger.log(TAG, "TEST [${test.name}] EXCEPTION: ${e.message}", e)
                results.add(
                    TestResult(
                        testName = test.name,
                        sourceLanguage = test.src,
                        targetLanguage = test.tgt,
                        inputText = test.input,
                        outputText = "ERROR: ${e.message}",
                        isSuccess = false
                    )
                )
            }
        }

        val passedCount = results.count { it.isSuccess }
        DiagnosticLogger.log(TAG, "══════════ TEST SUITE COMPLETE: $passedCount/${results.size} PASSED ══════════")
        results
    }

    private data class TestCase(
        val name: String,
        val src: Language,
        val tgt: Language,
        val input: String
    )
}
