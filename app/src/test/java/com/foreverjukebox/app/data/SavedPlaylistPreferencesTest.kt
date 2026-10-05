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

    @Test
    fun decodeSavedPlaylistKeepsUnknownPlayModeVerbatim() {
        val raw = """{"tracks":[{"id":"yt:one","type":"Server","title":"One","playMode":"futuremode"}],"lastIndex":0}"""

        val decoded = decodeSavedPlaylist(raw)

        assertEquals(1, decoded.tracks.size)
        assertEquals(FavoritePlayMode("futuremode"), decoded.tracks.first().playMode)
        assertEquals(0, decoded.lastIndex)
    }

    @Test
    fun decodeSavedPlaylistDropsOnlyTheMalformedEntry() {
        val raw = """{"tracks":[{"id":"yt:one","type":"Server","title":"One"},""" +
            """{"id":"bad","type":"Stream"},""" +
            """{"type":"Server"},""" +
            """{"id":"local-two","type":"LocalCached"}],"lastIndex":2}"""

        val decoded = decodeSavedPlaylist(raw)

        assertEquals(listOf("yt:one", "local-two"), decoded.tracks.map { it.id })
        // The resume index pointed at a dropped entry and now lands on the one that followed it.
        assertEquals(1, decoded.lastIndex)
    }

    @Test
    fun decodeSavedPlaylistMovesTheResumeIndexWithItsEntry() {
        val tracks = """[{"id":"yt:one","type":"Server"},{"id":"bad","type":"Stream"},{"id":"local-two","type":"LocalCached"}]"""

        assertEquals(1, decodeSavedPlaylist("""{"tracks":$tracks,"lastIndex":2}""").lastIndex)
        assertEquals(0, decodeSavedPlaylist("""{"tracks":$tracks,"lastIndex":0}""").lastIndex)
        assertEquals(-1, decodeSavedPlaylist("""{"tracks":$tracks,"lastIndex":-1}""").lastIndex)
        assertNull(decodeSavedPlaylist("""{"tracks":[{"id":"bad","type":"Stream"}],"lastIndex":0}""").lastIndex)
    }

    @Test
    fun decodeSavedPlaylistLegacyArrayDropsOnlyTheMalformedEntry() {
        val legacy = """[{"id":"yt:one","type":"Server"},{"id":"bad","type":"Stream"}]"""

        val decoded = decodeSavedPlaylist(legacy)

        assertEquals(listOf("yt:one"), decoded.tracks.map { it.id })
    }
}
