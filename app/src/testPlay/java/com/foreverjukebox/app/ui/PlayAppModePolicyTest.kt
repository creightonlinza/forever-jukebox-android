package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayAppModePolicyTest {
    @Test
    fun playBuildAlwaysUsesLocalTabs() {
        assertEquals(listOf(TabId.Input, TabId.Play, TabId.Faq), tabsForMode(null))
        assertEquals(listOf(TabId.Input, TabId.Play, TabId.Faq), tabsForMode(AppMode.Local))
        assertEquals(listOf(TabId.Input, TabId.Play, TabId.Faq), tabsForMode(AppMode.Server))
        assertEquals(TabId.Input, defaultTabForMode(null))
        assertEquals(TabId.Input, coerceTabForMode(AppMode.Server, TabId.Search))
    }

    @Test
    fun playBuildHasNoServerPromptsOrActions() {
        assertFalse(shouldShowAppModeGate(null))
        assertFalse(shouldShowBaseUrlPrompt(AppMode.Server, ""))
        assertFalse(shouldShowServerListenActions(AppMode.Server))
        assertFalse(
            shouldShowDeleteTrackAction(
                mode = AppMode.Server,
                playback = PlaybackState(lastJobId = "job_123", deleteEligible = true),
                adminKey = "admin-secret"
            )
        )
    }
}
