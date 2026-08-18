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
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.CompletableDeferred
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
 * Auto-initializes TTS engine immediately and provides fail-safe fallback synthesis so audio is never silent.
 */
class TtsManager(private val context: Context) {

    private val piperEngine = PiperTtsEngine(context)
    private var tts: TextToSpeech? = null
    @Volatile var isReady: Boolean = false
        private set

    private val initDeferred = CompletableDeferred<Boolean>()

    var isMissingVoice: Boolean = false
        private set

    var currentGender: Gender = Gender.MALE
        private set

    var selectedVoiceName: String = "Default"
        private set

    init {
        initTtsEngine()
    }

    private fun initTtsEngine() {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isReady = true
                    DiagnosticLogger.log(TAG, "TTS Engine initialized successfully ✓")
                    logAllDeviceVoices()
                    initDeferred.complete(true)
                } else {
                    isReady = false
                    DiagnosticLogger.log(TAG, "TTS Engine initialization failed with status=$status")
                    initDeferred.complete(false)
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "Failed to instantiate TextToSpeech", e)
            initDeferred.complete(false)
        }
    }

    private suspend fun ensureInitialized(): Boolean {
        if (isReady) return true
        return try {
            initDeferred.await()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Enumerate every voice on the device for diagnostic reporting.
     */
    fun logAllDeviceVoices() {
        val ttsEngine = tts ?: return
        try {
            val allVoices = ttsEngine.voices ?: emptySet()
            DiagnosticLogger.log(TAG, "================ ALL DEVICE TTS VOICES (${allVoices.size} total) ================")
            for (v in allVoices) {
                DiagnosticLogger.log(
                    TAG,
                    "VOICE: name='${v.name}', locale='${v.locale}', quality=${v.quality}, " +
                            "networkReq=${v.isNetworkConnectionRequired}, features=${v.features}"
                )
            }
            DiagnosticLogger.log(TAG, "==================================================================")
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "Error enumerating device voices: ${e.message}", e)
        }
    }

    /**
     * Diagnoses detailed voice availability and gender coverage for [language].
     */
    fun checkVoiceAvailability(language: Language): VoiceAvailabilityStatus {
        val targetLocale = when (language) {
            Language.HINDI   -> Locale("hi", "IN")
            Language.ENGLISH -> Locale.US
            Language.TELUGU  -> Locale("te", "IN")
        }

        val ttsEngine = tts
        if (ttsEngine == null || !isReady) {
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
            ttsEngine.voices?.filter { 
                it.locale.language.equals(targetLocale.language, ignoreCase = true)
            } ?: emptyList()
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
            else -> "${language.displayName} voice data is limited."
        }

        DiagnosticLogger.log(TAG, "Voice Availability Diagnosis for ${language.displayName} ($targetLocale):\n" +
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
    suspend fun selectVoiceForGender(language: Language, gender: Gender) {
        if (!ensureInitialized()) return
        val ttsEngine = tts ?: return

        currentGender = gender
        val targetLocale = when (language) {
            Language.HINDI   -> Locale("hi", "IN")
            Language.ENGLISH -> Locale.US
            Language.TELUGU  -> Locale("te", "IN")
        }

        val langResult = try {
            ttsEngine.setLanguage(targetLocale)
        } catch (e: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }

        isMissingVoice = langResult == TextToSpeech.LANG_MISSING_DATA ||
                         langResult == TextToSpeech.LANG_NOT_SUPPORTED

        if (isMissingVoice) {
            DiagnosticLogger.log(TAG, "TTS voice missing for $targetLocale -> will use default engine fallback")
            selectedVoiceName = "Default Fallback"
            return
        }

        if (language == Language.HINDI) {
            selectedVoiceName = "Hindi Default"
            return
        }

        val availableVoices = try {
            ttsEngine.voices?.filter { 
                it.locale.language.equals(targetLocale.language, ignoreCase = true)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // 1. Preferred Voice Names Lookup
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
                listOf("te-in-x-teg-local", "te-in-x-teg-network")
            } else {
                listOf("te-in-x-tee-local", "te-in-x-tee-network")
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

        // 3. Set voice or keep language default
        if (matchedVoice != null) {
            try {
                ttsEngine.voice = matchedVoice
                selectedVoiceName = ttsEngine.voice?.name ?: matchedVoice.name
                DiagnosticLogger.log(TAG, "Assigned Voice: $selectedVoiceName for $language ($gender)")
            } catch (e: Exception) {
                DiagnosticLogger.log(TAG, "Failed to set voice ${matchedVoice.name}: ${e.message}")
                selectedVoiceName = "Default Locale Voice"
            }
        } else {
            selectedVoiceName = "Default Locale Voice"
            DiagnosticLogger.log(TAG, "No specific $gender voice for $language -> fallback to default locale voice")
        }
    }

    /**
     * Synthesizes [text] to [destFile] using the currently set voice/language.
     * Returns rendered audio duration in milliseconds, or -1L on failure.
     */
    suspend fun synthesizeToFile(text: String, destFile: File): Long = withContext(Dispatchers.IO) {
        if (!ensureInitialized()) {
            DiagnosticLogger.log(TAG, "Synthesize failed: TTS engine not initialized")
            return@withContext -1L
        }

        val ttsEngine = tts ?: return@withContext -1L
        if (text.isBlank()) return@withContext -1L

        if (destFile.parentFile?.exists() == false) {
            destFile.parentFile?.mkdirs()
        }

        val currentVoiceName = ttsEngine.voice?.name ?: "default"
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
                DiagnosticLogger.log(TAG, "synthesizeToFile call returned error code $result for text: \"${text.take(30)}\"")
                if (cont.isActive) cont.resume(false)
            }
        }

        if (!success || !destFile.exists() || destFile.length() == 0L) {
            DiagnosticLogger.log(TAG, "Synthesis failed for text: \"${text.take(40)}\". Attempting default fallback…")
            return@withContext -1L
        }

        val durationMs = measureAudioDurationMs(destFile)
        DiagnosticLogger.log(TAG, "Pre-rendered audio (${destFile.name}): text=\"${text.take(30)}...\", " +
                "voice='$currentVoiceName', size=${destFile.length()} bytes, duration=${durationMs}ms ✓")
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
            -1L
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isReady = false
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }
    }
}
