package com.foreverjukebox.app.ui

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
        assertTrue(shouldShowPlaylistControls(playlist))
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
    fun appendAndSelectAddsNewTrackAtEnd() {
        val current = track("current")
        val next = track("next")
        val extra = track("extra")

        val playlist = initializePlaylist(current, next).appendAndSelectTrack(extra)

        assertEquals(listOf(current, next, extra), playlist.tracks)
        assertEquals(2, playlist.currentIndex)
        assertEquals(extra, playlist.currentTrack())
    }

    @Test
    fun appendAndSelectMovesCurrentIndexForDuplicate() {
        val current = track("current")
        val next = track("next")

        val playlist = initializePlaylist(current, next).appendAndSelectTrack(next)

        assertEquals(listOf(current, next), playlist.tracks)
        assertEquals(1, playlist.currentIndex)
        assertEquals(next, playlist.currentTrack())
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
        val playlist = initializePlaylist(current, next)
            .appendAndSelectTrack(extra)

        val updated = playlist.removeTrackAt(1)

        assertEquals(listOf(current, extra), updated.tracks)
        assertEquals(1, updated.currentIndex)
        assertEquals(extra, updated.currentTrack())
    }

    private fun track(
        id: String,
        type: PlaylistTrackType = PlaylistTrackType.Server
    ): PlaylistTrack {
        return PlaylistTrack(
            id = id,
            type = type,
            title = id,
            artist = "Artist"
        )
    }
}
