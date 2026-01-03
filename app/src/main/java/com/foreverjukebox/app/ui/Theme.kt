package com.foreverjukebox.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.foreverjukebox.app.data.ThemeMode

@Composable
fun ForeverJukeboxTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val isDark = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val tokens = themeTokens(isDark)
    val colors: ColorScheme = themeColors(tokens, isDark)
    CompositionLocalProvider(LocalThemeTokens provides tokens) {
        MaterialTheme(
            colorScheme = colors,
            content = content
        )
    }
}
