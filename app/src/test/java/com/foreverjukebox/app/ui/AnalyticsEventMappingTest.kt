package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.ThemeMode
import com.foreverjukebox.app.visualization.visualizationLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsEventMappingTest {

    // These labels are the `viz` param values, shared verbatim with the web app. Adding a
    // seventh visualization is fine; renaming one of these six splits the GA4 dimension into
    // rows that never merge, and no backfill can repair it.
    @Test
    fun visualizationLabelsKeepTheSharedWebSpellings() {
        assertTrue(
            visualizationLabels.containsAll(
                listOf("Arc", "Classic", "Galaxy", "Grid", "Infinite", "Wave")
            )
        )
    }

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
    fun playTitleIsOmittedForOnDeviceTracksAndKeptForServerTracks() {
        assertNull(analyticsPlayTrackTitle("local-a3f3c0dc73c6476c9db95c227f9206f2", "My Song.mp3"))
        assertEquals("Never Gonna Give You Up", analyticsPlayTrackTitle("dQw4w9WgXcQ", " Never Gonna Give You Up "))
        assertNull(analyticsPlayTrackTitle("dQw4w9WgXcQ", "  "))
        assertNull(analyticsPlayTrackTitle(null, "My Song.mp3"))
    }

    // duration_min carries whole minutes as strings, matching the web picker values;
    // an enum rename or a new option with a non-web duration would split the dimension.
    @Test
    fun sleepTimerDurationMapsOptionsToWebMinuteStrings() {
        assertEquals("off", analyticsSleepTimerDuration(SleepTimerOption.Off))
        assertEquals("15", analyticsSleepTimerDuration(SleepTimerOption.Minutes15))
        assertEquals("30", analyticsSleepTimerDuration(SleepTimerOption.Minutes30))
        assertEquals("45", analyticsSleepTimerDuration(SleepTimerOption.Minutes45))
        assertEquals("60", analyticsSleepTimerDuration(SleepTimerOption.Hour1))
        assertEquals("120", analyticsSleepTimerDuration(SleepTimerOption.Hours2))
    }

    @Test
    fun themeModeMapsToWebThemeValues() {
        assertEquals("system", analyticsThemeValue(ThemeMode.System))
        assertEquals("light", analyticsThemeValue(ThemeMode.Light))
        assertEquals("dark", analyticsThemeValue(ThemeMode.Dark))
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
