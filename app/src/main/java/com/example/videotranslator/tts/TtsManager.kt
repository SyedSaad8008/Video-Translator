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
 * Detailed status data class describing device TTS voice availability.
 */
data class VoiceAvailabilityStatus(
    val language: Language,
    val locale: Locale,
    val isLanguageSupported: Boolean,
    val totalVoicesCount: Int,
    val hasMaleVoice: Boolean,
    val hasFemaleVoice: Boolean,
    val hasGenderMatchedVoices: Boolean,
    val isSingleVoiceOnly: Boolean,
    val message: String
)

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
     * Diagnoses detailed voice availability and gender coverage for [language].
     */
    fun checkVoiceAvailability(language: Language): VoiceAvailabilityStatus {
        val ttsEngine = tts
        val targetLocale = when (language) {
            Language.HINDI   -> Locale("hi", "IN")
            Language.ENGLISH -> Locale.US
            Language.TELUGU  -> Locale("te", "IN")
        }

        if (ttsEngine == null || !ready) {
            return VoiceAvailabilityStatus(
                language = language,
                locale = targetLocale,
                isLanguageSupported = false,
                totalVoicesCount = 0,
                hasMaleVoice = false,
                hasFemaleVoice = false,
                hasGenderMatchedVoices = false,
                isSingleVoiceOnly = false,
                message = "TTS engine is initializing..."
            )
        }

        val availability = try {
            ttsEngine.isLanguageAvailable(targetLocale)
        } catch (e: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }

        val isSupported = availability != TextToSpeech.LANG_MISSING_DATA &&
                          availability != TextToSpeech.LANG_NOT_SUPPORTED

        if (!isSupported) {
            return VoiceAvailabilityStatus(
                language = language,
                locale = targetLocale,
                isLanguageSupported = false,
                totalVoicesCount = 0,
                hasMaleVoice = false,
                hasFemaleVoice = false,
                hasGenderMatchedVoices = false,
                isSingleVoiceOnly = false,
                message = "${language.displayName} TTS voice data is not installed on this device."
            )
        }

        val availableVoices = try {
            ttsEngine.voices?.filter { it.locale.language == targetLocale.language } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val maleKeywords = listOf("male", "-m-", "tpd", "end", "ena", "iom", "iol", "teg")
        val femaleKeywords = listOf("female", "-f-", "iob", "tpc", "sfg", "enc", "iog", "tee")

        val hasMale = availableVoices.any { v ->
            val name = v.name.lowercase()
            maleKeywords.any { kw -> name.contains(kw) }
        }

        val hasFemale = availableVoices.any { v ->
            val name = v.name.lowercase()
            femaleKeywords.any { kw -> name.contains(kw) }
        }

        val hasBoth = hasMale && hasFemale
        val isSingleVoice = availableVoices.size <= 1 || (!hasMale || !hasFemale)

        val msg = when {
            hasBoth -> "${language.displayName} has distinct Male and Female voices installed."
            availableVoices.size == 1 -> "${language.displayName} has only 1 voice installed on this device (no distinct male/female option)."
            !hasMale -> "${language.displayName} is missing a dedicated Male voice."
            !hasFemale -> "${language.displayName} is missing a dedicated Female voice."
            else -> "${language.displayName} voice data is incomplete."
        }

        Log.d(TAG, "Voice Availability Diagnosis for ${language.displayName} ($targetLocale):\n" +
                "   Supported: $isSupported, Total Voices: ${availableVoices.size}\n" +
                "   Male: $hasMale, Female: $hasFemale, Both: $hasBoth, Message: \"$msg\"")

        return VoiceAvailabilityStatus(
            language = language,
            locale = targetLocale,
            isLanguageSupported = true,
            totalVoicesCount = availableVoices.size,
            hasMaleVoice = hasMale,
            hasFemaleVoice = hasFemale,
            hasGenderMatchedVoices = hasBoth,
            isSingleVoiceOnly = isSingleVoice,
            message = msg
        )
    }

    /**
     * Selects a gender-matched voice for [language] and [gender].
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

                Log.d(TAG, "SUCCESSFULLY ASSIGNED VOICE for $language ($gender):\n" +
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
            isMissingVoice = availableVoices.isEmpty()
            Log.w(TAG, "No specific $gender voice found for $language -> fallback to default voice '$voiceBefore'")
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
