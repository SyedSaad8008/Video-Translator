package com.example.videotranslator.cache

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.videotranslator.model.TranslationSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "SegmentCache"

/**
 * Persists translated segments per video (keyed by a stable ID derived from the URI).
 * Each video gets its own JSON + PCM pair so switching videos doesn't re-use stale data.
 */
class SegmentCache(private val context: Context) {

    /** In-memory copy of the most recently loaded/saved segment list. */
    var lastLoaded: List<TranslationSegment>? = null
        private set

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    /** Derive a short, filesystem-safe key from the video URI. */
    private fun keyFor(uri: Uri): String {
        val hash = uri.toString().hashCode().toString(16).takeLast(8)
        val name = uri.lastPathSegment?.replace(Regex("[^a-zA-Z0-9_-]"), "_")?.take(20) ?: "video"
        return "${name}_$hash"
    }

    private fun segmentFile(key: String) = File(context.filesDir, "segments_$key.json")
    fun pcmFile(key: String) = File(context.filesDir, "audio_$key.raw")

    fun isCached(uri: Uri): Boolean {
        val k = keyFor(uri)
        return segmentFile(k).exists() && pcmFile(k).exists()
    }

    fun pcmFileFor(uri: Uri): File = pcmFile(keyFor(uri))

    /** Stereo center-channel-cancelled PCM for the instrumental background player. */
    fun instrumentalFileFor(uri: Uri): File = File(context.filesDir, "music_${keyFor(uri)}.raw")

    fun hasInstrumental(uri: Uri): Boolean = instrumentalFileFor(uri).exists()

    /** Directory for pre-rendered segment audio files (Part B sync). */
    fun renderedAudioDir(uri: Uri): File {
        val dir = File(context.filesDir, "rendered_${keyFor(uri)}")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun load(uri: Uri): List<TranslationSegment>? = withContext(Dispatchers.IO) {
        val file = segmentFile(keyFor(uri))
        if (!file.exists()) return@withContext null
        try {
            json.decodeFromString<List<TranslationSegment>>(file.readText()).also { lastLoaded = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cache for $uri", e)
            null
        }
    }

    suspend fun save(uri: Uri, segments: List<TranslationSegment>) = withContext(Dispatchers.IO) {
        try {
            segmentFile(keyFor(uri)).writeText(json.encodeToString(segments))
            lastLoaded = segments
            Log.d(TAG, "Saved ${segments.size} segments for ${uri.lastPathSegment}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache", e)
        }
    }

    /** Remove cached data and pre-rendered audio files for one specific video. */
    fun clearFor(uri: Uri) {
        val k = keyFor(uri)
        segmentFile(k).delete()
        pcmFile(k).delete()
        instrumentalFileFor(uri).delete()
        renderedAudioDir(uri).deleteRecursively()
    }

    /** Remove ALL cached data (all videos). */
    fun clearAll() {
        context.filesDir.listFiles()
            ?.filter { it.name.startsWith("segments_") || it.name.startsWith("audio_") || it.name.startsWith("rendered_") }
            ?.forEach { it.deleteRecursively() }
    }
}
