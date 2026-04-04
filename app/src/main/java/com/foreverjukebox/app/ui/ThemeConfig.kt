package com.foreverjukebox.app.ui

import android.content.Context
import androidx.core.graphics.toColorInt
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import java.io.IOException

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
    val titleGlow: Color,
    val muted: Color,
    val edgeStroke: Color,
    val beatFill: Color,
    val beatHighlight: Color,
    val vizBackground: Color
)

data class ThemeConfig(val dark: ThemeTokens, val light: ThemeTokens)

private val DarkTokens = ThemeTokens(
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE7E4DD),
    panelSurface = Color(0xFF141922),
    heroSurface = Color(0xFF1A1F27),
    controlSurface = Color(0xFF1F2633),
    controlSurfaceHover = Color(0xFF202835),
    panelBorder = Color(0xFF283142),
    heroBorder = Color(0xFF2B3442),
    controlBorder = Color(0xFF3B465B),
    accent = Color(0xFF4AC7FF),
    titleAccent = Color(0xFFF1C47A),
    titleGlow = Color(0x59F1C47A),
    muted = Color(0xFF9AA3B2),
    edgeStroke = Color(0x804AC7FF),
    beatFill = Color(0xFFFFD46A),
    beatHighlight = Color(0xFFFFD46A),
    vizBackground = Color(0xFF232B3D)
)

private val LightTokens = ThemeTokens(
    background = Color(0xFFF6F1FF),
    onBackground = Color(0xFF261A38),
    panelSurface = Color(0xFFFCFAFF),
    heroSurface = Color(0xFFEFE5FF),
    controlSurface = Color(0xFFE8DBFF),
    controlSurfaceHover = Color(0xFFDDCCFF),
    panelBorder = Color(0x33492B71),
    heroBorder = Color(0x425B3096),
    controlBorder = Color(0x57583898),
    accent = Color(0xFF2E8BFF),
    titleAccent = Color(0xFFB144FF),
    titleGlow = Color(0x57B144FF),
    muted = Color(0xFF635280),
    edgeStroke = Color(0x704D3078),
    beatFill = Color(0xFFB144FF),
    beatHighlight = Color(0xFFB144FF),
    vizBackground = Color(0xFFE9DBFF)
)

val LocalThemeTokens = staticCompositionLocalOf { DarkTokens }

fun themeTokens(isDark: Boolean): ThemeTokens = if (isDark) DarkTokens else LightTokens

fun loadThemeConfig(context: Context): ThemeConfig? {
    return try {
        val raw = context.assets.open("theme.json").bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        ThemeConfig(
            dark = parseThemeTokens(root.getJSONObject("dark")),
            light = parseThemeTokens(root.getJSONObject("light"))
        )
    } catch (_: IOException) {
        null
    } catch (_: Exception) {
        null
    }
}

internal fun parseThemeTokens(obj: JSONObject): ThemeTokens {
    val raw = mutableMapOf<String, String>()
    raw["background"] = obj.getString("background")
    raw["onBackground"] = obj.getString("onBackground")
    raw["panelSurface"] = obj.getString("panelSurface")
    raw["heroSurface"] = obj.getString("heroSurface")
    raw["controlSurface"] = obj.getString("controlSurface")
    raw["controlSurfaceHover"] = obj.getString("controlSurfaceHover")
    raw["panelBorder"] = obj.getString("panelBorder")
    raw["heroBorder"] = obj.getString("heroBorder")
    raw["controlBorder"] = obj.getString("controlBorder")
    raw["accent"] = obj.getString("accent")
    raw["titleAccent"] = obj.getString("titleAccent")
    if (obj.has("titleGlow")) {
        raw["titleGlow"] = obj.getString("titleGlow")
    }
    raw["muted"] = obj.getString("muted")
    raw["edgeStroke"] = obj.getString("edgeStroke")
    raw["beatFill"] = obj.getString("beatFill")
    raw["beatHighlight"] = obj.getString("beatHighlight")
    raw["vizBackground"] = obj.getString("vizBackground")
    return themeTokensFromRaw(raw)
}

internal fun themeTokensFromRaw(raw: Map<String, String>): ThemeTokens {
    val titleAccent = parseColor(raw.getValue("titleAccent"))
    val titleGlow = raw["titleGlow"]?.let(::parseColor) ?: fallbackTitleGlow(titleAccent)
    return ThemeTokens(
        background = parseColor(raw.getValue("background")),
        onBackground = parseColor(raw.getValue("onBackground")),
        panelSurface = parseColor(raw.getValue("panelSurface")),
        heroSurface = parseColor(raw.getValue("heroSurface")),
        controlSurface = parseColor(raw.getValue("controlSurface")),
        controlSurfaceHover = parseColor(raw.getValue("controlSurfaceHover")),
        panelBorder = parseColor(raw.getValue("panelBorder")),
        heroBorder = parseColor(raw.getValue("heroBorder")),
        controlBorder = parseColor(raw.getValue("controlBorder")),
        accent = parseColor(raw.getValue("accent")),
        titleAccent = titleAccent,
        titleGlow = titleGlow,
        muted = parseColor(raw.getValue("muted")),
        edgeStroke = parseColor(raw.getValue("edgeStroke")),
        beatFill = parseColor(raw.getValue("beatFill")),
        beatHighlight = parseColor(raw.getValue("beatHighlight")),
        vizBackground = parseColor(raw.getValue("vizBackground"))
    )
}

internal fun fallbackTitleGlow(titleAccent: Color): Color = titleAccent.copy(alpha = 0.28f)

private fun parseColor(value: String): Color {
    val trimmed = value.trim()
    return when {
        trimmed.startsWith("#") -> Color(trimmed.toColorInt())
        trimmed.startsWith("0x", ignoreCase = true) -> {
            val hex = trimmed.removePrefix("0x")
            val argb = hex.toLong(16).toInt()
            Color(argb)
        }
        trimmed.startsWith("rgba", ignoreCase = true) -> parseRgb(trimmed, true)
        trimmed.startsWith("rgb", ignoreCase = true) -> parseRgb(trimmed, false)
        else -> Color(trimmed.toColorInt())
    }
}

private fun parseRgb(value: String, hasAlpha: Boolean): Color {
    val start = value.indexOf("(")
    val end = value.indexOf(")")
    if (start == -1 || end == -1 || end <= start + 1) {
        return Color.Unspecified
    }
    val parts = value.substring(start + 1, end).split(",").map { it.trim() }
    if (parts.size < 3) {
        return Color.Unspecified
    }
    val r = parts[0].toFloatOrNull() ?: return Color.Unspecified
    val g = parts[1].toFloatOrNull() ?: return Color.Unspecified
    val b = parts[2].toFloatOrNull() ?: return Color.Unspecified
    val alpha = if (hasAlpha && parts.size >= 4) {
        parts[3].toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
    } else {
        1f
    }
    return Color(r / 255f, g / 255f, b / 255f, alpha)
}

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
