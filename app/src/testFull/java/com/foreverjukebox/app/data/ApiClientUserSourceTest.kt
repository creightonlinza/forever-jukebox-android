package com.foreverjukebox.app.data

import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiClientUserSourceTest {

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
    fun startUrlAnalysisUsesExpectedPathAndBody() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody(
                    """
                    {
                      "id": "job_url",
                      "status": "downloading",
                      "source_provider": "soundcloud",
                      "source_id": "sc:abc",
                      "message": "Fetching audio"
                    }
                    """.trimIndent()
                )
        )

        val baseUrl = server.url("/base/").toString()
        val response = api.startUrlAnalysis(
            baseUrl = baseUrl,
            url = "https://soundcloud.com/artist/track"
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/base/api/analysis/url", request.path)
        val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(
            "https://soundcloud.com/artist/track",
            payload.getValue("url").toString().trim('"')
        )
        assertEquals("job_url", response.id)
        assertEquals("downloading", response.status)
    }

    @Test
    fun startUrlAnalysisParsesCompletedJobWithResult() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "job_done",
                      "status": "complete",
                      "source_provider": "youtube",
                      "source_id": "dQw4w9WgXcQ",
                      "result": {"beats": []}
                    }
                    """.trimIndent()
                )
        )

        val baseUrl = server.url("/base/").toString()
        val response = api.startUrlAnalysis(baseUrl = baseUrl, url = "https://youtu.be/dQw4w9WgXcQ")

        assertEquals("complete", response.status)
        assertNotNull(response.result)
    }

    @Test
    fun startUrlAnalysisKeepsFailedStatusOn200() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "job_bad",
                      "status": "failed",
                      "error": "This video is not available on YouTube.",
                      "error_code": "youtube_unavailable"
                    }
                    """.trimIndent()
                )
        )

        val baseUrl = server.url("/base/").toString()
        val response = api.startUrlAnalysis(baseUrl = baseUrl, url = "https://youtu.be/dQw4w9WgXcQ")

        assertEquals("failed", response.status)
        assertEquals("youtube_unavailable", response.errorCode)
    }

    @Test
    fun startUrlAnalysisThrowsWithBodyPreservedOnHttpError() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"detail":"User-supplied URL jobs are disabled"}""")
        )

        val baseUrl = server.url("/base/").toString()
        val result = runCatching {
            api.startUrlAnalysis(baseUrl = baseUrl, url = "https://youtu.be/dQw4w9WgXcQ")
        }

        val exception = result.exceptionOrNull()
        assertTrue(exception is HttpStatusException)
        assertEquals(403, (exception as HttpStatusException).statusCode)
        assertEquals("""{"detail":"User-supplied URL jobs are disabled"}""", exception.responseBody)
    }

    @Test
    fun uploadTrackSendsMultipartFilePartAndParsesResponse() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody(
                    """
                    {
                      "id": "job_upload",
                      "status": "queued",
                      "source_provider": "upload",
                      "source_id": null,
                      "message": "Queued • Next in line"
                    }
                    """.trimIndent()
                )
        )
        val payload = "fake-audio-bytes".toByteArray()
        val progress = mutableListOf<Long>()

        val baseUrl = server.url("/base/").toString()
        val response = api.uploadTrack(
            baseUrl = baseUrl,
            fileName = "My Song.mp3",
            sizeBytes = payload.size.toLong(),
            contentType = "audio/mpeg",
            onBytesWritten = { progress.add(it) }
        ) { ByteArrayInputStream(payload) }

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/base/api/upload", request.path)
        assertTrue(
            request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data")
        )
        val body = request.body.readUtf8()
        assertTrue(body.contains("""Content-Disposition: form-data; name="file"; filename="My Song.mp3""""))
        assertTrue(body.contains("Content-Type: audio/mpeg"))
        assertTrue(body.contains("fake-audio-bytes"))
        assertEquals(payload.size.toLong(), progress.last())
        assertEquals("job_upload", response.id)
        assertEquals("queued", response.status)
        assertEquals("upload", response.sourceProvider)
    }

    @Test
    fun uploadTrackThrowsWithStatusOnRejection() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(413)
                .setBody("""{"detail":"File too large"}""")
        )
        val payload = "fake-audio-bytes".toByteArray()

        val baseUrl = server.url("/base/").toString()
        val result = runCatching {
            api.uploadTrack(
                baseUrl = baseUrl,
                fileName = "big.wav",
                sizeBytes = payload.size.toLong(),
                contentType = "audio/wav"
            ) { ByteArrayInputStream(payload) }
        }

        assertFalse(result.isSuccess)
        val exception = result.exceptionOrNull()
        assertTrue(exception is HttpStatusException)
        assertEquals(413, (exception as HttpStatusException).statusCode)
        assertEquals("""{"detail":"File too large"}""", exception.responseBody)
    }
}
