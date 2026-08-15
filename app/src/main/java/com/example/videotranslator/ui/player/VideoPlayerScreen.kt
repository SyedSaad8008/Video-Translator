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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.videotranslator.library.VideoRun
import com.example.videotranslator.model.Language
import com.example.videotranslator.model.ProcessingState
import com.example.videotranslator.tts.VoiceAvailabilityStatus
import com.example.videotranslator.util.DiagnosticLogger

// ─────────────────────────── Design Tokens ───────────────────────────────────
private val BgDeep        = Color(0xFF060610)
private val BgCard        = Color(0xFF0E0E1C)
private val BgCardLight   = Color(0xFF141428)
private val BgElevated    = Color(0xFF1A1A32)
private val Gold          = Color(0xFFC9A84C)
private val GoldLight     = Color(0xFFE5C76B)
private val GoldDim       = Color(0xFF8A6F30)
private val Ivory         = Color(0xFFF5F0E8)
private val IvoryDim      = Color(0xFFAA9F8E)
private val IvorySoft     = Color(0xFFD4CFC4)
private val MutedLabel    = Color(0xFF6B6680)
private val SuccessGreen  = Color(0xFF2ECC71)
private val ErrorRed      = Color(0xFFE74C3C)
private val WarningAmber  = Color(0xFFF39C12)
private val InfoBlue      = Color(0xFF3498DB)
private val BorderGold    = Gold.copy(alpha = 0.16f)
private val BorderGoldHi  = Gold.copy(alpha = 0.40f)

private val GoldGradient  = Brush.linearGradient(listOf(GoldLight, Gold, GoldDim))

// ─────────────────────────── Screen ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    viewModel: VideoPlayerViewModel = viewModel()
) {
    val processingState by viewModel.processingState.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.currentLanguage.collectAsStateWithLifecycle()
    val missingVoice    by viewModel.missingVoiceWarning.collectAsStateWithLifecycle()
    val voiceStatus     by viewModel.voiceAvailabilityStatus.collectAsStateWithLifecycle()
    val videoUri        by viewModel.videoUri.collectAsStateWithLifecycle()
    val libraryRuns     by viewModel.libraryRuns.collectAsStateWithLifecycle()
    val logText         by DiagnosticLogger.logTextFlow.collectAsStateWithLifecycle()

    var showLogSheet     by remember { mutableStateOf(false) }
    var showLibrarySheet by remember { mutableStateOf(false) }

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
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(14.dp))

            PremiumHeader(
                onOpenDiagnostics = { showLogSheet = true },
                onOpenLibrary = { showLibrarySheet = true }
            )

            Spacer(Modifier.height(22.dp))

            if (videoUri == null) {
                PickerCard(
                    onPick = { videoPicker.launch("video/*") },
                    onOpenLibrary = { showLibrarySheet = true },
                    libraryCount = libraryRuns.size
                )
            } else {
                VideoSurface(viewModel = viewModel)
            }

            Spacer(Modifier.height(18.dp))

            // Processing / Error states
            AnimatedContent(
                targetState = processingState,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                label = "state"
            ) { state ->
                when (state) {
                    is ProcessingState.Loading -> ProcessingCard(state)
                    is ProcessingState.Error   -> ErrorCard(state.message) { viewModel.retryPipeline() }
                    else -> {}
                }
            }

            // Ready-state controls
            AnimatedVisibility(
                visible = processingState == ProcessingState.Ready,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 },
                exit  = fadeOut(tween(200))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LanguageSelector(currentLanguage, viewModel::switchLanguage)
                    Spacer(Modifier.height(10.dp))
                    MusicHint(currentLanguage)
                    Spacer(Modifier.height(14.dp))
                    ActionButtonRow(
                        onUploadNew = { videoPicker.launch("video/*") },
                        onOpenLibrary = { showLibrarySheet = true }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Voice remediation prompt
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
                            Log.e("VideoPlayerScreen", "Could not launch TTS data install", e)
                        }
                    }
                )
            }

            Spacer(Modifier.height(32.dp))
        }

        // Bottom sheets
        if (showLogSheet) {
            DiagnosticLogSheet(
                logText = logText,
                onDismiss = { showLogSheet = false },
                onCopy = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("LinguaPlay Logs", logText))
                    Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (showLibrarySheet) {
            LibrarySheet(
                runs = libraryRuns,
                onDismiss = { showLibrarySheet = false },
                onSelectRun = { run ->
                    viewModel.loadPastRun(run)
                    showLibrarySheet = false
                },
                onDeleteRun = { viewModel.deleteRun(it) },
                onUploadNew = {
                    showLibrarySheet = false
                    videoPicker.launch("video/*")
                }
            )
        }
    }
}

// ─────────────────────────── Header ──────────────────────────────────────────

@Composable
private fun PremiumHeader(
    onOpenDiagnostics: () -> Unit,
    onOpenLibrary: () -> Unit
) {
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
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "LinguaPlay",
                    color = Ivory,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    "Offline Dual-Dub Video Translator",
                    color = Gold,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HeaderIconButton(Icons.AutoMirrored.Filled.List, "Library", onOpenLibrary)
            HeaderIconButton(Icons.Default.Info, "Diagnostics", onOpenDiagnostics)
        }
    }
}

@Composable
private fun HeaderIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BgCard)
            .border(1.dp, BorderGold, RoundedCornerShape(10.dp))
    ) {
        Icon(icon, contentDescription = desc, tint = Gold, modifier = Modifier.size(20.dp))
    }
}

// ─────────────────────────── Picker Card ─────────────────────────────────────

@Composable
private fun PickerCard(onPick: () -> Unit, onOpenLibrary: () -> Unit, libraryCount: Int) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        SurfaceCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon circle
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(Gold.copy(alpha = 0.15f), Color.Transparent)
                            )
                        )
                        .border(1.5.dp, BorderGoldHi, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Select Video",
                        tint = Gold,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    "Select Video from Device",
                    color = Ivory,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    "Translates Hindi dialogue → English & Telugu\nPreserves background music • Gender-matched voice",
                    color = IvoryDim,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        }

        if (libraryCount > 0) {
            Spacer(Modifier.height(12.dp))
            SurfaceCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenLibrary() }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.List, null, tint = Gold, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Your Past Translations",
                                color = Ivory,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "$libraryCount saved run${if (libraryCount != 1) "s" else ""}",
                                color = IvoryDim,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Text("→", color = Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─────────────────────────── Video Surface ───────────────────────────────────

@Composable
private fun VideoSurface(viewModel: VideoPlayerViewModel) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, BorderGoldHi, RoundedCornerShape(14.dp))
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

// ─────────────────────────── Processing Card ─────────────────────────────────

@Composable
private fun ProcessingCard(state: ProcessingState.Loading) {
    SurfaceCard(borderColor = InfoBlue.copy(alpha = 0.25f)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    progress = { state.progress.coerceAtLeast(0f) },
                    modifier = Modifier.size(40.dp),
                    color = Gold,
                    trackColor = BgElevated,
                    strokeWidth = 3.dp
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.step,
                        color = Ivory,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(state.progress * 100).toInt()}% complete",
                        color = Gold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { state.progress.coerceAtLeast(0f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Gold,
                trackColor = BgElevated
            )
        }
    }
}

// ─────────────────────────── Error Card ──────────────────────────────────────

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    SurfaceCard(borderColor = ErrorRed.copy(alpha = 0.35f)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(ErrorRed.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Warning, null, tint = ErrorRed, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Processing Failed",
                        color = ErrorRed,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        message,
                        color = IvoryDim,
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 17.sp
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Refresh, null, tint = ErrorRed, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Retry Pipeline", color = ErrorRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ─────────────────────────── Language Selector ───────────────────────────────

@Composable
private fun LanguageSelector(
    currentLanguage: Language,
    onLanguageSelected: (Language) -> Unit
) {
    SurfaceCard {
        Text(
            "SELECT AUDIO TRANSLATION",
            color = MutedLabel,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
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
                        .background(
                            if (isSelected) GoldGradient
                            else Brush.linearGradient(listOf(BgElevated, BgElevated))
                        )
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
                        lang.displayName,
                        color = if (isSelected) Color(0xFF1A1000) else Ivory,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

// ─────────────────────────── Music Hint ──────────────────────────────────────

@Composable
private fun MusicHint(language: Language) {
    if (language == Language.HINDI) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SuccessGreen.copy(alpha = 0.06f))
            .border(1.dp, SuccessGreen.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("♪", fontSize = 14.sp, color = SuccessGreen)
            Spacer(Modifier.width(8.dp))
            Text(
                "Background music preserved — translated voice overlaid",
                color = SuccessGreen.copy(alpha = 0.8f),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ─────────────────────────── Action Buttons ──────────────────────────────────

@Composable
private fun ActionButtonRow(onUploadNew: () -> Unit, onOpenLibrary: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ActionButton(
            modifier = Modifier.weight(1f),
            label = "Upload New Video",
            accent = false,
            onClick = onUploadNew
        )
        ActionButton(
            modifier = Modifier.weight(1f),
            label = "Open Library",
            accent = true,
            icon = { Icon(Icons.AutoMirrored.Filled.List, null, tint = Gold, modifier = Modifier.size(16.dp)) },
            onClick = onOpenLibrary
        )
    }
}

@Composable
private fun ActionButton(
    modifier: Modifier = Modifier,
    label: String,
    accent: Boolean,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (accent) BgElevated else BgCard)
            .border(1.dp, if (accent) BorderGoldHi else BorderGold, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            if (icon != null) Spacer(Modifier.width(6.dp))
            Text(
                label,
                color = if (accent) Gold else IvoryDim,
                fontSize = 12.5.sp,
                fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = 0.8.sp
            )
        }
    }
}

// ─────────────────────────── Voice Remediation Card ──────────────────────────

@Composable
private fun VoiceRemediationCard(
    status: VoiceAvailabilityStatus?,
    language: Language,
    onInstallData: () -> Unit
) {
    val messageText = status?.message ?: "No ${language.displayName} TTS voice installed."
    val isSingleVoice = status?.isSingleVoiceOnly ?: true

    SurfaceCard(borderColor = WarningAmber.copy(alpha = 0.35f)) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(WarningAmber.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Warning, null, tint = WarningAmber, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isSingleVoice) "Limited Voice Options" else "Missing Voice Data",
                        color = WarningAmber,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$messageText You can install additional Google TTS voice packs to get distinct male & female voices.",
                        color = IvorySoft,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onInstallData,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Install Voice Data", color = Color(0xFF1A1000), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─────────────────────────── Library Bottom Sheet ────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySheet(
    runs: List<VideoRun>,
    onDismiss: () -> Unit,
    onSelectRun: (VideoRun) -> Unit,
    onDeleteRun: (String) -> Unit,
    onUploadNew: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        contentColor = Ivory
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Your Translations", color = Ivory, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${runs.size} saved run${if (runs.size != 1) "s" else ""}",
                        color = IvoryDim,
                        fontSize = 12.sp
                    )
                }
                Button(
                    onClick = onUploadNew,
                    colors = ButtonDefaults.buttonColors(containerColor = Gold),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Add, null, tint = Color(0xFF1A1000), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New Run", color = Color(0xFF1A1000), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(18.dp))

            if (runs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.AutoMirrored.Filled.List, null, tint = MutedLabel, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No translations yet", color = IvoryDim, fontSize = 14.sp)
                        Text("Upload a video to get started", color = MutedLabel, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(runs, key = { it.runId }) { run ->
                        LibraryRunCard(
                            run = run,
                            onPlay = { onSelectRun(run) },
                            onDelete = { onDeleteRun(run.runId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRunCard(run: VideoRun, onPlay: () -> Unit, onDelete: () -> Unit) {
    val statusColor = when (run.status) {
        "Ready" -> SuccessGreen
        "Error" -> ErrorRed
        else    -> WarningAmber
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgElevated)
            .border(1.dp, BorderGold, RoundedCornerShape(12.dp))
            .clickable { onPlay() }
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    run.videoTitle,
                    color = Ivory,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetadataPill(run.formattedDate)
                    MetadataPill("${run.segmentCount} segs")
                    MetadataPill(
                        if (run.detectedGender == "FEMALE") "♀ Female" else "♂ Male"
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.PlayArrow, "Play", tint = SuccessGreen, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, "Delete", tint = ErrorRed.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MetadataPill(text: String) {
    Text(
        text,
        color = MutedLabel,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BgCard)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

// ─────────────────────────── Diagnostic Log Sheet ────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticLogSheet(
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Pipeline Diagnostics", color = Ivory, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("System & processing logs", color = IvoryDim, fontSize = 12.sp)
                }
                Button(
                    onClick = onCopy,
                    colors = ButtonDefaults.buttonColors(containerColor = Gold),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("Copy", color = Color(0xFF1A1000), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF050508))
                    .border(1.dp, BorderGold, RoundedCornerShape(10.dp))
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    logText.ifBlank { "No logs recorded yet." },
                    color = Color(0xFF00FF66),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

// ─────────────────────────── Surface Card ────────────────────────────────────

@Composable
private fun SurfaceCard(
    modifier: Modifier = Modifier,
    borderColor: Color = BorderGold,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(BgCard)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(16.dp)
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
                colors = listOf(Gold.copy(alpha = 0.04f), Color.Transparent),
                center = Offset(size.width * 0.5f, 0f),
                radius = size.width * 0.7f
            ),
            radius = size.width * 0.7f,
            center = Offset(size.width * 0.5f, 0f)
        )
    }
}
