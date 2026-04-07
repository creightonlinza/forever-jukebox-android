package com.foreverjukebox.app.data

import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiClientPublicApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ApiClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun searchSpotifyUsesExpectedPathAndQueryAndParsesItems() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "items": [
                        {
                          "id": "sp1",
                          "name": "Around the World",
                          "artist": "Daft Punk",
                          "duration": 431.0
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val baseUrl = server.url("/base/").toString()
        val result = api.searchSpotify(baseUrl = baseUrl, query = "daft punk")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/base/api/search/spotify?q=daft%20punk", request.path)
        assertEquals(1, result.size)
        assertEquals("sp1", result.first().id)
        assertEquals("Around the World", result.first().name)
    }

    @Test
    fun createFavoritesSyncTrimsPayloadToServerLimit() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":"abc123","count":100,"favorites":[]}""")
        )
        val baseUrl = server.url("/api/").toString()
        val favorites = (1..105).map { index ->
            FavoriteTrack(
                uniqueSongId = "song_$index",
                title = "Song $index",
                artist = "Artist $index"
            )
        }

        val response = api.createFavoritesSync(baseUrl = baseUrl, favorites = favorites)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/api/favorites/sync", request.path)
        val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val payloadFavorites = payload.getValue("favorites").jsonArray
        assertEquals(100, payloadFavorites.size)
        assertEquals("song_1", payloadFavorites.first().jsonObject.getValue("uniqueSongId").toString().trim('"'))
        assertEquals("abc123", response.code)
    }

    @Test
    fun fetchTopSongsThrowsIoExceptionForHttpError() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("boom")
        )

        val baseUrl = server.url("/").toString()
        val result = runCatching {
            api.fetchTopSongs(baseUrl)
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun getAppConfigRejectsInvalidBaseUrl() = runTest {
        val result = runCatching {
            api.getAppConfig("not-a-url")
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun getJobByYoutubeReturnsNullOn404WithoutRepair() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("not found")
        )

        val baseUrl = server.url("/base/").toString()
        val result = api.getJobByYoutube(baseUrl = baseUrl, youtubeId = "dQw4w9WgXcQ")

        assertNull(result)
        val lookup = server.takeRequest()
        assertEquals("GET", lookup.method)
        assertEquals("/base/api/jobs/by-youtube/dQw4w9WgXcQ", lookup.path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun getJobByTrackReturnsNullOn404WithoutRepair() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("not found")
        )

        val baseUrl = server.url("/base/").toString()
        val result = api.getJobByTrack(baseUrl = baseUrl, title = "Track", artist = "Artist")

        assertNull(result)
        val lookup = server.takeRequest()
        assertEquals("GET", lookup.method)
        assertEquals("/base/api/jobs/by-track?title=Track&artist=Artist", lookup.path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun getJobByTrackKeepsFailedResponseWithoutAutoRepair() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "job_123",
                      "status": "failed",
                      "error": "Analysis missing"
                    }
                    """.trimIndent()
                )
        )

        val baseUrl = server.url("/base/").toString()
        val result = api.getJobByTrack(baseUrl = baseUrl, title = "Track", artist = "Artist")

        assertEquals("failed", result?.status)
        assertEquals("Analysis missing", result?.error)
        val lookup = server.takeRequest()
        assertEquals("GET", lookup.method)
        assertEquals("/base/api/jobs/by-track?title=Track&artist=Artist", lookup.path)
        assertEquals(1, server.requestCount)
    }
}
