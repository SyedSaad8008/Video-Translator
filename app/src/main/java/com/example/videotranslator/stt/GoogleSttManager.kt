package com.example.videotranslator.stt

import android.util.Base64
import android.util.Log
import com.example.videotranslator.model.TranslationSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "GoogleSttManager"

/**
 * Hindi STT via Google Cloud Speech-to-Text REST API.
 *
 * Why Google Cloud STT instead of Vosk:
 *  - Uses Google's production acoustic model (trained on real-world Hindi speech + music)
 *  - Far superior accuracy on noisy/music-backed audio
 *  - Returns word-level timestamps natively
 *  - model = "latest_long" handles up to 60 s per call; we chunk longer audio ourselves
 *
 * Setup:
 *  1. Go to https://console.cloud.google.com/apis/api/speech.googleapis.com
 *  2. Enable the "Cloud Speech-to-Text API"
 *  3. Create an API key under "APIs & Services → Credentials"
 *  4. Paste it into [API_KEY] below.
 *  Free tier: 60 min / month — more than enough for an assessment.
 */
class GoogleSttManager {

    companion object {
        // ← PASTE YOUR GOOGLE CLOUD SPEECH-TO-TEXT API KEY HERE
        var API_KEY: String = ""
        private const val ENDPOINT = "https://speech.googleapis.com/v1/speech:recognize"
        /** 50 s of 16kHz audio per chunk (API limit is 60s for sync endpoint) */
        private const val CHUNK_SAMPLES = 50 * 16_000
    }

    /**
     * Transcribes [pcm] (16-bit LE, 16kHz, mono) into timed Hindi segments.
     * Audio is split into 50-second chunks; each chunk is sent independently
     * and timestamps are offset accordingly.
     */
    suspend fun recognise(pcm: ShortArray): List<TranslationSegment> =
        withContext(Dispatchers.IO) {
            val allSegments = mutableListOf<TranslationSegment>()
            var offset = 0

            while (offset < pcm.size) {
                val end = minOf(offset + CHUNK_SAMPLES, pcm.size)
                val chunk = pcm.copyOfRange(offset, end)
                val chunkOffsetMs = (offset.toLong() * 1000L) / 16_000L

                val segs = recogniseChunk(chunk, chunkOffsetMs)
                allSegments.addAll(segs)
                Log.d(TAG, "Chunk @${chunkOffsetMs}ms → ${segs.size} segments")
                offset = end
            }

            Log.d(TAG, "Total segments from Google STT: ${allSegments.size}")
            allSegments
        }

    // ─────────────────────────────────── internal ────────────────────────────

    private fun recogniseChunk(pcm: ShortArray, offsetMs: Long): List<TranslationSegment> {
        // Pack shorts → LE bytes → Base64
        val bytes = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
        val audioBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val requestJson = JSONObject().apply {
            put("config", JSONObject().apply {
                put("encoding", "LINEAR16")
                put("sampleRateHertz", 16_000)
                put("languageCode", "hi-IN")
                put("enableWordTimeOffsets", true)
                // "latest_long" handles music-backed speech & noise well
                put("model", "latest_long")
                put("useEnhanced", true)
                // Don't filter profanity, transcribe everything
                put("profanityFilter", false)
            })
            put("audio", JSONObject().apply {
                put("content", audioBase64)
            })
        }.toString()

        val url = URL("$ENDPOINT?key=$API_KEY")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 60_000
            conn.readTimeout = 180_000

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(requestJson) }

            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "no error body"
                Log.e(TAG, "HTTP ${conn.responseCode}: $err")
                return emptyList()
            }

            val responseJson = JSONObject(conn.inputStream.bufferedReader().readText())
            parseResponse(responseJson, offsetMs)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(response: JSONObject, offsetMs: Long): List<TranslationSegment> {
        val results = response.optJSONArray("results") ?: return emptyList()
        val segments = mutableListOf<TranslationSegment>()

        for (i in 0 until results.length()) {
            val alternatives = results.getJSONObject(i).optJSONArray("alternatives") ?: continue
            if (alternatives.length() == 0) continue

            val best = alternatives.getJSONObject(0)
            val text = best.optString("transcript", "").trim()
            if (text.isEmpty()) continue

            val words = best.optJSONArray("words")
            val startMs: Long
            val endMs: Long

            if (words != null && words.length() > 0) {
                startMs = offsetMs + parseTs(words.getJSONObject(0).optString("startTime", "0s"))
                endMs   = offsetMs + parseTs(words.getJSONObject(words.length() - 1).optString("endTime", "0s"))
            } else {
                startMs = offsetMs
                endMs   = offsetMs + (pcmDurationMs(text))
            }

            segments.add(TranslationSegment(startMs = startMs, endMs = endMs, hindi = text, sourceText = text))
        }
        return segments
    }

    /** Parse Google's "1.230s" timestamp format → milliseconds */
    private fun parseTs(s: String): Long =
        try { (s.trimEnd('s').toDouble() * 1000).toLong() } catch (_: Exception) { 0L }

    /** Rough fallback duration when word timestamps are missing */
    private fun pcmDurationMs(text: String): Long = text.length * 80L
}
