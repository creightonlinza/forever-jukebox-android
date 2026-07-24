package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalyticsEventMappingTest {

    @Test
    fun playModeMapsToWebEventValues() {
        assertEquals("jukebox", analyticsPlayMode(PlaybackMode.Jukebox))
        assertEquals("autocanonizer", analyticsPlayMode(PlaybackMode.Autocanonizer))
    }

    @Test
    fun selectSourceMapsTopSongsTabsToWebSourceValues() {
        assertEquals("top", analyticsSelectSource(TopSongsTab.TopSongs))
        assertEquals("trending", analyticsSelectSource(TopSongsTab.Trending))
        assertEquals("recent", analyticsSelectSource(TopSongsTab.Recent))
        assertEquals("favorites", analyticsSelectSource(TopSongsTab.Favorites))
    }

    @Test
    fun searchResultTitleUsesWebNameEmDashArtistFormat() {
        assertEquals("Song — Artist", analyticsSearchResultTitle("Song", "Artist"))
        assertEquals("Song", analyticsSearchResultTitle("Song", ""))
        assertEquals("Song", analyticsSearchResultTitle(" Song ", null))
        assertNull(analyticsSearchResultTitle("", "Artist"))
        assertNull(analyticsSearchResultTitle(null, null))
    }

    @Test
    fun playTrackIdPrefersJobIdThenFallsBackToYoutubeId() {
        val withJobId = PlaybackState(lastJobId = "abc123", lastYouTubeId = "dQw4w9WgXcQ")
        val youtubeOnly = PlaybackState(lastJobId = null, lastYouTubeId = "dQw4w9WgXcQ")
        val blankIds = PlaybackState(lastJobId = " ", lastYouTubeId = " ")

        assertEquals("abc123", withJobId.analyticsPlayTrackId())
        assertEquals("dQw4w9WgXcQ", youtubeOnly.analyticsPlayTrackId())
        assertNull(blankIds.analyticsPlayTrackId())
    }
}
