package com.foreverjukebox.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPlaylistPreferencesTest {

    private val tracks = listOf(
        SavedPlaylistTrack(
            id = "yt:one",
            type = SavedPlaylistTrackType.Server,
            title = "One",
            artist = "Artist",
            tuningParams = "jb=1&thresh=7"
        ),
        SavedPlaylistTrack(
            id = "local-two",
            type = SavedPlaylistTrackType.LocalCached,
            title = "Two",
            artist = null,
            tuningParams = null
        )
    )

    @Test
    fun encodeDecodeSavedPlaylistKeepsTracksAndLastIndex() {
        val playlist = SavedPlaylist(tracks = tracks, lastIndex = 1)

        val decoded = decodeSavedPlaylist(encodeSavedPlaylist(playlist))

        assertEquals(playlist, decoded)
    }

    @Test
    fun encodeDecodeSavedPlaylistRoundTripsAbsentLastIndex() {
        val playlist = SavedPlaylist(tracks = tracks)

        val decoded = decodeSavedPlaylist(encodeSavedPlaylist(playlist))

        assertEquals(tracks, decoded.tracks)
        assertNull(decoded.lastIndex)
    }

    @Test
    fun decodeSavedPlaylistAcceptsLegacyTrackArray() {
        val legacy = """[{"id":"yt:one","type":"Server","title":"One","artist":"Artist","tuningParams":"jb=1&thresh=7"},""" +
            """{"id":"local-two","type":"LocalCached","title":"Two"}]"""

        val decoded = decodeSavedPlaylist(legacy)

        assertEquals(tracks, decoded.tracks)
        assertNull(decoded.lastIndex)
    }

    @Test
    fun decodeSavedPlaylistFallsBackToEmptyOnMalformedJson() {
        assertTrue(decodeSavedPlaylist("{not-json").tracks.isEmpty())
        assertTrue(decodeSavedPlaylist("[not-json").tracks.isEmpty())
        assertTrue(decodeSavedPlaylist(null).tracks.isEmpty())
    }
}
