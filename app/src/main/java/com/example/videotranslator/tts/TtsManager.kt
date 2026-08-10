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
                logAllDeviceVoices()
            } else {
                Log.e(TAG, "TTS Engine initialization failed with status=$status")
            }
            if (cont.isActive) cont.resume(ready)
        }
    }

    /**
     * Enumerate every voice on the device for diagnostic reporting.
     */
    fun logAllDeviceVoices() {
        val ttsEngine = tts ?: return
        try {
            val allVoices = ttsEngine.voices ?: emptySet()
            Log.d(TAG, "================ ALL DEVICE TTS VOICES (${allVoices.size} total) ================")
            for (v in allVoices) {
                Log.d(
                    TAG,
                    "VOICE: name='${v.name}', locale='${v.locale}', quality=${v.quality}, " +
                            "networkReq=${v.isNetworkConnectionRequired}, features=${v.features}"
                )
            }
            Log.d(TAG, "==================================================================")
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating device voices: ${e.message}", e)
        }
    }

    /**
     * Selects a gender-matched voice for [language] and [gender].
     * Uses a verified lookup table for Google TTS engine:
     *  - English Male:   en-us-x-tpd-local / network, en-in-x-end-local / network, en-in-x-ena, en-us-x-iom
     *  - English Female: en-us-x-iob-local / network, en-us-x-tpc-local / network, en-us-x-sfg, en-in-x-enc
     *  - Telugu Male:    te-in-x-teg-local / network
     *  - Telugu Female:  te-in-x-tee-local / network
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

        Log.d(TAG, "Found ${availableVoices.size} candidate voices for locale '${targetLocale}' (${language}):")

        // 1. Verified Deep Google TTS Voice Lookup Table
        val preferredVoiceNames = when (language) {
            Language.ENGLISH -> if (gender == Gender.MALE) {
                listOf(
                    "en-us-x-tpd-local", "en-us-x-tpd-network",
                    "en-in-x-end-local", "en-in-x-end-network",
                    "en-in-x-ena-local", "en-in-x-ena-network",
                    "en-us-x-iom-local", "en-us-x-iom-network",
                    "en-us-x-iol-local", "en-us-x-iol-network"
                )
            } else {
                listOf(
                    "en-us-x-iob-local", "en-us-x-iob-network",
                    "en-us-x-tpc-local", "en-us-x-tpc-network",
                    "en-us-x-sfg-local", "en-us-x-sfg-network",
                    "en-in-x-enc-local", "en-in-x-enc-network",
                    "en-us-x-iog-local", "en-us-x-iog-network"
                )
            }
            Language.TELUGU -> if (gender == Gender.MALE) {
                listOf(
                    "te-in-x-teg-local", "te-in-x-teg-network"
                )
            } else {
                listOf(
                    "te-in-x-tee-local", "te-in-x-tee-network"
                )
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
            val indicTag  = if (gender == Gender.MALE) "teg" else "tee"

            matchedVoice = availableVoices.firstOrNull { v ->
                val name = v.name.lowercase()
                name.contains(genderTag) || name.contains(altTag) || name.contains(indicTag)
            }
        }

        val voiceBefore = ttsEngine.voice?.name ?: "null"

        // 3. Set voice and inspect actual assigned Voice object
        if (matchedVoice != null) {
            try {
                ttsEngine.voice = matchedVoice
                val voiceAfter = ttsEngine.voice?.name ?: "null"
                selectedVoiceName = voiceAfter
                isMissingVoice = false

                Log.d(TAG, "SUCCESSFULLY ASSIGNED DEEP MALE VOICE for $language ($gender):\n" +
                        "   Requested: '${matchedVoice.name}'\n" +
                        "   Assigned:  '$voiceAfter'\n" +
                        "   Match Verified: ${voiceAfter == matchedVoice.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set voice ${matchedVoice.name}: ${e.message}")
                selectedVoiceName = "Default Locale Voice"
                isMissingVoice = true
            }
        } else {
            selectedVoiceName = "Default Locale Voice (Unmatched)"
            isMissingVoice = true
            Log.w(TAG, "No specific $gender voice found for $language -> surfaced warning card, fallback to '$voiceBefore'")
        }
    }

    /**
     * Synthesizes [text] to [destFile] using the currently set voice/language.
     * Returns rendered audio duration in milliseconds, or -1L on failure.
     */
    suspend fun synthesizeToFile(text: String, destFile: File): Long = withContext(Dispatchers.IO) {
        val ttsEngine = tts ?: return@withContext -1L
        if (!ready || text.isBlank()) return@withContext -1L

        val currentVoiceName = ttsEngine.voice?.name ?: "null"
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

        val durationMs = measureAudioDurationMs(destFile)
        Log.d(TAG, "Pre-rendered audio (${destFile.name}): text=\"${text.take(40)}...\", " +
                "activeVoice='$currentVoiceName', size=${destFile.length()} bytes, duration=${durationMs}ms")
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
