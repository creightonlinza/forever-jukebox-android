package com.foreverjukebox.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPlaylistPreferencesTest {

    @Test
    fun encodeDecodeSavedPlaylistKeepsTuningParams() {
        val tracks = listOf(
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

        val decoded = decodeSavedPlaylistTracks(encodeSavedPlaylistTracks(tracks))

        assertEquals(tracks, decoded)
    }

    @Test
    fun decodeSavedPlaylistFallsBackToEmptyOnMalformedJson() {
        val decoded = decodeSavedPlaylistTracks("{not-json")

        assertTrue(decoded.isEmpty())
    }
}
