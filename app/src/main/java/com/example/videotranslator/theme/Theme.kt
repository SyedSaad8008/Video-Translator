package com.example.videotranslator.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Always use dark theme for the video translator
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9B59B6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3D1A5C),
    secondary = Color(0xFF7D3C98),
    background = Color(0xFF0A0A1A),
    surface = Color(0xFF12122A),
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun VideoTranslatorTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
