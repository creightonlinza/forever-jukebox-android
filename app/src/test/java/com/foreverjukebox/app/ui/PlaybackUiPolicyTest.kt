package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackUiPolicyTest {

    @Test
    fun playbackTransportContentDescriptionMatchesPlaybackState() {
        assertEquals("Pause", playbackTransportContentDescription(PlaybackState(isRunning = true)))
        assertEquals("Resume", playbackTransportContentDescription(PlaybackState(isPaused = true)))
        assertEquals("Play", playbackTransportContentDescription(PlaybackState()))
    }

    @Test
    fun playAfterLoadedOptionShowsForLocalAndServerLoadingOnly() {
        val loading = PlaybackState(analysisInFlight = true)

        assertTrue(shouldShowPlayAfterLoadedOption(AppMode.Local, loading))
        assertTrue(shouldShowPlayAfterLoadedOption(AppMode.Server, loading))
        assertFalse(shouldShowPlayAfterLoadedOption(null, loading))
        assertFalse(shouldShowPlayAfterLoadedOption(AppMode.Local, PlaybackState()))
        assertFalse(
            shouldShowPlayAfterLoadedOption(
                AppMode.Server,
                loading.copy(isCasting = true)
            )
        )
        assertFalse(
            shouldShowPlayAfterLoadedOption(
                AppMode.Local,
                PlaybackState(analysisErrorMessage = "boom")
            )
        )
    }

    @Test
    fun playAfterLoadedStartsOnlyWhenCheckedAndStableReady() {
        val ready = PlaybackState(
            playAfterLoaded = true,
            audioLoaded = true,
            analysisLoaded = true
        )

        assertTrue(shouldStartPlayAfterLoaded(ready))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(playAfterLoaded = false)))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(analysisInFlight = true)))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(audioLoading = true)))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(analysisCalculating = true)))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(isCasting = true)))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(isRunning = true)))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(analysisErrorMessage = "boom")))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(audioLoaded = false)))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(analysisLoaded = false)))
    }

    @Test
    fun playlistSkipEnablesPlayAfterLoadedForActiveLocalPlaylist() {
        val playlist = JukeboxPlaylistState(
            tracks = listOf(
                PlaylistTrack("one", PlaylistTrackType.Server, "One", null),
                PlaylistTrack("two", PlaylistTrackType.Server, "Two", null)
            ),
            currentIndex = 0
        )
        val active = UiState(
            playlist = playlist,
            playback = PlaybackState(playMode = PlaybackMode.Jukebox)
        )

        assertTrue(shouldEnablePlayAfterLoadedForPlaylistSkip(active))
        assertFalse(
            shouldEnablePlayAfterLoadedForPlaylistSkip(
                active.copy(playback = active.playback.copy(isCasting = true))
            )
        )
        assertFalse(
            shouldEnablePlayAfterLoadedForPlaylistSkip(
                active.copy(playlist = playlist.copy(tracks = playlist.tracks.take(1)))
            )
        )
    }

    @Test
    fun savedPlaylistButtonShowsOnlyForInactivePlaylistOnEmptyListenScreen() {
        val inactivePlaylist = JukeboxPlaylistState(
            tracks = listOf(
                PlaylistTrack("one", PlaylistTrackType.Server, "One", null),
                PlaylistTrack("two", PlaylistTrackType.Server, "Two", null)
            ),
            currentIndex = -1
        )
        val empty = UiState(playlist = inactivePlaylist, playback = PlaybackState())

        assertTrue(shouldShowSavedPlaylistButton(empty))
        assertFalse(
            shouldShowSavedPlaylistButton(
                empty.copy(playback = PlaybackState(audioLoaded = true, analysisLoaded = true))
            )
        )
        assertFalse(
            shouldShowSavedPlaylistButton(
                empty.copy(playlist = inactivePlaylist.copy(currentIndex = 0))
            )
        )
        assertFalse(
            shouldShowSavedPlaylistButton(
                empty.copy(playlist = inactivePlaylist.copy(tracks = inactivePlaylist.tracks.take(1)))
            )
        )
    }

    @Test
    fun activePlaylistControlsHideForInactiveSavedPlaylist() {
        val inactivePlaylist = JukeboxPlaylistState(
            tracks = listOf(
                PlaylistTrack("one", PlaylistTrackType.Server, "One", null),
                PlaylistTrack("two", PlaylistTrackType.Server, "Two", null)
            ),
            currentIndex = -1
        )

        assertTrue(shouldShowPlaylistControls(inactivePlaylist))
        assertFalse(shouldShowActivePlaylistControls(inactivePlaylist))
        assertTrue(shouldShowActivePlaylistControls(inactivePlaylist.copy(currentIndex = 0)))
    }
}
