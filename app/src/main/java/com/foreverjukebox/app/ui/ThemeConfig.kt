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
    val panelBorder: Color,
    val controlBorder: Color,
    val accent: Color,
    val titleAccent: Color,
    val titleGlow: Color,
    val muted: Color,
    val edgeStroke: Color,
    val beatFill: Color,
    val beatHighlight: Color,
    val vizBackground: Color,
    // Destructive actions and the anchor-branch highlight share one red.
    val danger: Color,
    // Autocanonizer cursor/tile colors (match the web palette by default).
    val canonMain: Color,
    val canonOther: Color
)

data class ThemeConfig(val dark: ThemeTokens, val light: ThemeTokens)

internal val DefaultDangerColor = Color(0xFFE35A5A)
internal val DefaultCanonMainColor = Color(0xFF4F8FFF)
internal val DefaultCanonOtherColor = Color(0xFF10DF00)

private val DarkTokens = ThemeTokens(
    background = Color(0xFF000000),
    onBackground = Color(0xFFE7E4DD),
    panelSurface = Color(0xFF141922),
    heroSurface = Color(0xFF1A1F27),
    controlSurface = Color(0xFF1F2633),
    panelBorder = Color(0xFF283142),
    controlBorder = Color(0xFF3B465B),
    accent = Color(0xFF4AC7FF),
    titleAccent = Color(0xFFF1C47A),
    titleGlow = Color(0x59F1C47A),
    muted = Color(0xFF9AA3B2),
    edgeStroke = Color(0x804AC7FF),
    beatFill = Color(0xFFFFD46A),
    beatHighlight = Color(0xFFFFD46A),
    vizBackground = Color(0xFF232B3D),
    danger = DefaultDangerColor,
    canonMain = DefaultCanonMainColor,
    canonOther = DefaultCanonOtherColor
)

private val LightTokens = ThemeTokens(
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF261A38),
    panelSurface = Color(0xFFF2ECFB),
    heroSurface = Color(0xFFEFE5FF),
    controlSurface = Color(0xFFE8DBFF),
    panelBorder = Color(0x33492B71),
    controlBorder = Color(0x57583898),
    accent = Color(0xFF2E8BFF),
    titleAccent = Color(0xFFB144FF),
    titleGlow = Color(0x57B144FF),
    muted = Color(0xFF635280),
    edgeStroke = Color(0x704D3078),
    beatFill = Color(0xFFB144FF),
    beatHighlight = Color(0xFFB144FF),
    vizBackground = Color(0xFFE9DBFF),
    danger = DefaultDangerColor,
    canonMain = DefaultCanonMainColor,
    canonOther = DefaultCanonOtherColor
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

// Unknown keys in theme.json (including the retired controlSurfaceHover and
// heroBorder) are ignored so older theme files still load.
internal fun parseThemeTokens(obj: JSONObject): ThemeTokens {
    val raw = mutableMapOf<String, String>()
    val keys = listOf(
        "background",
        "onBackground",
        "panelSurface",
        "heroSurface",
        "controlSurface",
        "panelBorder",
        "controlBorder",
        "accent",
        "titleAccent",
        "muted",
        "edgeStroke",
        "beatFill",
        "beatHighlight",
        "vizBackground"
    )
    for (key in keys) {
        raw[key] = obj.getString(key)
    }
    val optionalKeys = listOf("titleGlow", "danger", "canonMain", "canonOther")
    for (key in optionalKeys) {
        if (obj.has(key)) {
            raw[key] = obj.getString(key)
        }
    }
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
        panelBorder = parseColor(raw.getValue("panelBorder")),
        controlBorder = parseColor(raw.getValue("controlBorder")),
        accent = parseColor(raw.getValue("accent")),
        titleAccent = titleAccent,
        titleGlow = titleGlow,
        muted = parseColor(raw.getValue("muted")),
        edgeStroke = parseColor(raw.getValue("edgeStroke")),
        beatFill = parseColor(raw.getValue("beatFill")),
        beatHighlight = parseColor(raw.getValue("beatHighlight")),
        vizBackground = parseColor(raw.getValue("vizBackground")),
        danger = raw["danger"]?.let(::parseColor) ?: DefaultDangerColor,
        canonMain = raw["canonMain"]?.let(::parseColor) ?: DefaultCanonMainColor,
        canonOther = raw["canonOther"]?.let(::parseColor) ?: DefaultCanonOtherColor
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
