package com.manst.lantransfer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Blue600,
    onPrimary = Color.White,
    primaryContainer = Blue100,
    onPrimaryContainer = Blue900,
    secondary = Teal600,
    onSecondary = Color.White,
    secondaryContainer = Teal100,
    onSecondaryContainer = Color(0xFF00382F),
    tertiary = Amber600,
    onTertiary = Color.White,
    background = Slate50,
    onBackground = Blue900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Color(0xFFE7EEF6),
    onSurfaceVariant = Color(0xFF47566B),
    outline = Color(0xFF8494A9),
    error = ErrorRed,
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Blue300,
    onPrimary = Blue900,
    primaryContainer = Blue700,
    onPrimaryContainer = Blue100,
    secondary = Color(0xFF7FD6C4),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF0A4A40),
    onSecondaryContainer = Teal100,
    tertiary = Color(0xFFFFB95C),
    onTertiary = Color(0xFF3A2200),
    background = Slate900,
    onBackground = Color(0xFFE3EAF3),
    surface = Slate800,
    onSurface = Color(0xFFE3EAF3),
    surfaceVariant = Slate700,
    onSurfaceVariant = Slate200,
    outline = Color(0xFF5C6C82),
    error = ErrorRedLight,
    onError = Color(0xFF690005)
)

@Composable
fun LanTransferTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
