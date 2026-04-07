package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLifecycleCoordinatorPolicyTest {

    @Test
    fun stateAfterPrepareForExitResetsTabsForLocalMode() {
        val next = stateAfterPrepareForExit(
            UiState(
                appMode = AppMode.Local,
                activeTab = TabId.Play,
                topSongsTab = TopSongsTab.Trending
            )
        )

        assertEquals(TabId.Input, next.activeTab)
        assertEquals(TopSongsTab.TopSongs, next.topSongsTab)
    }

    @Test
    fun stateAfterPrepareForExitResetsTabsForServerMode() {
        val next = stateAfterPrepareForExit(
            UiState(
                appMode = AppMode.Server,
                activeTab = TabId.Play,
                topSongsTab = TopSongsTab.Recent
            )
        )

        assertEquals(TabId.Top, next.activeTab)
        assertEquals(TopSongsTab.TopSongs, next.topSongsTab)
    }

    @Test
    fun resolveVersionUpdatePromptReturnsNullForBlankFields() {
        assertNull(
            resolveVersionUpdatePrompt(
                currentVersionName = "v2026.04.01",
                latestVersionRaw = "  ",
                downloadUrlRaw = "https://example.com/release"
            )
        )
        assertNull(
            resolveVersionUpdatePrompt(
                currentVersionName = "v2026.04.01",
                latestVersionRaw = "v2026.04.02",
                downloadUrlRaw = ""
            )
        )
    }

    @Test
    fun resolveVersionUpdatePromptReturnsNullWhenLatestIsNotNewer() {
        val prompt = resolveVersionUpdatePrompt(
            currentVersionName = "v2026.04.10",
            latestVersionRaw = "v2026.04.01",
            downloadUrlRaw = "https://example.com/release"
        )

        assertNull(prompt)
    }

    @Test
    fun resolveVersionUpdatePromptReturnsPromptWhenLatestIsNewer() {
        val prompt = resolveVersionUpdatePrompt(
            currentVersionName = "v2026.04.01",
            latestVersionRaw = " v2026.04.15 ",
            downloadUrlRaw = " https://example.com/release "
        )

        assertEquals(
            VersionUpdatePrompt(
                latestVersion = "v2026.04.15",
                downloadUrl = "https://example.com/release"
            ),
            prompt
        )
    }
}
