package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoPaneLayoutPolicyTest {

    @Test
    fun narrowWindowsKeepOneColumn() {
        assertFalse(isTwoPaneWindow(widthDp = 411f, heightDp = 891f))
        assertFalse(isTwoPaneWindow(widthDp = 839.9f, heightDp = 1200f))
    }

    @Test
    fun wideButShortWindowsKeepOneColumn() {
        assertFalse(isTwoPaneWindow(widthDp = 891f, heightDp = 411f))
        assertFalse(isTwoPaneWindow(widthDp = 1200f, heightDp = 479.9f))
    }

    @Test
    fun wideAndTallWindowsShowTwoPanesFromTheBoundary() {
        assertTrue(isTwoPaneWindow(widthDp = 840f, heightDp = 480f))
        assertTrue(isTwoPaneWindow(widthDp = 1280f, heightDp = 800f))
    }

    @Test
    fun leftPaneIsHalfTheWindowUpToTheMaximum() {
        assertEquals(420f, leftPaneWidthDp(840f))
        assertEquals(480f, leftPaneWidthDp(960f))
        assertEquals(480f, leftPaneWidthDp(1280f))
    }

    @Test
    fun playRequestKeepsTheCurrentTabOnlyInTwoPaneMode() {
        val twoPane = UiState(twoPane = true, activeTab = TabId.Search)
        val narrow = UiState(twoPane = false, activeTab = TabId.Search)

        assertEquals(TabId.Search, tabForLayout(TabId.Play, twoPane))
        assertEquals(TabId.Faq, tabForLayout(TabId.Faq, twoPane))
        assertEquals(TabId.Play, tabForLayout(TabId.Play, narrow))
    }

    @Test
    fun wideEntryKeepsABrowseTab() {
        assertEquals(
            TabId.Faq,
            tabForTwoPaneEntry(AppMode.Server, TabId.Faq, listOf(TabId.Top, TabId.Play))
        )
    }

    @Test
    fun wideEntryFromPlayUsesTheLastBrowseTabInHistory() {
        val history = listOf(TabId.Top, TabId.Play, TabId.Search, TabId.Play)

        assertEquals(
            TabId.Search,
            tabForTwoPaneEntry(AppMode.Server, TabId.Play, history)
        )
    }

    @Test
    fun wideEntryFromPlayWithoutBrowseHistoryUsesHome() {
        AppMode.entries.forEach { mode ->
            assertEquals(defaultTabForMode(mode), tabForTwoPaneEntry(mode, TabId.Play, emptyList()))
            assertEquals(defaultTabForMode(mode), tabForTwoPaneEntry(mode, TabId.Play, listOf(TabId.Play)))
        }
    }

    @Test
    fun narrowEntryKeepsTheTabWhenListenIsIdle() {
        assertEquals(TabId.Search, tabForNarrowEntry(UiState(activeTab = TabId.Search)))
    }

    @Test
    fun narrowEntryOpensListenWhenATrackIsLoadedLoadingOrFailed() {
        val busyPlaybacks = listOf(
            PlaybackState(audioLoaded = true, analysisLoaded = true),
            PlaybackState(loadingShown = true),
            PlaybackState(analysisInFlight = true),
            PlaybackState(analysisCalculating = true),
            PlaybackState(audioLoading = true),
            PlaybackState(analysisErrorMessage = "failed")
        )
        busyPlaybacks.forEach { playback ->
            assertEquals(
                TabId.Play,
                tabForNarrowEntry(UiState(activeTab = TabId.Search, playback = playback))
            )
        }
    }

    @Test
    fun narrowBackPopsTheLastEntryAsIs() {
        val history = ArrayDeque(listOf(TabId.Top, TabId.Play))

        assertEquals(TabId.Play, popBackTab(history, TabId.Search, twoPane = false))
        assertEquals(listOf(TabId.Top), history.toList())
    }

    @Test
    fun narrowBackSkipsTheTabShown() {
        val history = ArrayDeque(listOf(TabId.Top, TabId.Search))

        assertEquals(TabId.Top, popBackTab(history, TabId.Search, twoPane = false))
    }

    @Test
    fun listenResetKeepsTheLeftTabInTwoPane() {
        val wide = UiState(activeTab = TabId.Search, twoPane = true)

        assertEquals(TabId.Search, tabAfterListenReset(wide, TabId.Top))
        assertEquals(TabId.Top, tabAfterListenReset(wide.copy(twoPane = false), TabId.Top))
    }

    @Test
    fun twoPaneBackSkipsPlayAndTheTabShown() {
        val history = ArrayDeque(listOf(TabId.Top, TabId.Play, TabId.Search, TabId.Play))

        assertEquals(TabId.Top, popBackTab(history, TabId.Search, twoPane = true))
        assertTrue(history.isEmpty())
    }

    @Test
    fun twoPaneBackWithOnlyPlayLeftReturnsNullAndEmptiesHistory() {
        val history = ArrayDeque(listOf(TabId.Play, TabId.Play))

        assertNull(popBackTab(history, TabId.Top, twoPane = true))
        assertTrue(history.isEmpty())
        assertNull(popBackTab(ArrayDeque(), TabId.Top, twoPane = true))
    }
}
