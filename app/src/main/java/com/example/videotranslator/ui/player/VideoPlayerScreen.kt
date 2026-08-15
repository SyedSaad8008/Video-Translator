package com.example.videotranslator.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.example.videotranslator.R
import com.example.videotranslator.model.Language
import com.example.videotranslator.model.ProcessingState
import com.example.videotranslator.tts.VoiceAvailabilityStatus
import com.example.videotranslator.util.DiagnosticLogger

// ─────────────────────────── Design tokens ───────────────────────────────────
private val BgDeep        = Color(0xFF080810)
private val BgCard        = Color(0xFF0F0F1E)
private val BgCardLight   = Color(0xFF141428)
private val Gold          = Color(0xFFC9A84C)
private val GoldLight     = Color(0xFFE5C76B)
private val GoldDim       = Color(0xFF8A6F30)
private val Ivory         = Color(0xFFF5F0E8)
private val IvoryDim      = Color(0xFFAA9F8E)
private val MutedWhite    = Color(0xFF6B6680)
private val SuccessGreen  = Color(0xFF2ECC71)
private val ErrorRed      = Color(0xFFE74C3C)
private val BorderGold    = Gold.copy(alpha = 0.18f)
private val BorderGoldHi  = Gold.copy(alpha = 0.45f)

private val GoldGradient = Brush.linearGradient(listOf(GoldLight, Gold, GoldDim))

// ─────────────────────────── Screen ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    viewModel: VideoPlayerViewModel = viewModel()
) {
    val processingState by viewModel.processingState.collectAsStateWithLifecycle()
    val currentLanguage  by viewModel.currentLanguage.collectAsStateWithLifecycle()
    val missingVoice     by viewModel.missingVoiceWarning.collectAsStateWithLifecycle()
    val voiceStatus      by viewModel.voiceAvailabilityStatus.collectAsStateWithLifecycle()
    val videoUri         by viewModel.videoUri.collectAsStateWithLifecycle()
    val logText          by DiagnosticLogger.logTextFlow.collectAsStateWithLifecycle()
    
    var showLogSheet by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val context        = LocalContext.current

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.onVideoPicked(it) } }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.exoPlayer.pause()
            if (event == Lifecycle.Event.ON_RESUME) viewModel.recheckVoiceAvailability()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
    ) {
        AmbientGlow()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))

            PremiumHeader(onOpenDiagnostics = { showLogSheet = true })

            Spacer(Modifier.height(20.dp))

            if (videoUri == null) {
                PickerCard(onPick = { videoPicker.launch("video/*") })
            } else {
                VideoSurface(viewModel = viewModel)
            }

            Spacer(Modifier.height(18.dp))

            AnimatedContent(
                targetState = processingState,
                transitionSpec = {
                    fadeIn(tween(300)) togetherWith fadeOut(tween(200))
                },
                label = "state"
            ) { state ->
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    when (state) {
                        is ProcessingState.Loading -> ProcessingCard(state)
                        is ProcessingState.Error   -> ErrorCard(state.message) { viewModel.retryPipeline() }
                        else -> {}
                    }
                }
            }

            AnimatedVisibility(
                visible = processingState == ProcessingState.Ready,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 2 },
                exit  = fadeOut(tween(200))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LanguageSelector(currentLanguage, viewModel::switchLanguage)
                    Spacer(Modifier.height(10.dp))
                    MusicHint(currentLanguage)
                    Spacer(Modifier.height(12.dp))
                    ChangeVideoButton { videoPicker.launch("video/*") }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Active Remediation Missing Voice Prompt
            AnimatedVisibility(visible = missingVoice && currentLanguage != Language.HINDI) {
                VoiceRemediationCard(
                    status = voiceStatus,
                    language = currentLanguage,
                    onInstallData = {
                        try {
                            val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e("VideoPlayerScreen", "Could not launch ACTION_INSTALL_TTS_DATA intent", e)
                        }
                    }
                )
            }

            Spacer(Modifier.height(32.dp))
        }

        if (showLogSheet) {
            DiagnosticLogBottomSheet(
                logText = logText,
                onDismiss = { showLogSheet = false },
                onCopy = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("LinguaPlay Logs", logText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Diagnostic logs copied to clipboard!", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

// ─────────────────────────── Header ───────────────────────────────────────────

@Composable
private fun PremiumHeader(onOpenDiagnostics: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.ic_app_logo),
                contentDescription = "LinguaPlay Logo",
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "LinguaPlay",
                    color = Ivory,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "Offline Dual-Dub Video Translator",
                    color = Gold,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp
                )
            }
        }

        IconButton(
            onClick = onOpenDiagnostics,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(BgCard)
                .border(1.dp, BorderGold, RoundedCornerShape(10.dp))
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = "System Diagnostics",
                tint = Gold
            )
        }
    }
}

// ─────────────────────────── Picker Card ──────────────────────────────────────

@Composable
private fun PickerCard(onPick: () -> Unit) {
    LuxCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { onPick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(BgCardLight)
                    .border(1.dp, BorderGoldHi, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("🎬", fontSize = 32.sp)
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = "Select Video from Device",
                color = Ivory,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Translates Hindi dialogue → English & Telugu\nPreserves background music & lip-sync",
                color = IvoryDim,
                fontSize = 12.5.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        }
    }
}

// ─────────────────────────── Video Surface ────────────────────────────────────

@Composable
private fun VideoSurface(viewModel: VideoPlayerViewModel) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, BorderGoldHi, RoundedCornerShape(16.dp))
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = viewModel.exoPlayer
                    useController = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ─────────────────────────── Processing Card ──────────────────────────────────

@Composable
private fun ProcessingCard(state: ProcessingState.Loading) {
    LuxCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            CircularProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.size(36.dp),
                color = Gold,
                trackColor = BgCardLight,
                strokeWidth = 3.dp
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.step,
                    color = Ivory,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${(state.progress * 100).toInt()}% complete",
                    color = IvoryDim,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    LuxCard(borderColor = ErrorRed.copy(alpha = 0.4f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Error",
                tint = ErrorRed,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Pipeline Processing Error", color = ErrorRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(message, color = IvoryDim, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = Gold)
            }
        }
    }
}

@Composable
private fun LanguageSelector(
    currentLanguage: Language,
    onLanguageSelected: (Language) -> Unit
) {
    LuxCard {
        Text(
            "SELECT AUDIO TRANSLATION",
            color = MutedWhite,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Language.entries.forEach { lang ->
                val isSelected = currentLanguage == lang
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) GoldGradient else Brush.linearGradient(listOf(BgCardLight, BgCardLight)))
                        .border(
                            1.dp,
                            if (isSelected) Gold else BorderGold,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onLanguageSelected(lang) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = lang.displayName,
                        color = if (isSelected) Color(0xFF1A1000) else Ivory,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicHint(language: Language) {
    if (language == Language.HINDI) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BgCard)
            .border(1.dp, SuccessGreen.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            "♪  Background music preserved — translated voice overlaid",
            color = SuccessGreen.copy(alpha = 0.75f),
            fontSize = 11.sp,
            letterSpacing = 0.2.sp
        )
    }
}

@Composable
private fun ChangeVideoButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BgCard)
            .border(1.dp, BorderGold, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Change Video",
            color = IvoryDim,
            fontSize = 13.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.Light
        )
    }
}

/** Active Remediation Prompt for Missing or Single TTS Voices */
@Composable
private fun VoiceRemediationCard(
    status: VoiceAvailabilityStatus?,
    language: Language,
    onInstallData: () -> Unit
) {
    val messageText = status?.message ?: "No ${language.displayName} TTS voice installed."
    val isSingleVoice = status?.isSingleVoiceOnly ?: true

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1F1400))
            .border(1.dp, Gold.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Text("⚠", fontSize = 16.sp, color = Gold)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isSingleVoice) "Limited Gender Voice Options" else "Missing TTS Voice Data",
                        color = Gold,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$messageText Installing official Google TTS voice packs adds distinct male & female voices (if available for this locale).",
                        color = IvoryDim,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onInstallData,
                    colors = ButtonDefaults.buttonColors(containerColor = Gold),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Install Voice Data", color = Color(0xFF1A1000), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─────────────────────────── Diagnostic Log Modal Sheet ─────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticLogBottomSheet(
    logText: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        contentColor = Ivory
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("System Diagnostics & Pipeline Logs", color = Gold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Button(
                    onClick = onCopy,
                    colors = ButtonDefaults.buttonColors(containerColor = Gold),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Copy Logs", color = Color(0xFF1A1000), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .border(1.dp, BorderGold, RoundedCornerShape(8.dp))
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = logText.ifBlank { "No diagnostic logs recorded yet." },
                    color = Color(0xFF00FF66),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─────────────────────────── Lux Card Container ───────────────────────────────

@Composable
private fun LuxCard(
    modifier: Modifier = Modifier,
    borderColor: Color = BorderGold,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(BgCard)
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Column(content = content)
    }
}

// ─────────────────────────── Ambient Glow ────────────────────────────────────

@Composable
private fun AmbientGlow() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Gold.copy(alpha = 0.05f), Color.Transparent),
                center = Offset(size.width * 0.5f, 0f),
                radius = size.width * 0.8f
            ),
            radius = size.width * 0.8f,
            center = Offset(size.width * 0.5f, 0f)
        )
    }
}
