package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLayoutTest {

    @Test
    fun twoPaneNavBarLeavesOutJukeboxOnly() {
        assertEquals(listOf(TabId.Input, TabId.Faq), navBarTabs(AppMode.Local, twoPane = true))
        assertEquals(tabsForMode(AppMode.Local), navBarTabs(AppMode.Local, twoPane = false))
        assertEquals(tabsForMode(AppMode.Server) - TabId.Play, navBarTabs(AppMode.Server, twoPane = true))
    }

    @Test
    fun twoPaneLeftPaneNeverShowsPlay() {
        assertNull(layoutBodyTab(TabId.Play, twoPane = true, reportedTwoPane = false, hasListenContent = true))
        assertEquals(
            TabId.Search,
            layoutBodyTab(TabId.Search, twoPane = true, reportedTwoPane = false, hasListenContent = true)
        )
        assertEquals(
            TabId.Faq,
            layoutBodyTab(TabId.Faq, twoPane = true, reportedTwoPane = true, hasListenContent = false)
        )
    }

    @Test
    fun narrowingFrameAnticipatesPlayOnlyWithListenContent() {
        assertEquals(
            TabId.Play,
            layoutBodyTab(TabId.Search, twoPane = false, reportedTwoPane = true, hasListenContent = true)
        )
        assertEquals(
            TabId.Search,
            layoutBodyTab(TabId.Search, twoPane = false, reportedTwoPane = true, hasListenContent = false)
        )
    }

    @Test
    fun oneColumnShowsActiveTabOnceViewModelAgrees() {
        TabId.entries.forEach { tab ->
            assertEquals(tab, layoutBodyTab(tab, twoPane = false, reportedTwoPane = false, hasListenContent = true))
        }
    }
}
