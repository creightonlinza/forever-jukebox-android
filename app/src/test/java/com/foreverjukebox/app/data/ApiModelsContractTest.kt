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
            "src:youtube:dQw4w9WgXcQ",
            stableTrackIdFromAnalysis(response)
        )
    }

    @Test
    fun topSongItemBuildsStableIdFromSourceOrJobFallback() {
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
            "src:soundcloud:sc%3Aabc%2F123",
            stableTrackIdFromTopSong(sourceBacked)
        )
        assertEquals("job:job_2", stableTrackIdFromTopSong(jobBacked))
    }

    @Test
    fun appConfigDecodesAllowUserUrl() {
        val payload = """
            {
              "allow_user_upload": true,
              "allow_user_url": true,
              "allow_favorites_sync": false
            }
        """.trimIndent()

        val config = json.decodeFromString(AppConfigResponse.serializer(), payload)

        assertTrue(config.allowUserUpload)
        assertTrue(config.allowUserUrl)
        assertFalse(config.allowFavoritesSync)
    }

    @Test
    fun parseTrackStableIdSupportsLegacyFallbacks() {
        val parsedYoutube = parseTrackStableId("dQw4w9WgXcQ")
        val parsedJob = parseTrackStableId("job_abc")
        val parsedSource = parseTrackStableId("src:bandcamp:track%2F42")

        assertEquals("src:youtube:dQw4w9WgXcQ", parsedYoutube?.stableId)
        assertEquals("youtube", parsedYoutube?.sourceProvider)
        assertEquals("dQw4w9WgXcQ", parsedYoutube?.sourceId)

        assertEquals("job:job_abc", parsedJob?.stableId)
        assertEquals("job_abc", parsedJob?.jobId)

        assertEquals("bandcamp", parsedSource?.sourceProvider)
        assertEquals("track/42", parsedSource?.sourceId)
        assertNull(parsedSource?.jobId)
    }
}
