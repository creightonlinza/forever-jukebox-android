package com.foreverjukebox.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelsContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun analysisResponsePrefersJobIdForReusableTrackIdentity() {
        val payload = """
            {
              "id": "a3f3c0dc73c6476c9db95c227f9206f2",
              "status": "queued",
              "source_provider": "youtube",
              "source_id": "dQw4w9WgXcQ",
              "youtube_id": "legacy"
            }
        """.trimIndent()

        val response = json.decodeFromString(AnalysisResponse.serializer(), payload)

        assertEquals("a3f3c0dc73c6476c9db95c227f9206f2", response.id)
        assertEquals("youtube", response.sourceProvider)
        assertEquals("dQw4w9WgXcQ", response.sourceId)
        assertEquals("legacy", response.youtubeId)
        assertEquals(
            "a3f3c0dc73c6476c9db95c227f9206f2",
            trackIdFromAnalysis(response)
        )
    }

    @Test
    fun analysisResponseDoesNotFallbackToYoutubeIdentityWhenJobIdMissing() {
        val response = AnalysisResponse(
            sourceProvider = "youtube",
            sourceId = "dQw4w9WgXcQ",
            youtubeId = "legacy"
        )

        assertNull(trackIdFromAnalysis(response))
    }

    @Test
    fun analysisStartResponseDecodesFailedDisplayFields() {
        val payload = """
            {
              "status": "failed",
              "source_provider": "youtube",
              "error": "Unable to download video data.",
              "error_code": "download_unavailable"
            }
        """.trimIndent()

        val response = json.decodeFromString(AnalysisStartResponse.serializer(), payload)

        assertEquals("failed", response.status)
        assertEquals("youtube", response.sourceProvider)
        assertEquals("Unable to download video data.", response.error)
        assertEquals("download_unavailable", response.errorCode)
    }

    @Test
    fun topSongItemPrefersJobIdWhenPresent() {
        val youtubeBacked = TopSongItem(
            id = "a3f3c0dc73c6476c9db95c227f9206f2",
            sourceProvider = "youtube",
            sourceId = "dQw4w9WgXcQ",
            youtubeId = "legacy",
            title = "Track"
        )
        val sourceBacked = TopSongItem(
            id = "0123456789abcdef0123456789abcdef",
            sourceProvider = "soundcloud",
            sourceId = "sc:abc/123",
            title = "Track"
        )
        val jobBacked = TopSongItem(
            id = "ffffffffffffffffffffffffffffffff",
            sourceProvider = "upload",
            sourceId = null,
            title = "Upload"
        )

        assertEquals("a3f3c0dc73c6476c9db95c227f9206f2", trackIdFromTopSong(youtubeBacked))
        assertEquals(
            "0123456789abcdef0123456789abcdef",
            trackIdFromTopSong(sourceBacked)
        )
        assertEquals("ffffffffffffffffffffffffffffffff", trackIdFromTopSong(jobBacked))
    }

    @Test
    fun topSongItemDoesNotFallbackToYoutubeIdentityWhenJobIdMissing() {
        val sourceBacked = TopSongItem(
            sourceProvider = "youtube",
            sourceId = "dQw4w9WgXcQ",
            youtubeId = "legacy",
            title = "Track"
        )
        val legacyBacked = TopSongItem(
            youtubeId = "legacy12345",
            title = "Legacy Track"
        )

        assertNull(trackIdFromTopSong(sourceBacked))
        assertNull(trackIdFromTopSong(legacyBacked))
    }

    @Test
    fun youtubeTrackIdFromTopSongIgnoresJobIdAndReturnsYoutubeIdentityOnly() {
        val youtubeBacked = TopSongItem(
            id = "a3f3c0dc73c6476c9db95c227f9206f2",
            sourceProvider = "youtube",
            sourceId = "dQw4w9WgXcQ",
            youtubeId = "legacy",
            title = "Track"
        )
        val nonYoutubeBacked = TopSongItem(
            id = "0123456789abcdef0123456789abcdef",
            sourceProvider = "soundcloud",
            sourceId = "sc:abc/123",
            title = "Track"
        )

        assertEquals("dQw4w9WgXcQ", youtubeTrackIdFromTopSong(youtubeBacked))
        assertNull(youtubeTrackIdFromTopSong(nonYoutubeBacked))
    }

    @Test
    fun appConfigDecodesAllowUserUrl() {
        val payload = """
            {
              "allow_user_upload": true,
              "allow_user_url": true,
              "allow_favorites_sync": false,
              "max_favorites": 175
            }
        """.trimIndent()

        val config = json.decodeFromString(AppConfigResponse.serializer(), payload)

        assertTrue(config.allowUserUpload)
        assertTrue(config.allowUserUrl)
        assertFalse(config.allowFavoritesSync)
        assertEquals(175, config.maxFavorites)
    }

    @Test
    fun appConfigDefaultsMaxFavoritesWhenMissing() {
        val payload = """
            {
              "allow_user_upload": true,
              "allow_user_url": true,
              "allow_favorites_sync": false
            }
        """.trimIndent()

        val config = json.decodeFromString(AppConfigResponse.serializer(), payload)

        assertEquals(DEFAULT_MAX_FAVORITES, config.maxFavorites)
    }

    @Test
    fun parseTrackIdSupportsYoutubeAndJobIdsOnly() {
        val parsedYoutube = parseTrackId("dQw4w9WgXcQ")
        val parsedJob = parseTrackId("a3f3c0dc73c6476c9db95c227f9206f2")
        val parsedProvider = parseTrackId("soundcloud:abc123")
        val parsedSource = parseTrackId("src:bandcamp:track%2F42")

        assertEquals("dQw4w9WgXcQ", parsedYoutube?.trackId)
        assertEquals("dQw4w9WgXcQ", parsedYoutube?.youtubeId)

        assertEquals("a3f3c0dc73c6476c9db95c227f9206f2", parsedJob?.trackId)
        assertEquals("a3f3c0dc73c6476c9db95c227f9206f2", parsedJob?.jobId)

        assertNull(parsedProvider)

        assertNull(parsedSource)
    }

    @Test
    fun favoriteUniqueSongIdFromTrackIdReturnsSimpleIds() {
        assertEquals("dQw4w9WgXcQ", favoriteUniqueSongIdFromTrackId("dQw4w9WgXcQ"))
        assertEquals(
            "a3f3c0dc73c6476c9db95c227f9206f2",
            favoriteUniqueSongIdFromTrackId("a3f3c0dc73c6476c9db95c227f9206f2")
        )
        assertNull(favoriteUniqueSongIdFromTrackId("src:youtube:"))
    }
}
