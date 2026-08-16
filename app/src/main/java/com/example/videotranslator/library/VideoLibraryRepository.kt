package com.example.videotranslator.library

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.videotranslator.util.DiagnosticLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "VideoLibraryRepository"
private const val INDEX_FILE_NAME = "library_runs.json"

/**
 * Metadata model for a single video translation run in the persistent library.
 */
data class VideoRun(
    val runId: String = UUID.randomUUID().toString(),
    val uriString: String,
    val videoTitle: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val detectedGender: String = "MALE",
    val detectedSourceLanguage: String = "HINDI", // "HINDI", "TELUGU", "ENGLISH"
    val segmentCount: Int = 0,
    val status: String = "Ready" // "Ready", "Processing", "Error"
) {
    val formattedDate: String
        get() = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US).format(Date(timestampMs))
}

/**
 * Enterprise JSON-backed Persistent Video Library Repository.
 * Maintains full history of past translation runs keyed by unique UUID run ID.
 */
class VideoLibraryRepository(private val context: Context) {

    private val indexFile = File(context.filesDir, INDEX_FILE_NAME)

    @Synchronized
    fun getAllRuns(): List<VideoRun> {
        if (!indexFile.exists() || indexFile.length() == 0L) return emptyList()
        return try {
            val jsonText = indexFile.readText()
            val array = JSONArray(jsonText)
            val list = mutableListOf<VideoRun>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    VideoRun(
                        runId = obj.optString("runId", UUID.randomUUID().toString()),
                        uriString = obj.optString("uriString", ""),
                        videoTitle = obj.optString("videoTitle", "Video Run"),
                        timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
                        detectedGender = obj.optString("detectedGender", "MALE"),
                        detectedSourceLanguage = obj.optString("detectedSourceLanguage", "HINDI"),
                        segmentCount = obj.optInt("segmentCount", 0),
                        status = obj.optString("status", "Ready")
                    )
                )
            }
            list.sortedByDescending { it.timestampMs }
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "Error reading library index JSON: ${e.message}", e)
            emptyList()
        }
    }

    @Synchronized
    fun saveRun(run: VideoRun) {
        try {
            val currentRuns = getAllRuns().toMutableList()
            val existingIdx = currentRuns.indexOfFirst { it.runId == run.runId }
            if (existingIdx != -1) {
                currentRuns[existingIdx] = run
            } else {
                currentRuns.add(0, run)
            }

            val array = JSONArray()
            for (r in currentRuns) {
                val obj = JSONObject().apply {
                    put("runId", r.runId)
                    put("uriString", r.uriString)
                    put("videoTitle", r.videoTitle)
                    put("timestampMs", r.timestampMs)
                    put("detectedGender", r.detectedGender)
                    put("detectedSourceLanguage", r.detectedSourceLanguage)
                    put("segmentCount", r.segmentCount)
                    put("status", r.status)
                }
                array.put(obj)
            }
            indexFile.writeText(array.toString(2))
            DiagnosticLogger.log(TAG, "Saved video run [${run.runId}] \"${run.videoTitle}\" to library ✓")
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "Error saving run to library JSON: ${e.message}", e)
        }
    }

    @Synchronized
    fun deleteRun(runId: String) {
        try {
            val currentRuns = getAllRuns().filter { it.runId != runId }
            val array = JSONArray()
            for (r in currentRuns) {
                val obj = JSONObject().apply {
                    put("runId", r.runId)
                    put("uriString", r.uriString)
                    put("videoTitle", r.videoTitle)
                    put("timestampMs", r.timestampMs)
                    put("detectedGender", r.detectedGender)
                    put("detectedSourceLanguage", r.detectedSourceLanguage)
                    put("segmentCount", r.segmentCount)
                    put("status", r.status)
                }
                array.put(obj)
            }
            indexFile.writeText(array.toString(2))

            // Delete storage directory for runId
            val runDir = File(context.filesDir, "runs/$runId")
            if (runDir.exists()) {
                runDir.deleteRecursively()
            }
            DiagnosticLogger.log(TAG, "Deleted video run [$runId] from library ✓")
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "Error deleting run [$runId]: ${e.message}", e)
        }
    }

    fun getRun(runId: String): VideoRun? =
        getAllRuns().firstOrNull { it.runId == runId }
}
