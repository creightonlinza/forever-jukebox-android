package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullWhatsNewContentTest {

    @Test
    fun fullWhatsNewIncludesServerSearchFeatures() {
        val prompt = buildWhatsNewPrompt(
            versionCode = 12,
            versionName = "v2026.05.1",
            bullets = listOf(" ", "")
        )

        assertEquals(currentWhatsNewBullets, prompt.bullets)
        assertTrue(prompt.bullets.any { it.contains("YouTube", ignoreCase = true) })
        assertTrue(prompt.bullets.any { it.contains("Favorites search", ignoreCase = true) })
    }
}
