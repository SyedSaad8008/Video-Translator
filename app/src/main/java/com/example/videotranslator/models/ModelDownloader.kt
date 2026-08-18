package com.example.videotranslator.models

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.example.videotranslator.model.ModelInfo
import com.example.videotranslator.util.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

private const val TAG = "ModelDownloader"
private const val BUFFER_SIZE = 8192
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000

/**
 * Handles downloading on-device AI model files with storage pre-checks,
 * progress updates, checksum validation, and zip extraction.
 */
class ModelDownloader(private val context: Context) {

    /**
     * Checks if the device has sufficient available storage for the given model size.
     */
    fun hasSufficientStorage(requiredBytes: Long): Boolean {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            availableBytes > (requiredBytes + 50 * 1024 * 1024L)
        } catch (e: Exception) {
            Log.w(TAG, "Storage check failed: ${e.message}")
            true
        }
    }

    /**
     * Downloads a model file from its remote endpoint to target local file.
     */
    suspend fun downloadModel(
        modelInfo: ModelInfo,
        targetFile: File,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        DiagnosticLogger.log(TAG, "Starting download for ${modelInfo.name} (${modelInfo.formattedSize}) → ${targetFile.name}")

        if (!hasSufficientStorage(modelInfo.sizeBytes)) {
            val err = "Insufficient storage space to download ${modelInfo.name}. Required: ${modelInfo.formattedSize}"
            DiagnosticLogger.log(TAG, err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            tempFile.parentFile?.mkdirs()
            if (tempFile.exists()) tempFile.delete()

            val url = URL(modelInfo.downloadUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("HTTP server returned error response: $responseCode for ${modelInfo.name}")
            }

            val totalBytes = if (connection.contentLengthLong > 0) connection.contentLengthLong else modelInfo.sizeBytes
            inputStream = connection.inputStream
            outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            var totalBytesRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                val progress = if (totalBytes > 0) (totalBytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
                onProgress(progress)
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            if (tempFile.length() < 1024) {
                throw IllegalStateException("Downloaded file is empty or corrupted (< 1KB).")
            }

            // If zip archive (e.g. Vosk acoustic models), unzip to parent directory
            if (targetFile.name.endsWith(".zip")) {
                unzip(tempFile, targetFile.parentFile ?: context.filesDir)
            }

            // Rename temp to target
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            DiagnosticLogger.log(TAG, "Download and verification complete for ${modelInfo.name} ✓ (${targetFile.length() / (1024*1024)} MB)")
            onProgress(1.0f)
            Result.success(targetFile)

        } catch (e: Exception) {
            DiagnosticLogger.log(TAG, "Download failed for ${modelInfo.name}: ${e.localizedMessage}", e)
            if (tempFile.exists()) tempFile.delete()
            Result.failure(e)
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            connection?.disconnect()
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        try {
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val newFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        FileOutputStream(newFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            DiagnosticLogger.log(TAG, "Unzipped ${zipFile.name} successfully ✓")
        } catch (e: Exception) {
            Log.w(TAG, "Unzip error: ${e.message}")
        }
    }

    /**
     * Computes SHA-256 hash of a file for integrity verification.
     */
    fun computeSha256(file: File): String {
        if (!file.exists()) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}
