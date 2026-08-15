package com.example.videotranslator.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Enterprise-Grade Diagnostic Logging & Cross-Device Telemetry Subsystem.
 *
 * Records pipeline stage execution, memory usage, device specs, network model status,
 * and detailed error tracebacks to an in-memory buffer and a persistent log file (`diagnostics.log`).
 */
object DiagnosticLogger {

    private const val TAG = "DiagnosticLogger"
    private const val MAX_IN_MEMORY_LOGS = 500

    private val logQueue = ConcurrentLinkedQueue<String>()
    private val _logTextFlow = MutableStateFlow("")
    val logTextFlow: StateFlow<String> = _logTextFlow.asStateFlow()

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        try {
            val logDir = File(context.filesDir, "logs")
            if (!logDir.exists()) logDir.mkdirs()
            logFile = File(logDir, "diagnostics.log")

            logDeviceInfo(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DiagnosticLogger", e)
        }
    }

    private fun logDeviceInfo(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val availRamMb = memInfo.availMem / (1024 * 1024)
        val cores = Runtime.getRuntime().availableProcessors()

        log("SYSTEM_INFO", "==========================================================")
        log("SYSTEM_INFO", "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
        log("SYSTEM_INFO", "Android SDK: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        log("SYSTEM_INFO", "CPU Cores: $cores | RAM: ${availRamMb}MB avail / ${totalRamMb}MB total")
        log("SYSTEM_INFO", "Low Memory Mode: ${memInfo.lowMemory}")
        log("SYSTEM_INFO", "==========================================================")
    }

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val logLine = if (throwable != null) {
            "[$timestamp] [$tag] $message\nEXCEPTION: ${throwable.localizedMessage}\n${Log.getStackTraceString(throwable)}"
        } else {
            "[$timestamp] [$tag] $message"
        }

        Log.d(tag, message, throwable)

        logQueue.add(logLine)
        while (logQueue.size > MAX_IN_MEMORY_LOGS) {
            logQueue.poll()
        }

        val fullText = logQueue.joinToString("\n")
        _logTextFlow.value = fullText

        try {
            logFile?.appendText("$logLine\n")
        } catch (e: Exception) {
            Log.w(TAG, "Could not write log line to file", e)
        }
    }

    fun clearLogs() {
        logQueue.clear()
        _logTextFlow.value = ""
        try {
            logFile?.writeText("")
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear log file", e)
        }
    }

    fun getLogFile(): File? = logFile
}
