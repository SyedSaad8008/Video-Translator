package com.example.videotranslator.ui.player

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.example.videotranslator.R
import com.example.videotranslator.model.Language
import com.example.videotranslator.model.ProcessingState

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

@Composable
fun VideoPlayerScreen(
    viewModel: VideoPlayerViewModel = viewModel()
) {
    val processingState by viewModel.processingState.collectAsStateWithLifecycle()
    val currentLanguage  by viewModel.currentLanguage.collectAsStateWithLifecycle()
    val missingVoice     by viewModel.missingVoiceWarning.collectAsStateWithLifecycle()
    val videoUri         by viewModel.videoUri.collectAsStateWithLifecycle()
    val lifecycleOwner   = LocalLifecycleOwner.current

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.onVideoPicked(it) } }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
    ) {
        // Subtle ambient glow behind the content
        AmbientGlow()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()   // ← Fix: keep content below notification bar
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))

            // ── Header ──────────────────────────────────────────────────────
            PremiumHeader()

            Spacer(Modifier.height(20.dp))

            // ── Video area ───────────────────────────────────────────────────
            if (videoUri == null) {
                PickerCard(onPick = { videoPicker.launch("video/*") })
            } else {
                VideoSurface(viewModel = viewModel)
            }

            Spacer(Modifier.height(18.dp))

            // ── Processing state ─────────────────────────────────────────────
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
                        is ProcessingState.Error   -> ErrorCard(state.message) { viewModel.reprocess() }
                        else -> {}
                    }
                }
            }

            // ── Language selector ────────────────────────────────────────────
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

            // ── Missing TTS voice warning ────────────────────────────────────
            AnimatedVisibility(visible = missingVoice) {
                MissingVoiceCard(currentLanguage)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─────────────────────────── Ambient glow ────────────────────────────────────

@Composable
private fun AmbientGlow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Gold.copy(alpha = 0.06f), Color.Transparent),
                        center = Offset(size.width / 2, 0f),
                        radius = size.width * 0.7f
                    ),
                    center = Offset(size.width / 2, 0f),
                    radius = size.width * 0.7f
                )
            }
    )
}

// ─────────────────────────── Header ──────────────────────────────────────────

@Composable
private fun PremiumHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
    ) {
        // App Logo Icon
        Image(
            painter = painterResource(R.drawable.ic_app_logo),
            contentDescription = "LinguaPlay App Logo",
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.height(8.dp))
        // App name
        Text(
            text = "LINGUAPLAY",
            color = Ivory,
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 7.sp
        )
        Spacer(Modifier.height(4.dp))
        // Subtitle — thin divider style
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            GoldDivider(width = 30.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "हिंदी  ·  English  ·  తెలుగు",
                color = Gold,
                fontSize = 11.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.width(10.dp))
            GoldDivider(width = 30.dp)
        }
    }
}

@Composable
private fun GoldDivider(width: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(1.dp)
            .background(
                Brush.horizontalGradient(listOf(Color.Transparent, Gold.copy(alpha = 0.5f), Color.Transparent))
            )
    )
}

// ─────────────────────────── Picker card ─────────────────────────────────────

@Composable
private fun PickerCard(onPick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(BgCard)
            .border(1.dp, BorderGold, RoundedCornerShape(20.dp))
            .clickable { onPick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Film icon made from composables
            FilmIcon()
            Spacer(Modifier.height(16.dp))
            Text(
                "Select a Video",
                color = Ivory,
                fontSize = 17.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap to browse your gallery",
                color = IvoryDim,
                fontSize = 12.sp,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(22.dp))
            GoldPillButton(text = "Browse Gallery", onClick = onPick)
        }
    }
}

@Composable
private fun FilmIcon() {
    // Minimalist film strip icon using Box composables
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Gold.copy(alpha = 0.12f))
            .border(1.dp, BorderGoldHi, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text("▶", fontSize = 22.sp, color = Gold)
    }
}

// ─────────────────────────── Video surface ───────────────────────────────────

@Composable
private fun VideoSurface(viewModel: VideoPlayerViewModel) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, BorderGold, RoundedCornerShape(20.dp))
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = viewModel.exoPlayer
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
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

// ─────────────────────────── Processing card ─────────────────────────────────

@Composable
private fun ProcessingCard(state: ProcessingState.Loading) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha"
    )

    LuxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Animated gold ring
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = Gold.copy(alpha = alpha),
                trackColor = Gold.copy(alpha = 0.12f),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.step,
                    color = Ivory,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 0.3.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Results cached — runs once per video",
                    color = IvoryDim,
                    fontSize = 11.sp
                )
            }
        }
        if (state.progress >= 0f) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp)),
                color = Gold,
                trackColor = Gold.copy(alpha = 0.15f)
            )
        }
    }
}

// ─────────────────────────── Error card ──────────────────────────────────────

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    LuxCard(borderColor = ErrorRed.copy(alpha = 0.3f)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = ErrorRed.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp).padding(top = 2.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Processing Error", color = Ivory, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Text(
                    message,
                    color = IvoryDim, fontSize = 12.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
            border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.4f))
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("Retry", fontSize = 13.sp, letterSpacing = 1.sp)
        }
    }
}

// ─────────────────────────── Language selector ───────────────────────────────

@Composable
private fun LanguageSelector(current: Language, onSelect: (Language) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "AUDIO LANGUAGE",
            color = MutedWhite,
            fontSize = 10.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Language.entries.forEach { lang ->
                LanguagePill(
                    language = lang,
                    selected = lang == current,
                    onClick = { onSelect(lang) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun LanguagePill(
    language: Language,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animSpec = tween<Float>(300)
    val elevation by animateFloatAsState(if (selected) 8f else 0f, animSpec, label = "elev")

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) Modifier.background(GoldGradient)
                else Modifier
                    .background(BgCard)
                    .border(1.dp, BorderGoldHi, RoundedCornerShape(12.dp))
            )
            .clickable { onClick() }
            .padding(vertical = 13.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = language.displayName,
            color = if (selected) Color(0xFF1A1000) else IvoryDim,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Light,
            textAlign = TextAlign.Center,
            letterSpacing = 0.5.sp
        )
    }
}

// ─────────────────────────── Music hint ──────────────────────────────────────

@Composable
private fun MusicHint(language: Language) {
    AnimatedVisibility(
        visible = language != Language.HINDI,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(200))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0D1F14))
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
}

// ─────────────────────────── Change video button ─────────────────────────────

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

// ─────────────────────────── Missing voice ───────────────────────────────────

@Composable
private fun MissingVoiceCard(language: Language) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1F1400))
            .border(1.dp, Gold.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text("⚠", fontSize = 14.sp, color = Gold)
            Spacer(Modifier.width(10.dp))
            Text(
                "No ${language.displayName} TTS voice installed. " +
                "Go to Settings → Accessibility → Text-to-speech to install it.",
                color = Gold.copy(alpha = 0.8f),
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

// ─────────────────────────── Shared components ───────────────────────────────

/** Luxury card with gold border */
@Composable
private fun LuxCard(
    borderColor: Color = BorderGold,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(BgCard)
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content
    )
}

/** Gold gradient pill button */
@Composable
private fun GoldPillButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .background(GoldGradient)
            .clickable { onClick() }
            .padding(horizontal = 28.dp, vertical = 11.dp)
    ) {
        Text(
            text = text,
            color = Color(0xFF1A1000),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
    }
}
