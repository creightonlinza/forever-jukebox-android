package com.foreverjukebox.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.foreverjukebox.app.data.ThemeMode

@Composable
fun ForeverJukeboxTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val isDark = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val themeConfig = remember(context) { loadThemeConfig(context) }
    val tokens = themeConfig?.let { if (isDark) it.dark else it.light } ?: themeTokens(isDark)
    val colors: ColorScheme = themeColors(tokens, isDark)
    val typography = remember { appTypography() }
    val shapes = remember { appShapes() }
    CompositionLocalProvider(LocalThemeTokens provides tokens) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = shapes,
            content = content
        )
    }
}

private fun appTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = appFontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = appFontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = appFontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = appFontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = appFontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = appFontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = appFontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = appFontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = appFontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = appFontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = appFontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = appFontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = appFontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = appFontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = appFontFamily)
    )
}

private fun appShapes(): Shapes {
    val corner = RoundedCornerShape(8.dp)
    return Shapes(
        extraSmall = corner,
        small = corner,
        medium = corner,
        large = corner,
        extraLarge = corner
    )
}
