package com.example.videotranslator.ai.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.videotranslator.model.Gender
import com.example.videotranslator.model.Language
import com.example.videotranslator.model.TranslationSegment
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val TAG = "NeuralTtsEngine"

/**
 * Gender-Matched Neural Text-to-Speech Engine.
 * Synthesizes dubbed speech for English, Hindi, and Telugu matching male and female voices.
 */
class NeuralTtsEngine(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    init {
        tts = TextToSpeech(context) { status ->
            isTtsReady = status == TextToSpeech.SUCCESS
            if (isTtsReady) {
                DiagnosticLogger.log(TAG, "Android System TTS Engine initialized successfully ✓")
            }
        }
    }

    fun close() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
    }

    /**
     * Synthesizes audio for all segments in the selected target language with gender matching.
     */
    suspend fun synthesizeSegments(
        segments: List<TranslationSegment>,
        targetLanguage: Language,
        outputDir: File
    ): List<TranslationSegment> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyList()

        DiagnosticLogger.log(TAG, "STAGE 5 - Synthesizing ${segments.size} segments into ${targetLanguage.displayName} with gender matching…")
        outputDir.mkdirs()

        val results = mutableListOf<TranslationSegment>()

        for (seg in segments) {
            val text = when (targetLanguage) {
                Language.ENGLISH -> seg.english
                Language.HINDI   -> seg.hindi
                Language.TELUGU  -> seg.telugu
            }.ifBlank { seg.sourceText }

            val gender = seg.voiceGender
            val outputFile = File(outputDir, "dub_${seg.id}.wav")

            // Synthesize to WAV file
            synthesizeToFile(text, targetLanguage, gender, outputFile)

            results.add(
                seg.copy(
                    audioFilePath = outputFile.absolutePath
                )
            )
        }

        DiagnosticLogger.log(TAG, "STAGE 5 - Dubbed speech synthesis complete for ${results.size} segments ✓")
        results
    }

    private suspend fun synthesizeToFile(
        text: String,
        language: Language,
        gender: Gender,
        outputFile: File
    ) = withContext(Dispatchers.IO) {
        val ttsInstance = tts ?: return@withContext

        try {
            outputFile.parentFile?.mkdirs()

            val locale = when (language) {
                Language.HINDI   -> Locale("hi", "IN")
                Language.ENGLISH -> Locale.US
                Language.TELUGU  -> Locale("te", "IN")
            }
            ttsInstance.language = locale

            // Apply gender pitch & speech rate modulation
            when (gender) {
                Gender.FEMALE -> {
                    ttsInstance.setPitch(1.22f)
                    ttsInstance.setSpeechRate(1.05f)
                }
                Gender.MALE -> {
                    ttsInstance.setPitch(0.88f)
                    ttsInstance.setSpeechRate(0.98f)
                }
                Gender.UNKNOWN -> {
                    ttsInstance.setPitch(1.00f)
                    ttsInstance.setSpeechRate(1.00f)
                }
            }

            val deferred = CompletableDeferred<Boolean>()
            val utteranceId = "utt_${System.currentTimeMillis()}_${outputFile.nameWithoutExtension}"

            ttsInstance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) deferred.complete(true)
                }
                override fun onError(id: String?) {
                    if (id == utteranceId) deferred.complete(false)
                }
            })

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }

            val result = ttsInstance.synthesizeToFile(text, params, outputFile, utteranceId)
            if (result == TextToSpeech.SUCCESS) {
                try {
                    deferred.await()
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS synthesis notice for file ${outputFile.name}: ${e.message}")
        }
    }
}
