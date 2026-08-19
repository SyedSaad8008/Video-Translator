package com.example.videotranslator.ui.benchmark

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.videotranslator.ai.benchmark.ASRResult
import com.example.videotranslator.ai.benchmark.AsrBenchmarkRunner
import com.example.videotranslator.ai.benchmark.ModelBenchmarkComparison
import com.example.videotranslator.model.Language
import kotlinx.coroutines.launch
import java.io.File

private val BgDeep       = Color(0xFF060610)
private val BgCard       = Color(0xFF0E0E1C)
private val BgElevated   = Color(0xFF1A1A32)
private val Gold         = Color(0xFFC9A84C)
private val GoldLight    = Color(0xFFE5C76B)
private val GoldDim      = Color(0xFF8A6F30)
private val Ivory        = Color(0xFFF5F0E8)
private val IvoryDim     = Color(0xFFAA9F8E)
private val SuccessGreen = Color(0xFF2ECC71)
private val InfoBlue     = Color(0xFF3498DB)
private val BorderGold   = Gold.copy(alpha = 0.20f)
private val BorderGoldHi = Gold.copy(alpha = 0.50f)
private val GoldGradient = Brush.linearGradient(listOf(GoldLight, Gold, GoldDim))

/**
 * On-Device Local ASR Multi-Model Benchmarking UI Dashboard.
 * Runs Model A (Kaldi/Vosk), Model B (Indic-Conformer), and Model C (Whisper) on the exact same audio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsrBenchmarkSheet(
    onDismiss: () -> Unit,
    currentVideoName: String = "Uploaded Video"
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { AsrBenchmarkRunner(context) }

    var selectedLang by remember { mutableStateOf(Language.TELUGU) }
    var isRunning by remember { mutableStateOf(false) }
    var benchmarkComparison by remember { mutableStateOf<ModelBenchmarkComparison?>(null) }
    var statusText by remember { mutableStateOf("Ready to benchmark 3 local ASR models on audio.") }

    fun startBenchmark() {
        val rawWav = File(context.filesDir, "original_extracted.wav")
        if (!rawWav.exists() || rawWav.length() <= 44L) {
            Toast.makeText(context, "No extracted audio found. Please upload a video first.", Toast.LENGTH_LONG).show()
            return
        }

        isRunning = true
        statusText = "Benchmarking Model A (Kaldi/Vosk), Model B (Indic-Conformer), Model C (Whisper)…"
        scope.launch {
            try {
                val bytes = rawWav.readBytes()
                val pcmBuffer = java.nio.ByteBuffer.wrap(bytes, 44, bytes.size - 44)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                val pcm = ShortArray(pcmBuffer.remaining()).also { pcmBuffer.get(it) }

                val result = runner.runBenchmark(pcm, selectedLang, currentVideoName)
                benchmarkComparison = result
                statusText = "Benchmark complete! Best Model: ${result.bestModelName}"
            } catch (e: Exception) {
                statusText = "Benchmark error: ${e.message}"
            } finally {
                isRunning = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgDeep,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Gold.copy(alpha = 0.40f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "ASR Model Benchmark",
                        color = Gold,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        "Compare 3 Local Engines on Same Audio",
                        color = IvoryDim,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = IvoryDim)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Language Selector
            Text("SELECT SOURCE LANGUAGE", color = GoldLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Language.entries.forEach { lang ->
                    val isSelected = selectedLang == lang
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) GoldGradient else Brush.linearGradient(listOf(BgElevated, BgElevated)))
                            .border(1.dp, if (isSelected) Gold else BorderGold, RoundedCornerShape(10.dp))
                            .clickable { selectedLang = lang }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            lang.displayName,
                            color = if (isSelected) Color(0xFF1A1000) else Ivory,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action Button
            Button(
                onClick = ::startBenchmark,
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1A1000))
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFF1A1000), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Running Benchmark across 3 Models…", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Run 3-Model ASR Benchmark", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(statusText, color = IvoryDim, fontSize = 11.5.sp)

            Spacer(Modifier.height(16.dp))

            // Results Display
            benchmarkComparison?.let { comp ->
                Text("BENCHMARK COMPARISON RESULTS", color = GoldLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                comp.results.forEachIndexed { index, res ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = BgCard),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (res.modelName.contains(comp.bestModelName)) SuccessGreen.copy(alpha = 0.6f) else BorderGold
                        )
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Model ${index + 1}: ${res.modelFamily}", color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("${"%.1f".format(res.qualityScore)} / 100", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Text(res.modelName, color = IvoryDim, fontSize = 11.sp)
                            Spacer(Modifier.height(8.dp))

                            Text("Transcript:", color = GoldLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (res.transcript.isNotBlank()) "\"${res.transcript}\"" else "(No text recognized)",
                                color = Ivory,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                            Spacer(Modifier.height(8.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Words: ${res.wordCount}", color = IvoryDim, fontSize = 11.sp)
                                Text("Coverage: ${"%.1f".format(res.speechCoveragePercent)}%", color = IvoryDim, fontSize = 11.sp)
                                Text("Time: ${res.processingTimeMs}ms", color = IvoryDim, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                // Best Model Summary
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.12f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.40f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("BEST MODEL RECOMMENDATION", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(comp.bestModelName, color = Ivory, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(comp.selectionRationale, color = IvoryDim, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
