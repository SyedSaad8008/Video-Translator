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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import com.example.videotranslator.models.ModelInstallProgress
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
    val processingState    by viewModel.processingState.collectAsStateWithLifecycle()
    val currentLanguage    by viewModel.currentLanguage.collectAsStateWithLifecycle()
    val missingVoice       by viewModel.missingVoiceWarning.collectAsStateWithLifecycle()
    val voiceStatus        by viewModel.voiceAvailabilityStatus.collectAsStateWithLifecycle()
    val videoUri           by viewModel.videoUri.collectAsStateWithLifecycle()
    val currentRunId       by viewModel.currentRunId.collectAsStateWithLifecycle()
    val libraryRuns        by viewModel.libraryRuns.collectAsStateWithLifecycle()
    val logText            by DiagnosticLogger.logTextFlow.collectAsStateWithLifecycle()
    val detectedSourceLang by viewModel.detectedSourceLanguage.collectAsStateWithLifecycle()
    val manualSourceLang   by viewModel.manualSourceLanguage.collectAsStateWithLifecycle()
    val installProgress    by viewModel.installProgress.collectAsStateWithLifecycle()

    var showLogSheet      by remember { mutableStateOf(false) }
    var showLibrarySheet  by remember { mutableStateOf(false) }
    var detectionMode     by remember { mutableStateOf(if (manualSourceLang != null) "manual" else "auto") }
    var manualLanguage    by remember { mutableStateOf(manualSourceLang ?: Language.HINDI) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val context        = LocalContext.current

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            if (detectionMode == "manual") {
                viewModel.setManualSourceLanguage(manualLanguage)
            } else {
                viewModel.setManualSourceLanguage(null)
            }
            viewModel.onVideoPicked(it)
        }
    }

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

            // Clean Header
            CleanHeader(
                onOpenLibrary = { showLibrarySheet = true },
                onOpenDiagnostics = { showLogSheet = true }
            )

            Spacer(Modifier.height(16.dp))

            if (videoUri == null) {
                CleanPickerCard(
                    onPick = { videoPicker.launch("video/*") },
                    onOpenLibrary = { showLibrarySheet = true },
                    libraryCount = libraryRuns.size,
                    installProgress = installProgress,
                    detectionMode = detectionMode,
                    onDetectionModeChanged = { mode ->
                        detectionMode = mode
                        if (mode == "manual") {
                            viewModel.setManualSourceLanguage(manualLanguage)
                        } else {
                            viewModel.setManualSourceLanguage(null)
                        }
                    },
                    manualLanguage = manualLanguage,
                    onManualLanguageChanged = { lang ->
                        manualLanguage = lang
                        viewModel.setManualSourceLanguage(lang)
                    }
                )
            } else {
                VideoSurface(viewModel = viewModel)
            }

            Spacer(Modifier.height(16.dp))

            // Processing state card
            AnimatedVisibility(
                visible = processingState is ProcessingState.Loading,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit  = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
                (processingState as? ProcessingState.Loading)?.let { loading ->
                    CleanProcessingCard(state = loading, onCancel = viewModel::cancelPipeline)
                }
            }

            // Error state card
            AnimatedVisibility(
                visible = processingState is ProcessingState.Error,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit  = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
                (processingState as? ProcessingState.Error)?.let { err ->
                    ErrorCard(message = err.message, onRetry = viewModel::retryPipeline)
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
                    LanguageSelector(
                        currentLanguage    = currentLanguage,
                        sourceLanguage     = detectedSourceLang,
                        onLanguageSelected = viewModel::switchLanguage
                    )
                    Spacer(Modifier.height(10.dp))
                    MusicHint(currentLanguage, detectedSourceLang)
                    Spacer(Modifier.height(14.dp))

                    val activeRun = libraryRuns.find { it.runId == currentRunId }
                        ?: VideoRun(
                            runId = currentRunId ?: "current_run",
                            uriString = videoUri?.toString() ?: "",
                            videoTitle = videoUri?.lastPathSegment ?: "Translated Video",
                            detectedSourceLanguage = detectedSourceLang.name
                        )

                    ExportShareRow(
                        run = activeRun,
                        currentLanguage = currentLanguage,
                        onExport = { viewModel.exportRun(activeRun, currentLanguage) },
                        onShare = { viewModel.shareRun(activeRun, currentLanguage) }
                    )

                    Spacer(Modifier.height(12.dp))

                    ActionButtonRow(
                        onUploadNew = { videoPicker.launch("video/*") },
                        onOpenLibrary = { showLibrarySheet = true }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Voice remediation prompt
            AnimatedVisibility(visible = missingVoice && currentLanguage != detectedSourceLang) {
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

        // Diagnostic & Library Bottom Sheets
        if (showLogSheet) {
            DiagnosticLogSheet(
                logText = logText,
                onDismiss = { showLogSheet = false },
                onClear = { DiagnosticLogger.clearLogs() },
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
                onExportRun = { run ->
                    viewModel.exportRun(run, currentLanguage)
                },
                onShareRun = { run ->
                    viewModel.shareRun(run, currentLanguage)
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

// ─────────────────────────── Clean Header ────────────────────────────────────

@Composable
private fun CleanHeader(
    onOpenLibrary: () -> Unit,
    onOpenDiagnostics: () -> Unit
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
                    .size(40.dp)
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
                    "100% Offline AI Video Translation",
                    color = Gold,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.8.sp
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BgCard)
            .border(1.dp, BorderGold, RoundedCornerShape(10.dp))
    ) {
        Icon(icon, contentDescription = desc, tint = Gold, modifier = Modifier.size(18.dp))
    }
}

// ─────────────────────────── Clean Picker Card ───────────────────────────────

@Composable
private fun CleanPickerCard(
    onPick: () -> Unit,
    onOpenLibrary: () -> Unit,
    libraryCount: Int,
    installProgress: ModelInstallProgress,
    detectionMode: String,
    onDetectionModeChanged: (String) -> Unit,
    manualLanguage: Language,
    onManualLanguageChanged: (Language) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        // ── 0. AI Model Installation & Readiness Card ──
        SurfaceCard(
            borderColor = if (installProgress.isComplete) SuccessGreen.copy(alpha = 0.35f) else Gold.copy(alpha = 0.35f)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (installProgress.isComplete) SuccessGreen else Gold)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (installProgress.isComplete) "100% On-Device Neural AI Engines Ready"
                            else "Installing On-Device Neural Models (${installProgress.installedCount}/${installProgress.totalCount})",
                            color = if (installProgress.isComplete) SuccessGreen else GoldLight,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!installProgress.isComplete) {
                        Text(
                            "${(installProgress.currentProgress * 100).toInt()}%",
                            color = Gold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (!installProgress.isComplete) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { installProgress.currentProgress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Gold,
                        trackColor = BgElevated
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        installProgress.statusMessage,
                        color = IvoryDim,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── 1. Detection Mode Toggle ──
        SurfaceCard {
            Text(
                "SPOKEN LANGUAGE MODE",
                color = MutedLabel,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("auto" to "Automatic AI", "manual" to "Manual Selection").forEach { (mode, label) ->
                    val isSelected = detectionMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) GoldGradient
                                else Brush.linearGradient(listOf(BgElevated, BgElevated))
                            )
                            .border(
                                1.dp,
                                if (isSelected) Gold else BorderGold,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onDetectionModeChanged(mode) }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (isSelected) Color(0xFF1A1000) else Ivory,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (detectionMode == "auto") {
                Text(
                    "AI automatically detects whether dialogue is Hindi, English, or Telugu.",
                    color = IvoryDim,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Language.entries.forEach { lang ->
                        val isSelected = manualLanguage == lang
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) Brush.linearGradient(
                                        listOf(SuccessGreen.copy(alpha = 0.18f), SuccessGreen.copy(alpha = 0.08f))
                                    ) else Brush.linearGradient(listOf(BgElevated, BgElevated))
                                )
                                .border(
                                    1.2.dp,
                                    if (isSelected) SuccessGreen else BorderGold,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onManualLanguageChanged(lang) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                lang.displayName,
                                color = if (isSelected) SuccessGreen else Ivory,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── 2. Primary Video Picker Hero ──
        SurfaceCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Gold.copy(alpha = 0.18f), Color.Transparent)))
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

                Spacer(Modifier.height(16.dp))

                Text(
                    "Select Video from Device",
                    color = Ivory,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    "Translates into English & Telugu with gender-matched voice dubbing\n100% On-Device • Zero Cloud • Complete Privacy",
                    color = IvoryDim,
                    fontSize = 11.5.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp
                )
            }
        }

        // ── 3. Past Translations Quick Access ──
        if (libraryCount > 0) {
            Spacer(Modifier.height(10.dp))
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
                            Text("Saved Translations & Storage", color = Ivory, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("$libraryCount video run${if (libraryCount != 1) "s" else ""}", color = IvoryDim, fontSize = 11.sp)
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

// ─────────────────────────── Clean Processing Card ───────────────────────────

@Composable
private fun CleanProcessingCard(
    state: ProcessingState.Loading,
    onCancel: () -> Unit
) {
    SurfaceCard(borderColor = InfoBlue.copy(alpha = 0.35f)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    CircularProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.size(36.dp),
                        color = Gold,
                        trackColor = BgElevated,
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            "Stage ${state.currentStage}/${state.totalStages}",
                            color = Gold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            state.step,
                            color = Ivory,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = IvoryDim, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.5.dp)
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
    sourceLanguage: Language,
    onLanguageSelected: (Language) -> Unit
) {
    SurfaceCard {
        Text(
            "SELECT AUDIO PLAYBACK TRACK",
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
                val isOriginal = lang == sourceLanguage
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
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            lang.displayName,
                            color = if (isSelected) Color(0xFF1A1000) else Ivory,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center
                        )
                        if (isOriginal) {
                            Text(
                                "Original",
                                color = if (isSelected) Color(0xFF4A3000) else GoldDim,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────── Music Hint ──────────────────────────────────────

@Composable
private fun MusicHint(language: Language, sourceLanguage: Language) {
    if (language == sourceLanguage) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BgCard)
            .border(1.dp, BorderGold, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🎵", fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                "Original speech muted • Dubbed ${language.displayName} audio synchronized",
                color = IvoryDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ─────────────────────────── Export & Share Row ──────────────────────────────

@Composable
private fun ExportShareRow(
    run: VideoRun,
    currentLanguage: Language,
    onExport: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ActionButton(
            modifier = Modifier.weight(1f),
            label = "Save to Downloads",
            accent = true,
            icon = { Text("💾", fontSize = 14.sp) },
            onClick = onExport
        )
        ActionButton(
            modifier = Modifier.weight(1f),
            label = "Share Track",
            accent = false,
            icon = { Icon(Icons.Default.Share, null, tint = InfoBlue, modifier = Modifier.size(16.dp)) },
            onClick = onShare
        )
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
            label = "Storage & Library",
            accent = true,
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
    onExportRun: (VideoRun) -> Unit,
    onShareRun: (VideoRun) -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Storage & Saved Videos", color = Ivory, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${runs.size} translated video${if (runs.size != 1) "s" else ""} stored on-device",
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
                        Text("No saved videos in storage yet", color = IvoryDim, fontSize = 14.sp)
                        Text("Translate a video to access it offline anytime", color = MutedLabel, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(runs, key = { it.runId }) { run ->
                        LibraryRunCard(
                            run = run,
                            onPlay = { onSelectRun(run) },
                            onExport = { onExportRun(run) },
                            onShare = { onShareRun(run) },
                            onDelete = { onDeleteRun(run.runId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRunCard(
    run: VideoRun,
    onPlay: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (run.status) {
        "Ready" -> SuccessGreen
        "Error" -> ErrorRed
        else    -> WarningAmber
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgElevated)
            .border(1.dp, BorderGold, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        run.videoTitle,
                        color = Ivory,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MetadataPill(run.formattedDate)
                        MetadataPill("${run.segmentCount} segments")
                        MetadataPill(
                            if (run.detectedGender == "FEMALE") "♀ Female" else "♂ Male"
                        )
                    }
                }

                IconButton(onClick = onPlay, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.PlayArrow, "Play", tint = SuccessGreen, modifier = Modifier.size(24.dp))
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Gold.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Text("💾 Save", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, InfoBlue.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Share, null, tint = InfoBlue, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Share", color = InfoBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.width(8.dp))

                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, "Delete", tint = ErrorRed.copy(alpha = 0.7f), modifier = Modifier.size(17.dp))
                }
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
    onClear: () -> Unit,
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
                    Text("Real-time AI pipeline execution trace", color = IvoryDim, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onClear,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderGold),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Clear", color = IvoryDim, fontSize = 12.sp)
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
                    fontSize = 11.sp,
                    lineHeight = 16.sp
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
