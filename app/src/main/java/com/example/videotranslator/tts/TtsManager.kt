package com.example.videotranslator.tts

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.videotranslator.model.Gender
import com.example.videotranslator.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "TtsManager"

/**
 * Wraps Android TextToSpeech for gender-matched voice selection and file pre-rendering.
 */
class TtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    var isMissingVoice = false
        private set

    var currentGender: Gender = Gender.MALE
        private set

    var selectedVoiceName: String = "Default"
        private set

    suspend fun initialise(): Boolean = suspendCancellableCoroutine { cont ->
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                Log.d(TAG, "TTS Engine initialized successfully")
            } else {
                Log.e(TAG, "TTS Engine initialization failed with status=$status")
            }
            if (cont.isActive) cont.resume(ready)
        }
    }

    /**
     * Selects a gender-matched voice for [language] and [gender].
     * Uses a verified lookup table for Google TTS engine, with dynamic feature fallback.
     */
    fun selectVoiceForGender(language: Language, gender: Gender) {
        val ttsEngine = tts ?: return
        if (!ready) return

        currentGender = gender
        val targetLocale = when (language) {
            Language.HINDI   -> Locale("hi", "IN")
            Language.ENGLISH -> Locale.US
            Language.TELUGU  -> Locale("te", "IN")
        }

        val langResult = ttsEngine.setLanguage(targetLocale)
        isMissingVoice = langResult == TextToSpeech.LANG_MISSING_DATA ||
                         langResult == TextToSpeech.LANG_NOT_SUPPORTED

        if (isMissingVoice) {
            Log.w(TAG, "TTS voice missing for $targetLocale")
            selectedVoiceName = "Default (Missing Data)"
            return
        }

        if (language == Language.HINDI) {
            selectedVoiceName = "Hindi Default"
            return
        }

        val availableVoices = try {
            ttsEngine.voices?.filter { it.locale.language == targetLocale.language } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query tts.voices: ${e.message}")
            emptyList()
        }

        Log.d(TAG, "Found ${availableVoices.size} candidate voices for ${targetLocale.language}")

        // 1. Verified Google TTS Voice Lookup Table
        val preferredVoiceNames = when (language) {
            Language.ENGLISH -> if (gender == Gender.MALE) {
                listOf("en-us-x-iom-network", "en-us-x-iom-local", "en-us-x-tpf-network", "en-us-x-sfg-network", "en-us-x-sfg-local", "en-us-x-iol-local")
            } else {
                listOf("en-us-x-iob-network", "en-us-x-iob-local", "en-us-x-tpc-network", "en-us-x-tpc-local", "en-us-x-sfg-local")
            }
            Language.TELUGU -> if (gender == Gender.MALE) {
                listOf("te-in-x-tem-network", "te-in-x-tem-local")
            } else {
                listOf("te-in-x-tef-network", "te-in-x-tef-local")
            }
            else -> emptyList()
        }

        var matchedVoice: Voice? = availableVoices.firstOrNull { voice ->
            preferredVoiceNames.any { pref -> voice.name.lowercase().contains(pref.lowercase()) }
        }

        // 2. Dynamic Feature / Name Inspection Fallback
        if (matchedVoice == null) {
            val genderTag = if (gender == Gender.MALE) "male" else "female"
            val altTag    = if (gender == Gender.MALE) "-m-" else "-f-"
            val indicTag  = if (gender == Gender.MALE) "tem" else "tef"

            matchedVoice = availableVoices.firstOrNull { v ->
                val name = v.name.lowercase()
                name.contains(genderTag) || name.contains(altTag) || name.contains(indicTag)
            }
        }

        // 3. Fallback to default locale voice if no gender match found
        if (matchedVoice != null) {
            try {
                ttsEngine.voice = matchedVoice
                selectedVoiceName = matchedVoice.name
                Log.d(TAG, "Selected gender-matched voice: ${matchedVoice.name} for $language ($gender)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set voice ${matchedVoice.name}: ${e.message}")
                selectedVoiceName = "Default Locale Voice"
            }
        } else {
            selectedVoiceName = "Default Locale Voice"
            Log.d(TAG, "No specific $gender voice found for $language -> using default locale voice")
        }
    }

    /**
     * Synthesizes [text] to [destFile] using the currently set voice/language.
     * Returns rendered audio duration in milliseconds, or -1L on failure.
     */
    suspend fun synthesizeToFile(text: String, destFile: File): Long = withContext(Dispatchers.IO) {
        val ttsEngine = tts ?: return@withContext -1L
        if (!ready || text.isBlank()) return@withContext -1L

        val utteranceId = "synth_${System.currentTimeMillis()}_${destFile.nameWithoutExtension}"

        val success = suspendCancellableCoroutine<Boolean> { cont ->
            val listener = object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}

                override fun onDone(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(false)
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && cont.isActive) cont.resume(false)
                }
            }

            ttsEngine.setOnUtteranceProgressListener(listener)
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
            val result = ttsEngine.synthesizeToFile(text, params, destFile, utteranceId)

            if (result != TextToSpeech.SUCCESS) {
                Log.e(TAG, "synthesizeToFile call failed with error code $result")
                if (cont.isActive) cont.resume(false)
            }
        }

        if (!success || !destFile.exists() || destFile.length() == 0L) {
            Log.e(TAG, "Synthesis failed or created empty file for: ${text.take(40)}")
            return@withContext -1L
        }

        // Measure rendered audio duration
        val durationMs = measureAudioDurationMs(destFile)
        Log.d(TAG, "Pre-rendered segment audio (${destFile.name}): text=\"${text.take(40)}...\", " +
                "size=${destFile.length()} bytes, duration=${durationMs}ms")
        durationMs
    }

    private fun measureAudioDurationMs(audioFile: File): Long {
        return try {
            val mp = MediaPlayer()
            mp.setDataSource(audioFile.absolutePath)
            mp.prepare()
            val dur = mp.duration.toLong()
            mp.release()
            dur
        } catch (e: Exception) {
            Log.w(TAG, "Failed to measure audio duration for ${audioFile.name}: ${e.message}")
            -1L
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
