package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewPolicyTest {

    @Test
    fun automaticDialogDoesNotShowWhileOnboardingGateIsVisible() {
        assertFalse(
            shouldShowAutomaticWhatsNew(
                showAppModeGate = true,
                whatsNewVersionCodeLoaded = true,
                lastShownVersionCode = null,
                currentVersionCode = 12,
                currentPrompt = null
            )
        )
    }

    @Test
    fun automaticDialogShowsWhenNoStoredVersionExistsAfterOnboarding() {
        assertTrue(
            shouldShowAutomaticWhatsNew(
                showAppModeGate = false,
                whatsNewVersionCodeLoaded = true,
                lastShownVersionCode = null,
                currentVersionCode = 12,
                currentPrompt = null
            )
        )
    }

    @Test
    fun automaticDialogDoesNotShowWhenStoredVersionMatchesCurrentVersion() {
        assertFalse(
            shouldShowAutomaticWhatsNew(
                showAppModeGate = false,
                whatsNewVersionCodeLoaded = true,
                lastShownVersionCode = 12,
                currentVersionCode = 12,
                currentPrompt = null
            )
        )
    }

    @Test
    fun automaticDialogShowsWhenCurrentVersionIsNewerThanStoredVersion() {
        assertTrue(
            shouldShowAutomaticWhatsNew(
                showAppModeGate = false,
                whatsNewVersionCodeLoaded = true,
                lastShownVersionCode = 11,
                currentVersionCode = 12,
                currentPrompt = null
            )
        )
    }

    @Test
    fun automaticDialogWaitsForStoredVersionPreferenceToLoad() {
        assertFalse(
            shouldShowAutomaticWhatsNew(
                showAppModeGate = false,
                whatsNewVersionCodeLoaded = false,
                lastShownVersionCode = null,
                currentVersionCode = 12,
                currentPrompt = null
            )
        )
    }

    @Test
    fun automaticDialogDoesNotReplaceExistingPrompt() {
        val prompt = buildWhatsNewPrompt(
            versionCode = 12,
            versionName = "v2026.05.1"
        )

        assertFalse(
            shouldShowAutomaticWhatsNew(
                showAppModeGate = false,
                whatsNewVersionCodeLoaded = true,
                lastShownVersionCode = null,
                currentVersionCode = 12,
                currentPrompt = prompt
            )
        )
    }

    @Test
    fun blankBulletsFallBackToDefaultsAndAreTrimmed() {
        val fallback = buildWhatsNewPrompt(
            versionCode = 12,
            versionName = "v2026.05.1",
            bullets = listOf(" ", "")
        )
        assertEquals(currentWhatsNewBullets, fallback.bullets)

        val trimmed = buildWhatsNewPrompt(
            versionCode = 12,
            versionName = "v2026.05.1",
            bullets = listOf("  spaced  ", " ")
        )
        assertEquals(listOf("spaced"), trimmed.bullets)
    }

    @Test
    fun dismissingDialogRecordsCurrentVersionAndClearsPrompt() {
        val state = UiState(
            appMode = AppMode.Local,
            showAppModeGate = false,
            whatsNewPrompt = buildWhatsNewPrompt(
                versionCode = 12,
                versionName = "v2026.05.1"
            )
        )

        val next = stateAfterWhatsNewDismissed(
            state = state,
            dismissedVersionCode = 12
        )

        assertEquals(12, next.lastShownWhatsNewVersionCode)
        assertNull(next.whatsNewPrompt)
    }
}
