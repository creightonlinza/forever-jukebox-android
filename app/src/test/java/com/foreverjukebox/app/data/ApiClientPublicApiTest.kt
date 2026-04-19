package com.foreverjukebox.app.data

import java.io.File
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
    fun searchYoutubeUsesExpectedPathAndQueryAndParsesItems() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "items": [
                        {
                          "id": "yt1",
                          "title": "Never Gonna Give You Up",
                          "duration": 212.0
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val baseUrl = server.url("/base/").toString()
        val result = api.searchYoutube(
            baseUrl = baseUrl,
            query = "rick astley",
            duration = 212.0
        )

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/base/api/search/youtube?q=rick%20astley&target_duration=212.0", request.path)
        assertEquals(1, result.size)
        assertEquals("yt1", result.first().id)
        assertEquals("Never Gonna Give You Up", result.first().title)
    }

    @Test
    fun startYoutubeAnalysisUsesExpectedPathAndBody() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "job_start",
                      "status": "queued",
                      "source_provider": "youtube",
                      "source_id": "dQw4w9WgXcQ"
                    }
                    """.trimIndent()
                )
        )

        val baseUrl = server.url("/base/").toString()
        val response = api.startYoutubeAnalysis(
            baseUrl = baseUrl,
            youtubeId = "dQw4w9WgXcQ",
            title = "Track",
            artist = "Artist"
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/base/api/analysis/youtube", request.path)
        val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("dQw4w9WgXcQ", payload.getValue("youtube_id").toString().trim('"'))
        assertEquals("Track", payload.getValue("title").toString().trim('"'))
        assertEquals("Artist", payload.getValue("artist").toString().trim('"'))
        assertEquals("job_start", response.id)
        assertEquals("queued", response.status)
    }

    @Test
    fun getAnalysisUsesExpectedPathAndParsesResponse() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "job_123",
                      "status": "processing",
                      "source_provider": "youtube",
                      "source_id": "dQw4w9WgXcQ"
                    }
                    """.trimIndent()
                )
        )

        val baseUrl = server.url("/base/").toString()
        val response = api.getAnalysis(baseUrl = baseUrl, jobId = "job_123")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/base/api/analysis/job_123", request.path)
        assertEquals("job_123", response.id)
        assertEquals("processing", response.status)
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
    fun updateFavoritesSyncUsesExpectedPathMethodAndBody() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "code": "abc123",
                      "count": 1,
                      "favorites": [
                        {
                          "uniqueSongId": "song_1",
                          "title": "Song 1",
                          "artist": "Artist 1",
                          "sourceType": "youtube"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val baseUrl = server.url("/base/").toString()
        val response = api.updateFavoritesSync(
            baseUrl = baseUrl,
            code = "abc123",
            favorites = listOf(
                FavoriteTrack(
                    uniqueSongId = "song_1",
                    title = "Song 1",
                    artist = "Artist 1",
                    sourceType = FavoriteSourceType.Youtube
                )
            )
        )

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/base/api/favorites/sync/abc123", request.path)
        val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val payloadFavorites = payload.getValue("favorites").jsonArray
        assertEquals(1, payloadFavorites.size)
        assertEquals("song_1", payloadFavorites.first().jsonObject.getValue("uniqueSongId").toString().trim('"'))
        assertEquals("abc123", response.code)
    }

    @Test
    fun fetchFavoritesSyncUsesExpectedPathAndParsesFavorites() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "favorites": [
                        {
                          "uniqueSongId": "song_1",
                          "title": "Song 1",
                          "artist": "Artist 1",
                          "sourceType": "youtube"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val baseUrl = server.url("/base/").toString()
        val favorites = api.fetchFavoritesSync(baseUrl = baseUrl, code = "abc123")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/base/api/favorites/sync/abc123", request.path)
        assertEquals(1, favorites.size)
        assertEquals("song_1", favorites.first().uniqueSongId)
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
    fun fetchTrendingSongsUsesExpectedPathAndParsesItems() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "items": [
                        {
                          "id": "job_1",
                          "source_provider": "soundcloud",
                          "source_id": "sc:abc",
                          "title": "Trending Song"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val baseUrl = server.url("/base/").toString()
        val items = api.fetchTrendingSongs(baseUrl)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/base/api/trending?limit=25", request.path)
        assertEquals(1, items.size)
        assertEquals("Trending Song", items.first().title)
    }

    @Test
    fun fetchRecentSongsUsesExpectedPathAndParsesItems() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "items": [
                        {
                          "id": "job_2",
                          "source_provider": "bandcamp",
                          "source_id": "bc_123",
                          "title": "Recent Song"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val baseUrl = server.url("/base/").toString()
        val items = api.fetchRecentSongs(baseUrl)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/base/api/recent?limit=25", request.path)
        assertEquals(1, items.size)
        assertEquals("Recent Song", items.first().title)
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
    fun postPlayUsesExpectedPathAndMethod() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(204)
        )

        val baseUrl = server.url("/base/").toString()
        api.postPlay(baseUrl = baseUrl, jobId = "job_123")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/base/api/plays/job_123", request.path)
    }

    @Test
    fun fetchAudioToFileUsesExpectedPathAndWritesBytes() = runTest {
        val payload = "audio-bytes".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(payload))
        )

        val baseUrl = server.url("/base/").toString()
        val tempFile = File.createTempFile("api-audio-", ".bin")
        try {
            val written = api.fetchAudioToFile(baseUrl = baseUrl, jobId = "job_audio", target = tempFile)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/base/api/audio/job_audio", request.path)
            assertEquals(tempFile.absolutePath, written.absolutePath)
            assertEquals("audio-bytes", written.readText())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun deleteJobUsesExpectedPathAndMethod() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "status": "deleted",
                      "id": "job_delete"
                    }
                    """.trimIndent()
                )
        )

        val baseUrl = server.url("/base/").toString()
        val response = api.deleteJob(baseUrl = baseUrl, jobId = "job_delete")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/base/api/jobs/job_delete", request.path)
        assertEquals("", request.body.readUtf8())
        assertEquals("deleted", response.status)
        assertEquals("job_delete", response.id)
    }

    @Test
    fun deleteJobThrowsHttpStatusExceptionWithFastApiErrorBody() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"detail":"Job not found"}""")
        )

        val baseUrl = server.url("/base/").toString()
        val result = runCatching {
            api.deleteJob(baseUrl = baseUrl, jobId = "job_missing")
        }

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is HttpStatusException)
        assertEquals(404, (exception as HttpStatusException).statusCode)
        assertEquals("""{"detail":"Job not found"}""", exception.responseBody)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/base/api/jobs/job_missing", request.path)
    }

    @Test
    fun fetchLatestGitHubReleaseUsesExpectedPathHeadersAndParsesResponse() = runTest {
        val githubApi = ApiClient(
            githubApiBaseUrl = server.url("/gh/").toString()
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "tag_name": "v1.2.3",
                      "html_url": "https://github.com/forever-jukebox/forever-jukebox-android/releases/tag/v1.2.3"
                    }
                    """.trimIndent()
                )
        )

        val response = githubApi.fetchLatestGitHubRelease(
            owner = "forever-jukebox",
            repo = "forever-jukebox-android"
        )

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/gh/repos/forever-jukebox/forever-jukebox-android/releases/latest", request.path)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
        assertEquals("ForeverJukebox-Android", request.getHeader("User-Agent"))
        assertEquals("v1.2.3", response.tagName)
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
        assertEquals("/base/api/jobs/by-source/youtube/dQw4w9WgXcQ", lookup.path)
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

    @Test
    fun getJobByYoutubeKeepsFailedResponseWithoutAutoRepair() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "job_yt",
                      "source_provider": "youtube",
                      "source_id": "dQw4w9WgXcQ",
                      "status": "failed",
                      "error": "Analysis missing"
                    }
                    """.trimIndent()
                )
        )

        val baseUrl = server.url("/base/").toString()
        val result = api.getJobByYoutube(baseUrl = baseUrl, youtubeId = "dQw4w9WgXcQ")

        assertEquals("failed", result?.status)
        assertEquals("Analysis missing", result?.error)
        assertEquals("job_yt", result?.id)
        val lookup = server.takeRequest()
        assertEquals("GET", lookup.method)
        assertEquals("/base/api/jobs/by-source/youtube/dQw4w9WgXcQ", lookup.path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun getJobBySourceReturnsNullOn404() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("not found")
        )

        val baseUrl = server.url("/base/").toString()
        val result = api.getJobBySource(
            baseUrl = baseUrl,
            sourceProvider = "soundcloud",
            sourceId = "abc123"
        )

        assertNull(result)
        val lookup = server.takeRequest()
        assertEquals("GET", lookup.method)
        assertEquals("/base/api/jobs/by-source/soundcloud/abc123", lookup.path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun getJobBySourceThrowsHttpStatusExceptionForNon404Errors() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setBody("""{"detail":[{"msg":"too long"}]}""")
        )

        val baseUrl = server.url("/base/").toString()
        val result = runCatching {
            api.getJobBySource(
                baseUrl = baseUrl,
                sourceProvider = "youtube",
                sourceId = "dQw4w9WgXcQ"
            )
        }

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is HttpStatusException)
        assertEquals(422, (exception as HttpStatusException).statusCode)
        val lookup = server.takeRequest()
        assertEquals("GET", lookup.method)
        assertEquals("/base/api/jobs/by-source/youtube/dQw4w9WgXcQ", lookup.path)
    }

    @Test
    fun getJobByTrackThrowsForNon404HttpError() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("boom")
        )

        val baseUrl = server.url("/base/").toString()
        val result = runCatching {
            api.getJobByTrack(baseUrl = baseUrl, title = "Track", artist = "Artist")
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        val lookup = server.takeRequest()
        assertEquals("GET", lookup.method)
        assertEquals("/base/api/jobs/by-track?title=Track&artist=Artist", lookup.path)
        assertEquals(1, server.requestCount)
    }
}
