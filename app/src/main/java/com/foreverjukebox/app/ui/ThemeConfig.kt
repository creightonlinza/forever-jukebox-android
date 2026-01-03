package com.foreverjukebox.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class ThemeTokens(
    val background: Color,
    val onBackground: Color,
    val panelSurface: Color,
    val heroSurface: Color,
    val controlSurface: Color,
    val controlSurfaceHover: Color,
    val panelBorder: Color,
    val heroBorder: Color,
    val controlBorder: Color,
    val accent: Color,
    val titleAccent: Color,
    val muted: Color,
    val edgeStroke: Color,
    val beatFill: Color,
    val beatHighlight: Color
)

private val DarkTokens = ThemeTokens(
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE7E4DD),
    panelSurface = Color(0xFF141922),
    heroSurface = Color(0xFF1A1F27),
    controlSurface = Color(0xFF1F2633),
    controlSurfaceHover = Color(0xFF283244),
    panelBorder = Color(0xFF283142),
    heroBorder = Color(0xFF2B3442),
    controlBorder = Color(0xFF3B465B),
    accent = Color(0xFF4AC7FF),
    titleAccent = Color(0xFFF1C47A),
    muted = Color(0xFFB9C0CC),
    edgeStroke = Color(0x1F4AC7FF),
    beatFill = Color(0x8CFFD782),
    beatHighlight = Color(0xFFFFD46A)
)

private val LightTokens = ThemeTokens(
    background = Color(0xFFEEF5FB),
    onBackground = Color(0xFF0F1B28),
    panelSurface = Color(0xFFF5F9FF),
    heroSurface = Color(0xFFD6E8FF),
    controlSurface = Color(0xFFD6E8FF),
    controlSurfaceHover = Color(0xFFC4DCFF),
    panelBorder = Color(0xFFB9D1EA),
    heroBorder = Color(0xFF88B2DA),
    controlBorder = Color(0xFF88B2DA),
    accent = Color(0xFF0A4C7D),
    titleAccent = Color(0xFFD68A3C),
    muted = Color(0xFF35526C),
    edgeStroke = Color(0x4D0A4C7D),
    beatFill = Color(0x730A4C7D),
    beatHighlight = Color(0xFF0A4C7D)
)

val LocalThemeTokens = staticCompositionLocalOf { DarkTokens }

fun themeTokens(isDark: Boolean): ThemeTokens = if (isDark) DarkTokens else LightTokens

fun themeColors(tokens: ThemeTokens, isDark: Boolean): ColorScheme {
    return if (isDark) {
        darkColorScheme(
            primary = tokens.accent,
            onPrimary = tokens.background,
            secondary = tokens.titleAccent,
            onSecondary = tokens.background,
            tertiary = tokens.accent,
            onTertiary = tokens.background,
            background = tokens.background,
            onBackground = tokens.onBackground,
            surface = tokens.panelSurface,
            onSurface = tokens.onBackground,
            surfaceVariant = tokens.heroSurface,
            onSurfaceVariant = tokens.muted,
            outline = tokens.panelBorder,
            outlineVariant = tokens.controlBorder
        )
    } else {
        lightColorScheme(
            primary = tokens.accent,
            onPrimary = tokens.background,
            secondary = tokens.titleAccent,
            onSecondary = tokens.background,
            tertiary = tokens.muted,
            onTertiary = tokens.panelSurface,
            background = tokens.background,
            onBackground = tokens.onBackground,
            surface = tokens.panelSurface,
            onSurface = tokens.onBackground,
            surfaceVariant = tokens.heroSurface,
            onSurfaceVariant = tokens.muted,
            outline = tokens.panelBorder,
            outlineVariant = tokens.controlBorder
        )
    }
}
