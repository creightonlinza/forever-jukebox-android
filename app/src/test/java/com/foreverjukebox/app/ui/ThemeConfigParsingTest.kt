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

    @Test
    fun parseThemeTokensUsesDefaultsForOptionalColorsWhenMissing() {
        val tokens = themeTokensFromRaw(themeTokensRaw())

        assertEquals(DefaultDangerColor, tokens.danger)
        assertEquals(DefaultCanonMainColor, tokens.canonMain)
        assertEquals(DefaultCanonOtherColor, tokens.canonOther)
    }

    @Test
    fun parseThemeTokensReadsOptionalColorsWhenPresent() {
        val raw = themeTokensRaw() + mapOf(
            "danger" to "0xFFAA1111",
            "canonMain" to "0xFF2244CC",
            "canonOther" to "0xFF22CC44"
        )
        val tokens = themeTokensFromRaw(raw)

        assertEquals(Color(0xFFAA1111), tokens.danger)
        assertEquals(Color(0xFF2244CC), tokens.canonMain)
        assertEquals(Color(0xFF22CC44), tokens.canonOther)
    }

    private fun themeTokensRaw(titleGlow: String? = null): Map<String, String> {
        val values = mutableMapOf(
            "background" to "0xFFF7F2E8",
            "onBackground" to "0xFF2D2113",
            "panelSurface" to "0xFFFFFDF8",
            "heroSurface" to "0xFFF5ECDD",
            "controlSurface" to "0xFFF2E5D2",
            "panelBorder" to "rgba(75, 53, 26, 0.20)",
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
