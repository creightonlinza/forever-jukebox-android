package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTest {

    @Test
    fun emptyPlaylistIsNotInitialized() {
        val playlist = JukeboxPlaylistState()

        assertFalse(playlist.isInitialized())
        assertFalse(playlist.isActive())
        assertFalse(shouldShowPlaylistControls(playlist))
        assertNull(playlist.currentTrack())
    }

    @Test
    fun initializePlaylistKeepsCurrentAsFirstTrack() {
        val current = track("current")
        val next = track("next")

        val playlist = initializePlaylist(current, next)

        assertTrue(playlist.isInitialized())
        assertEquals(listOf(current, next), playlist.tracks)
        assertEquals(0, playlist.currentIndex)
        assertEquals(current, playlist.currentTrack())
        assertTrue(playlist.isActive())
        assertTrue(shouldShowPlaylistControls(playlist))
        assertTrue(shouldShowActivePlaylistControls(playlist))
    }

    @Test
    fun appendTrackDoesNotSelectNewTrack() {
        val current = track("current")
        val next = track("next")
        val extra = track("extra")

        val playlist = initializePlaylist(current, next).appendTrack(extra)

        assertEquals(listOf(current, next, extra), playlist.tracks)
        assertEquals(0, playlist.currentIndex)
    }

    @Test
    fun appendTrackIgnoresDuplicate() {
        val current = track("current")
        val next = track("next")

        val playlist = initializePlaylist(current, next).appendTrack(next)

        assertEquals(listOf(current, next), playlist.tracks)
        assertEquals(0, playlist.currentIndex)
    }

    @Test
    fun appendTrackDoesNotGrowBeyondMaxPlaylistTracks() {
        val tracks = (1..MAX_PLAYLIST_TRACKS).map { track("track-$it") }
        val playlist = JukeboxPlaylistState(tracks = tracks, currentIndex = 0)

        val updated = playlist.appendTrack(track("extra"))

        assertEquals(tracks, updated.tracks)
        assertEquals(0, updated.currentIndex)
    }

    @Test
    fun replaceCurrentTrackWithNewTrackKeepsCurrentSlot() {
        val first = track("A")
        val current = track("B")
        val last = track("C")
        val replacement = track("D")
        val playlist = JukeboxPlaylistState(
            tracks = listOf(first, current, last),
            currentIndex = 1
        )

        val updated = playlist.replaceCurrentTrackWith(replacement)

        assertEquals(listOf(first, replacement, last), updated.tracks)
        assertEquals(1, updated.currentIndex)
        assertEquals(replacement, updated.currentTrack())
    }

    @Test
    fun replaceCurrentTrackWithDuplicateAfterCurrentMergesIntoCurrentSlot() {
        val first = track("A")
        val current = track("B")
        val middle = track("C")
        val duplicate = track("D", tuningParams = "jb=duplicate")
        val playlist = JukeboxPlaylistState(
            tracks = listOf(first, current, middle, duplicate),
            currentIndex = 1
        )

        val updated = playlist.replaceCurrentTrackWith(track("D", tuningParams = "ignored"))

        assertEquals(listOf(first, duplicate, middle), updated.tracks)
        assertEquals(1, updated.currentIndex)
        assertEquals(duplicate, updated.currentTrack())
    }

    @Test
    fun replaceCurrentTrackWithDuplicateBeforeCurrentPreservesValidCurrentIndex() {
        val first = track("A")
        val duplicate = track("D", tuningParams = "jb=duplicate")
        val current = track("B")
        val last = track("C")
        val playlist = JukeboxPlaylistState(
            tracks = listOf(first, duplicate, current, last),
            currentIndex = 2
        )

        val updated = playlist.replaceCurrentTrackWith(track("D", tuningParams = "ignored"))

        assertEquals(listOf(first, duplicate, last), updated.tracks)
        assertEquals(1, updated.currentIndex)
        assertEquals(duplicate, updated.currentTrack())
    }

    @Test
    fun replaceCurrentTrackWithCurrentDuplicateLeavesPlaylistUnchanged() {
        val playlist = JukeboxPlaylistState(
            tracks = listOf(track("A"), track("B"), track("C")),
            currentIndex = 1
        )

        val updated = playlist.replaceCurrentTrackWith(track("B"))

        assertEquals(playlist, updated)
    }

    @Test
    fun skipAvailabilityTracksCurrentIndex() {
        val playlist = initializePlaylist(track("current"), track("next"))

        assertFalse(playlist.canSkipPrevious())
        assertTrue(playlist.canSkipNext())

        val selectedNext = playlist.selectTrackAt(1)

        assertTrue(selectedNext.canSkipPrevious())
        assertFalse(selectedNext.canSkipNext())
    }

    @Test
    fun skipAvailabilityFollowsOrderAfterReplacement() {
        val playlist = JukeboxPlaylistState(
            tracks = listOf(track("A"), track("B"), track("C")),
            currentIndex = 1
        ).replaceCurrentTrackWith(track("D"))

        assertTrue(playlist.canSkipPrevious())
        assertTrue(playlist.canSkipNext())
        assertEquals(track("A"), playlist.selectTrackAt(0).currentTrack())
        assertEquals(track("C"), playlist.selectTrackAt(2).currentTrack())
    }

    @Test
    fun shouldAdvancePlaylistOnAutocanonizerEndRequiresNextTrack() {
        val playlist = JukeboxPlaylistState(
            tracks = listOf(track("A"), track("B"), track("C")),
            currentIndex = 1
        )

        assertTrue(
            shouldAdvancePlaylistOnAutocanonizerEnd(
                UiState(
                    playback = PlaybackState(playMode = PlaybackMode.Autocanonizer),
                    playlist = playlist
                )
            )
        )
        assertFalse(
            shouldAdvancePlaylistOnAutocanonizerEnd(
                UiState(
                    playback = PlaybackState(playMode = PlaybackMode.Autocanonizer),
                    playlist = playlist.copy(currentIndex = 2)
                )
            )
        )
        assertFalse(
            shouldAdvancePlaylistOnAutocanonizerEnd(
                UiState(
                    playback = PlaybackState(playMode = PlaybackMode.Jukebox),
                    playlist = playlist
                )
            )
        )
    }

    @Test
    fun restoredSavedPlaylistIsInactiveWithNoSkipAvailability() {
        val playlist = JukeboxPlaylistState(
            tracks = listOf(track("one"), track("two", tuningParams = "jb=1&thresh=7")),
            currentIndex = -1
        )

        assertTrue(playlist.isInitialized())
        assertFalse(playlist.isActive())
        assertTrue(playlist.isInactiveSavedPlaylist())
        assertNull(playlist.currentTrack())
        assertFalse(playlist.canSkipPrevious())
        assertFalse(playlist.canSkipNext())
        assertTrue(shouldShowPlaylistControls(playlist))
        assertFalse(shouldShowActivePlaylistControls(playlist))
        assertEquals("jb=1&thresh=7", playlist.tracks[1].tuningParams)
    }

    @Test
    fun selectingInactiveSavedPlaylistTrackActivatesIt() {
        val playlist = JukeboxPlaylistState(
            tracks = listOf(track("one"), track("two", tuningParams = "am=nightcore")),
            currentIndex = -1
        )

        val selected = playlist.selectTrackAt(1)

        assertTrue(selected.isActive())
        assertEquals(1, selected.currentIndex)
        assertEquals("am=nightcore", selected.currentTrack()?.tuningParams)
    }

    @Test
    fun localAndServerTracksWithSameIdAreDistinct() {
        val server = track("same", PlaylistTrackType.Server)
        val local = track("same", PlaylistTrackType.LocalCached)

        val playlist = initializePlaylist(server, local)

        assertEquals(listOf(server, local), playlist.tracks)
    }

    @Test
    fun removeTrackAtRefusesCurrentTrack() {
        val playlist = initializePlaylist(track("current"), track("next"))

        val updated = playlist.removeTrackAt(0)

        assertEquals(playlist, updated)
        assertFalse(playlist.canRemoveTrackAt(0))
    }

    @Test
    fun removeTrackAtDropsPlaylistWhenOnlyActiveTrackRemains() {
        val playlist = initializePlaylist(track("current"), track("next"))

        val updated = playlist.removeTrackAt(1)

        assertEquals(JukeboxPlaylistState(), updated)
        assertFalse(shouldShowPlaylistControls(updated))
    }

    @Test
    fun removeTrackAtAdjustsCurrentIndexWhenRemovingBeforeCurrent() {
        val current = track("current")
        val next = track("next")
        val extra = track("extra")
        val playlist = JukeboxPlaylistState(
            tracks = listOf(current, next, extra),
            currentIndex = 2
        )

        val updated = playlist.removeTrackAt(1)

        assertEquals(listOf(current, extra), updated.tracks)
        assertEquals(1, updated.currentIndex)
        assertEquals(extra, updated.currentTrack())
    }

    @Test
    fun inactivePlaylistCanRemoveAnyTrackAndPersistsRemainingTuning() {
        val first = track("first", tuningParams = "jb=1")
        val second = track("second", tuningParams = "thresh=9")
        val third = track("third", tuningParams = "am=lofi")
        val playlist = JukeboxPlaylistState(
            tracks = listOf(first, second, third),
            currentIndex = -1
        )

        val updated = playlist.removeTrackAt(1)

        assertEquals(listOf(first, third), updated.tracks)
        assertEquals(-1, updated.currentIndex)
        assertEquals("am=lofi", updated.tracks[1].tuningParams)
    }

    @Test
    fun savedPlaylistMappingKeepsTuningParams() {
        val track = track("favorite", tuningParams = "jb=1&thresh=7")

        val restored = track.toSavedPlaylistTrack().toPlaylistTrack()

        assertEquals(track, restored)
    }

    @Test
    fun playablePlaylistTracksFilterByModeAndLocalCache() {
        val server = track("server", PlaylistTrackType.Server)
        val cached = track("cached", PlaylistTrackType.LocalCached)
        val missing = track("missing", PlaylistTrackType.LocalCached)
        val tracks = listOf(server, cached, missing)

        assertEquals(listOf(server), playablePlaylistTracks(tracks, AppMode.Server, emptyList()))
        assertEquals(
            listOf(cached),
            playablePlaylistTracks(
                tracks = tracks,
                appMode = AppMode.Local,
                localCachedTracks = listOf(
                    LocalCachedTrack(
                        localId = "cached",
                        title = "Cached",
                        artist = null,
                        sourceUri = "content://cached"
                    )
                )
            )
        )
    }

    private fun track(
        id: String,
        type: PlaylistTrackType = PlaylistTrackType.Server,
        tuningParams: String? = null
    ): PlaylistTrack {
        return PlaylistTrack(
            id = id,
            type = type,
            title = id,
            artist = "Artist",
            tuningParams = tuningParams
        )
    }
}
