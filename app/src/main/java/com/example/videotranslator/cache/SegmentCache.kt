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
 * Enterprise Segment & Audio Cache Manager.
 * Supports unique Run-ID indexing (`context.filesDir/runs/<runId>/`) so every video upload
 * generates a brand-new run entry for side-by-side comparison in the persistent library.
 */
class SegmentCache(private val context: Context) {

    var lastLoaded: List<TranslationSegment>? = null
        private set

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    // ── Run ID Directory Strategy ─────────────────────────────────────────────
    fun runDir(runId: String): File {
        val dir = File(context.filesDir, "runs/$runId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun segmentFileForRun(runId: String): File = File(runDir(runId), "segments.json")
    fun pcmFileForRun(runId: String): File = File(runDir(runId), "mono.pcm")
    fun instrumentalFileForRun(runId: String): File = File(runDir(runId), "instrumental.wav")
    fun renderedAudioDirForRun(runId: String): File {
        val dir = File(runDir(runId), "rendered_audio")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun loadRun(runId: String): List<TranslationSegment>? = withContext(Dispatchers.IO) {
        val file = segmentFileForRun(runId)
        if (!file.exists()) return@withContext null
        try {
            json.decodeFromString<List<TranslationSegment>>(file.readText()).also { lastLoaded = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cache for runId: $runId", e)
            null
        }
    }

    suspend fun saveRun(runId: String, segments: List<TranslationSegment>) = withContext(Dispatchers.IO) {
        try {
            segmentFileForRun(runId).writeText(json.encodeToString(segments))
            lastLoaded = segments
            Log.d(TAG, "Saved ${segments.size} segments for runId: $runId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache for runId: $runId", e)
        }
    }

    // ── Fallback URI Key Strategy (Backward Compatibility) ─────────────────────
    private fun keyFor(uri: Uri): String {
        val hash = uri.toString().hashCode().toString(16).takeLast(8)
        val name = uri.lastPathSegment?.replace(Regex("[^a-zA-Z0-9_-]"), "_")?.take(20) ?: "video"
        return "${name}_$hash"
    }

    fun isCached(uri: Uri): Boolean {
        val k = keyFor(uri)
        return File(context.filesDir, "segments_$k.json").exists()
    }

    fun pcmFileFor(uri: Uri): File = File(context.filesDir, "audio_${keyFor(uri)}.raw")
    fun instrumentalFileFor(uri: Uri): File = File(context.filesDir, "music_${keyFor(uri)}.raw")
    fun renderedAudioDir(uri: Uri): File {
        val dir = File(context.filesDir, "rendered_${keyFor(uri)}")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun load(uri: Uri): List<TranslationSegment>? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "segments_${keyFor(uri)}.json")
        if (!file.exists()) return@withContext null
        try {
            json.decodeFromString<List<TranslationSegment>>(file.readText()).also { lastLoaded = it }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun save(uri: Uri, segments: List<TranslationSegment>) = withContext(Dispatchers.IO) {
        try {
            File(context.filesDir, "segments_${keyFor(uri)}.json").writeText(json.encodeToString(segments))
            lastLoaded = segments
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache", e)
        }
    }

    fun clearFor(uri: Uri) {
        val k = keyFor(uri)
        File(context.filesDir, "segments_$k.json").delete()
        File(context.filesDir, "audio_$k.raw").delete()
        instrumentalFileFor(uri).delete()
        renderedAudioDir(uri).deleteRecursively()
    }
}
