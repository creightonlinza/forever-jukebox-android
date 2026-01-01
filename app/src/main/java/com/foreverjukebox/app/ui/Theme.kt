package com.foreverjukebox.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.foreverjukebox.app.data.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4AC7FF),
    onPrimary = Color(0xFF0F1115),
    secondary = Color(0xFFF1C47A),
    onSecondary = Color(0xFF1A130A),
    tertiary = Color(0xFF4AC7FF),
    onTertiary = Color(0xFF0F1115),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE7E4DD),
    surface = Color(0xFF141922),
    onSurface = Color(0xFFE7E4DD),
    surfaceVariant = Color(0xFF1A1F27),
    onSurfaceVariant = Color(0xFFB9C0CC),
    outline = Color(0xFF2B3442),
    outlineVariant = Color(0xFF3B465B)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0A4C7D),
    onPrimary = Color(0xFFEFF5FB),
    secondary = Color(0xFFD68A3C),
    onSecondary = Color(0xFF1D1206),
    tertiary = Color(0xFF35526C),
    onTertiary = Color(0xFFF5F9FF),
    background = Color(0xFFEEF5FB),
    onBackground = Color(0xFF0F1B28),
    surface = Color(0xFFF5F9FF),
    onSurface = Color(0xFF0F1B28),
    surfaceVariant = Color(0xFFD6E8FF),
    onSurfaceVariant = Color(0xFF35526C),
    outline = Color(0xFFB9D1EA),
    outlineVariant = Color(0xFF88B2DA)
)

@Composable
fun ForeverJukeboxTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val colors: ColorScheme = when (mode) {
        ThemeMode.Dark -> DarkColors
        ThemeMode.Light -> LightColors
        ThemeMode.System -> if (isSystemInDarkTheme()) DarkColors else LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
