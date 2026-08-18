package com.example.videotranslator.ui.benchmark

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.videotranslator.ai.translation.NllbTranslationEngine
import com.example.videotranslator.model.Gender
import com.example.videotranslator.model.Language
import com.example.videotranslator.tts.TtsManager
import kotlinx.coroutines.launch

// ─────────────────────────── Palette ─────────────────────────────────────────
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

data class BenchmarkResult(
    val outputText: String,
    val inferenceTimeMs: Long,
    val modelName: String,
    val sourceLanguage: Language,
    val targetLanguage: Language
)

/**
 * Interactive Standalone On-Device Translation Benchmark & Verification Sheet.
 * Allows typing ANY arbitrary text in Hindi, Telugu, or English, running real local neural inference,
 * observing millisecond execution latency, and testing offline TTS voice dubbing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationBenchmarkSheet(
    onDismiss: () -> Unit,
    translationEngine: NllbTranslationEngine,
    ttsManager: TtsManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceLang by remember { mutableStateOf(Language.HINDI) }
    var targetLang by remember { mutableStateOf(Language.ENGLISH) }
    var inputText by remember {
        mutableStateOf("मैं आज कॉलेज जा रहा हूँ क्योंकि मुझे एक महत्वपूर्ण प्रोजेक्ट जमा करना है।")
    }
    var isTranslating by remember { mutableStateOf(false) }
    var benchmarkResult by remember { mutableStateOf<BenchmarkResult?>(null) }
    var isSpeaking by remember { mutableStateOf(false) }

    val presetChips = listOf(
        "Hindi (Long + Urdu words)" to "मुझे इस काम की इजाज़त चाहिए, यह बहुत ज़रूरी सवाल है और हमारी ज़िंदगी से जुड़ा हुआ है।",
        "Hindi (College Project)" to "मैं आज कॉलेज जा रहा हूँ क्योंकि मुझे एक महत्वपूर्ण प्रोजेक्ट जमा करना है।",
        "Telugu (Going to college)" to "నేను ఈరోజు కాలేజీకి వెళ్తున్నాను, ఎందుకంటే నేను ఒక ప్రాజెక్ట్ సమర్పించాలి.",
        "English (Important update)" to "We are discussing this important topic in detail today to plan our next steps."
    )

    fun runTranslation() {
        if (inputText.isBlank()) return
        isTranslating = true
        scope.launch {
            val startTime = System.currentTimeMillis()
            val translated = translationEngine.translate(inputText, sourceLang, targetLang)
            val elapsed = System.currentTimeMillis() - startTime
            benchmarkResult = BenchmarkResult(
                outputText = translated,
                inferenceTimeMs = elapsed,
                modelName = "On-Device Neural Machine Translation Engine (NMT)",
                sourceLanguage = sourceLang,
                targetLanguage = targetLang
            )
            isTranslating = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        contentColor = Ivory
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "On-Device Translation Benchmark",
                        color = Ivory,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "100% Local Neural Inference • Zero Cloud API",
                        color = Gold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close", tint = IvoryDim)
                }
            }

            Spacer(Modifier.height(14.dp))

            // Language Selector Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Source Language
                Column(modifier = Modifier.weight(1f)) {
                    Text("FROM", color = IvoryDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    LanguageDropdown(
                        selected = sourceLang,
                        onSelect = {
                            sourceLang = it
                            if (targetLang == it) {
                                targetLang = Language.entries.first { l -> l != it }
                            }
                        }
                    )
                }

                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Gold, modifier = Modifier.padding(top = 16.dp))

                // Target Language
                Column(modifier = Modifier.weight(1f)) {
                    Text("TO", color = IvoryDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    LanguageDropdown(
                        selected = targetLang,
                        onSelect = {
                            targetLang = it
                            if (sourceLang == it) {
                                sourceLang = Language.entries.first { l -> l != it }
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Sample Preset Chips
            Text("PRESET SAMPLES", color = IvoryDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presetChips.forEach { (label, text) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(BgElevated)
                            .border(1.dp, BorderGold, RoundedCornerShape(8.dp))
                            .clickable {
                                inputText = text
                                if (label.startsWith("Telugu")) {
                                    sourceLang = Language.TELUGU
                                    targetLang = Language.ENGLISH
                                } else if (label.startsWith("English")) {
                                    sourceLang = Language.ENGLISH
                                    targetLang = Language.HINDI
                                } else {
                                    sourceLang = Language.HINDI
                                    targetLang = Language.ENGLISH
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(label, color = Ivory, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Input Text Field
            Text("INPUT TEXT (TYPE ANY SENTENCE)", color = IvoryDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                placeholder = { Text("Type arbitrary text here…", color = IvoryDim.copy(alpha = 0.5f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Gold,
                    unfocusedBorderColor = BorderGold,
                    focusedTextColor = Ivory,
                    unfocusedTextColor = Ivory,
                    cursorColor = Gold
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(14.dp))

            // Translate Action Button
            Button(
                onClick = ::runTranslation,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Gold),
                shape = RoundedCornerShape(12.dp),
                enabled = !isTranslating && inputText.isNotBlank()
            ) {
                if (isTranslating) {
                    CircularProgressIndicator(color = Color(0xFF1A1000), modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Running On-Device Neural Inference…", color = Color(0xFF1A1000), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Translate with Local Neural Model", color = Color(0xFF1A1000), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Benchmark Result Card
            benchmarkResult?.let { res ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(BgDeep)
                        .border(1.dp, SuccessGreen.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(SuccessGreen)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${res.sourceLanguage.displayName} → ${res.targetLanguage.displayName}",
                                    color = SuccessGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                "${res.inferenceTimeMs} ms",
                                color = Gold,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        Text(
                            res.outputText,
                            color = Ivory,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 22.sp
                        )

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Copy button
                            OutlinedButton(
                                onClick = {
                                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cb.setPrimaryClip(ClipData.newPlainText("Translation", res.outputText))
                                    Toast.makeText(context, "Translation copied", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderGold),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Copy", color = IvoryDim, fontSize = 11.sp)
                            }

                            Spacer(Modifier.width(8.dp))

                            // TTS Listen button
                            Button(
                                onClick = {
                                    isSpeaking = true
                                    ttsManager.speak(
                                        text = res.outputText,
                                        language = res.targetLanguage,
                                        gender = Gender.MALE,
                                        onStart = { isSpeaking = true },
                                        onDone = { isSpeaking = false },
                                        onError = { isSpeaking = false }
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = InfoBlue),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, null, tint = Ivory, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (isSpeaking) "Speaking…" else "Listen TTS", color = Ivory, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageDropdown(
    selected: Language,
    onSelect: (Language) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BgElevated)
            .border(1.dp, BorderGold, RoundedCornerShape(10.dp))
            .clickable { expanded = true }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(selected.displayName, color = Ivory, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("▼", color = Gold, fontSize = 10.sp)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(BgCard)
        ) {
            Language.entries.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(lang.displayName, color = if (lang == selected) Gold else Ivory) },
                    onClick = {
                        onSelect(lang)
                        expanded = false
                    }
                )
            }
        }
    }
}
