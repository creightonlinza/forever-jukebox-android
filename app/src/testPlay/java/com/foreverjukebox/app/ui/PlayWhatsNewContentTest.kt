package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayWhatsNewContentTest {

    @Test
    fun playWhatsNewExcludesServerSearchFeatures() {
        val prompt = buildWhatsNewPrompt(
            versionCode = 12,
            versionName = "v2026.05.1",
            bullets = listOf(" ", "")
        )

        assertEquals(currentWhatsNewBullets, prompt.bullets)
        assertFalse(prompt.bullets.any { it.contains("YouTube", ignoreCase = true) })
        assertFalse(prompt.bullets.any { it.contains("search", ignoreCase = true) })
        assertFalse(prompt.bullets.any { it.contains("server", ignoreCase = true) })
        assertFalse(prompt.bullets.any { it.contains("sync", ignoreCase = true) })
    }
}
