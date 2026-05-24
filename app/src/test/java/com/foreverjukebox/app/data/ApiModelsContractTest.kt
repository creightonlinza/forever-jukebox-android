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
    fun analysisResponsePrefersSourceIdentityFields() {
        val payload = """
            {
              "id": "job_1",
              "status": "queued",
              "source_provider": "youtube",
              "source_id": "dQw4w9WgXcQ",
              "youtube_id": "legacy"
            }
        """.trimIndent()

        val response = json.decodeFromString(AnalysisResponse.serializer(), payload)

        assertEquals("job_1", response.id)
        assertEquals("youtube", response.sourceProvider)
        assertEquals("dQw4w9WgXcQ", response.sourceId)
        assertEquals("legacy", response.youtubeId)
        assertEquals(
            "dQw4w9WgXcQ",
            trackIdFromAnalysis(response)
        )
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
    fun topSongItemBuildsTrackIdFromYoutubeOrJobFallback() {
        val sourceBacked = TopSongItem(
            id = "job_1",
            sourceProvider = "soundcloud",
            sourceId = "sc:abc/123",
            title = "Track"
        )
        val jobBacked = TopSongItem(
            id = "job_2",
            sourceProvider = "upload",
            sourceId = null,
            title = "Upload"
        )

        assertEquals(
            "job_1",
            trackIdFromTopSong(sourceBacked)
        )
        assertEquals("job_2", trackIdFromTopSong(jobBacked))
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
        val parsedJob = parseTrackId("job_abc")
        val parsedProvider = parseTrackId("soundcloud:abc123")
        val parsedSource = parseTrackId("src:bandcamp:track%2F42")

        assertEquals("dQw4w9WgXcQ", parsedYoutube?.trackId)
        assertEquals("dQw4w9WgXcQ", parsedYoutube?.youtubeId)

        assertEquals("job_abc", parsedJob?.trackId)
        assertEquals("job_abc", parsedJob?.jobId)

        assertNull(parsedProvider?.youtubeId)
        assertEquals("soundcloud:abc123", parsedProvider?.jobId)

        assertNull(parsedSource?.youtubeId)
        assertEquals("src:bandcamp:track%2F42", parsedSource?.jobId)
    }

    @Test
    fun favoriteUniqueSongIdFromTrackIdReturnsSimpleIds() {
        assertEquals("dQw4w9WgXcQ", favoriteUniqueSongIdFromTrackId("dQw4w9WgXcQ"))
        assertEquals("job_abc", favoriteUniqueSongIdFromTrackId("job_abc"))
        assertEquals("src:youtube:", favoriteUniqueSongIdFromTrackId("src:youtube:"))
    }
}
