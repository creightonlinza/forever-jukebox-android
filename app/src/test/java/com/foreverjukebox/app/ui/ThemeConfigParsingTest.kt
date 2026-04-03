package com.foreverjukebox.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeConfigParsingTest {

    @Test
    fun parseThemeTokensReadsTitleGlowWhenPresent() {
        val tokens = themeTokensFromRaw(themeTokensRaw(titleGlow = "rgba(176, 106, 31, 0.28)"))

        assertEquals(Color(0x47B06A1F), tokens.titleGlow)
    }

    @Test
    fun parseThemeTokensFallsBackToTitleAccentWhenTitleGlowMissing() {
        val tokens = themeTokensFromRaw(themeTokensRaw())
        val expected = fallbackTitleGlow(tokens.titleAccent)

        assertEquals(expected, tokens.titleGlow)
    }

    private fun themeTokensRaw(titleGlow: String? = null): Map<String, String> {
        val values = mutableMapOf(
            "background" to "0xFFF7F2E8",
            "onBackground" to "0xFF2D2113",
            "panelSurface" to "0xFFFFFDF8",
            "heroSurface" to "0xFFF5ECDD",
            "controlSurface" to "0xFFF2E5D2",
            "controlSurfaceHover" to "0xFFEAD9BF",
            "panelBorder" to "rgba(75, 53, 26, 0.20)",
            "heroBorder" to "rgba(100, 69, 34, 0.24)",
            "controlBorder" to "rgba(95, 71, 43, 0.32)",
            "accent" to "0xFF0F8A70",
            "titleAccent" to "0xFFB06A1F",
            "muted" to "0xFF5E4B34",
            "edgeStroke" to "rgba(45, 33, 19, 0.42)",
            "beatFill" to "0xFFD08A3A",
            "beatHighlight" to "0xFFD08A3A",
            "vizBackground" to "0xFFEFE2CC"
        )
        if (titleGlow != null) {
            values["titleGlow"] = titleGlow
        }
        return values
    }
}
