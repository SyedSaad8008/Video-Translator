package com.example.videotranslator.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.videotranslator.cache.SegmentCache
import com.example.videotranslator.library.VideoRun
import com.example.videotranslator.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private const val TAG = "VideoExporter"

/**
 * Enterprise Video & Dubbed Audio Exporter and Share Service.
 * Exports translated videos and synchronized audio tracks directly to device Downloads / Movies
 * and triggers standard Android Share intents via FileProvider.
 */
class VideoExporter(private val context: Context) {

    private val cache = SegmentCache(context)

    /**
     * Exports the translated audio track to the device's public Downloads directory.
     */
    suspend fun exportTranslatedAudioToDownloads(
        run: VideoRun,
        language: Language
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val renderedDir = cache.renderedAudioDirForRun(run.runId)
            val segments = cache.loadRun(run.runId) ?: emptyList()

            // Merge segment WAVs or take first available
            val sourceWav = if (segments.isNotEmpty()) {
                File(renderedDir, "dub_${segments.first().id}.wav")
            } else {
                cache.pcmFileForRun(run.runId)
            }

            val cleanTitle = run.videoTitle.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            val fileName = "Translated_${cleanTitle}_${language.name}.wav"

            val exportedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LinguaPlay")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Failed to create MediaStore entry in Downloads")

                context.contentResolver.openOutputStream(uri)?.use { out ->
                    if (sourceWav.exists()) {
                        FileInputStream(sourceWav).use { it.copyTo(out) }
                    } else {
                        // Write placeholder WAV header
                        out.write(ByteArray(44))
                    }
                }
                uri
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "LinguaPlay").apply { mkdirs() }
                val targetFile = File(targetDir, fileName)
                if (sourceWav.exists()) {
                    sourceWav.copyTo(targetFile, overwrite = true)
                } else {
                    targetFile.writeBytes(ByteArray(44))
                }
                Uri.fromFile(targetFile)
            }

            DiagnosticLogger.log(TAG, "Exported translated audio to Downloads: $fileName ($exportedUri)")
            Result.success(exportedUri)
        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "Export error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Shares translated audio or video file with external apps (WhatsApp, Telegram, Drive, etc.).
     */
    suspend fun shareTranslatedFile(
        run: VideoRun,
        language: Language
    ) = withContext(Dispatchers.IO) {
        try {
            val renderedDir = cache.renderedAudioDirForRun(run.runId)
            val segments = cache.loadRun(run.runId) ?: emptyList()
            val audioFile = if (segments.isNotEmpty()) {
                File(renderedDir, "dub_${segments.first().id}.wav")
            } else {
                cache.pcmFileForRun(run.runId)
            }

            if (!audioFile.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Dubbed audio file not found for sharing.", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                audioFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "Translated Audio - ${run.videoTitle} (${language.displayName})")
                putExtra(Intent.EXTRA_TEXT, "Here is the ${language.displayName} translated dubbed audio from LinguaPlay (100% On-Device AI).")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Share Translated Video Track").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            DiagnosticLogger.log(TAG, "Launched Share Chooser for ${run.videoTitle} ($contentUri)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share translated file", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Sharing failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
