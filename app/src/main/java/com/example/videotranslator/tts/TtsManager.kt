package com.example.videotranslator.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.videotranslator.model.Language
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "TtsManager"

/**
 * Wraps Android TextToSpeech for clear, natural speech output.
 */
class TtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    var isMissingVoice = false
        private set

    var onSpeakStart: (() -> Unit)? = null
    var onSpeakDone: (() -> Unit)? = null

    suspend fun initialise(): Boolean = suspendCancellableCoroutine { cont ->
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onSpeakStart?.invoke()
                    }
                    override fun onDone(utteranceId: String?) {
                        onSpeakDone?.invoke()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onSpeakDone?.invoke()
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        onSpeakDone?.invoke()
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        onSpeakDone?.invoke()
                    }
                })
            }
            if (cont.isActive) cont.resume(ready)
        }
    }

    fun setLanguage(language: Language) {
        val ttsEngine = tts ?: return
        val locale = when (language) {
            Language.HINDI   -> Locale("hi", "IN")
            Language.ENGLISH -> Locale.US
            Language.TELUGU  -> Locale("te", "IN")
        }
        val result = ttsEngine.setLanguage(locale)
        isMissingVoice = result == TextToSpeech.LANG_MISSING_DATA ||
                         result == TextToSpeech.LANG_NOT_SUPPORTED
        if (isMissingVoice) Log.w(TAG, "TTS voice missing for $locale")
    }

    /**
     * Speak [text] at a natural, comfortable human speech rate (1.0x to 1.15x max).
     */
    fun speakTimed(text: String, durationMs: Long, isIndic: Boolean = false) {
        val ttsEngine = tts ?: return
        if (!ready || text.isBlank()) return

        // Speech rate kept at natural human pace (1.0x - 1.15x) so speech is clear and articulate
        val msPerChar = if (isIndic) 110f else 85f
        val estimatedMs = text.length * msPerChar

        val rate = if (durationMs > 0 && estimatedMs > durationMs) {
            (estimatedMs / durationMs).coerceIn(1.0f, 1.15f)
        } else {
            1.0f
        }

        ttsEngine.setSpeechRate(rate)
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "seg") }
        ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "seg")
        Log.d(TAG, "Speaking (rate=${"%.2f".format(rate)}x): ${text.take(60)}")
    }

    fun speak(text: String) {
        val ttsEngine = tts ?: return
        if (!ready || text.isBlank()) return
        ttsEngine.setSpeechRate(1.0f)
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "seg") }
        ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "seg")
        Log.d(TAG, "Speaking: ${text.take(60)}")
    }

    fun stop() {
        tts?.stop()
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
