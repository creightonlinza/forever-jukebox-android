package com.foreverjukebox.app.cast

import kotlinx.coroutines.test.runTest
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the relay upload contract against a MockWebServer: session creation, the audio/analysis
 * PUT paths, the 404 recreate-and-retry-once recovery, and the size/guard/failure code mapping.
 */
class CastUploadClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: CastUploadClient

    private val audioBody = "AUDIO-BYTES".toByteArray().toRequestBody(null)
    private val analysisBody = """{"track":{"title":"T"},"beats":[]}"""
        .toByteArray()
        .toRequestBody(null)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = CastUploadClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString()

    @Test
    fun createSessionPostsToSessionsAndParsesSessionId() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("""{"sessionId":"11111111-2222-4333-8444-555555555555"}""")
        )

        val sessionId = client.createSession(baseUrl())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/sessions", request.path)
        assertEquals("11111111-2222-4333-8444-555555555555", sessionId)
    }

    @Test
    fun uploadForCastReusesSessionAndPutsBothFilesToExpectedPaths() = runTest {
        val sessionId = "11111111-2222-4333-8444-555555555555"
        val fingerprint = "0123456789abcdef"
        server.enqueue(MockResponse().setResponseCode(204)) // audio
        server.enqueue(MockResponse().setResponseCode(204)) // analysis

        val result = client.uploadForCast(
            baseUrl = baseUrl(),
            existingSessionId = sessionId,
            fingerprint = fingerprint,
            audioBody = audioBody,
            analysisBody = analysisBody
        )

        assertEquals(CastUploadClient.UploadResult.Success(sessionId), result)
        assertEquals(2, server.requestCount)

        val audioRequest = server.takeRequest()
        assertEquals("PUT", audioRequest.method)
        assertEquals("/api/sessions/$sessionId/audio/$fingerprint", audioRequest.path)
        assertEquals("AUDIO-BYTES", audioRequest.body.readUtf8())

        val analysisRequest = server.takeRequest()
        assertEquals("PUT", analysisRequest.method)
        assertEquals("/api/sessions/$sessionId/analysis/$fingerprint", analysisRequest.path)
        // Bare payload uploaded verbatim — no job envelope wrapping.
        assertEquals("""{"track":{"title":"T"},"beats":[]}""", analysisRequest.body.readUtf8())
    }

    @Test
    fun uploadForCastCreatesSessionWhenNoneHeld() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody("""{"sessionId":"abc"}""")
        )
        server.enqueue(MockResponse().setResponseCode(204)) // audio
        server.enqueue(MockResponse().setResponseCode(204)) // analysis

        val result = client.uploadForCast(
            baseUrl = baseUrl(),
            existingSessionId = null,
            fingerprint = "0123456789abcdef",
            audioBody = audioBody,
            analysisBody = analysisBody
        )

        assertEquals(CastUploadClient.UploadResult.Success("abc"), result)
        assertEquals(3, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun uploadForCastRecreatesSessionAndRetriesOnceOn404() = runTest {
        val staleSession = "stale"
        val freshSession = "fresh"
        server.enqueue(MockResponse().setResponseCode(404)) // audio PUT on stale session
        server.enqueue(
            MockResponse().setResponseCode(201).setBody("""{"sessionId":"$freshSession"}""")
        )
        server.enqueue(MockResponse().setResponseCode(204)) // audio retry
        server.enqueue(MockResponse().setResponseCode(204)) // analysis retry

        val result = client.uploadForCast(
            baseUrl = baseUrl(),
            existingSessionId = staleSession,
            fingerprint = "0123456789abcdef",
            audioBody = audioBody,
            analysisBody = analysisBody
        )

        assertEquals(CastUploadClient.UploadResult.Success(freshSession), result)
        assertEquals(4, server.requestCount)

        assertEquals("/api/sessions/$staleSession/audio/0123456789abcdef", server.takeRequest().path)
        assertEquals("/api/sessions", server.takeRequest().path)
        assertEquals("/api/sessions/$freshSession/audio/0123456789abcdef", server.takeRequest().path)
        assertEquals("/api/sessions/$freshSession/analysis/0123456789abcdef", server.takeRequest().path)
    }

    @Test
    fun uploadForCastReturnsUnreachableWhenStill404AfterRecreate() = runTest {
        server.enqueue(MockResponse().setResponseCode(404)) // audio PUT
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"sessionId":"fresh"}"""))
        server.enqueue(MockResponse().setResponseCode(404)) // audio retry still 404

        val result = client.uploadForCast(
            baseUrl = baseUrl(),
            existingSessionId = "stale",
            fingerprint = "0123456789abcdef",
            audioBody = audioBody,
            analysisBody = analysisBody
        )

        assertEquals(CastUploadClient.UploadResult.Unreachable, result)
    }

    @Test
    fun uploadForCastMapsTooLargeAndGuardCodes() = runTest {
        server.enqueue(MockResponse().setResponseCode(413))
        val tooLarge = client.uploadForCast(
            baseUrl = baseUrl(),
            existingSessionId = "s",
            fingerprint = "0123456789abcdef",
            audioBody = audioBody,
            analysisBody = analysisBody
        )
        assertEquals(CastUploadClient.UploadResult.TooLarge, tooLarge)

        server.enqueue(MockResponse().setResponseCode(507))
        val guard = client.uploadForCast(
            baseUrl = baseUrl(),
            existingSessionId = "s",
            fingerprint = "0123456789abcdef",
            audioBody = audioBody,
            analysisBody = analysisBody
        )
        assertEquals(CastUploadClient.UploadResult.Guard, guard)
    }

    @Test
    fun uploadForCastMapsBadIdsAndServerErrorsToUnreachable() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))
        val badIds = client.uploadForCast(
            baseUrl = baseUrl(),
            existingSessionId = "s",
            fingerprint = "0123456789abcdef",
            audioBody = audioBody,
            analysisBody = analysisBody
        )
        assertEquals(CastUploadClient.UploadResult.Unreachable, badIds)

        server.enqueue(MockResponse().setResponseCode(500))
        val serverError = client.uploadForCast(
            baseUrl = baseUrl(),
            existingSessionId = "s",
            fingerprint = "0123456789abcdef",
            audioBody = audioBody,
            analysisBody = analysisBody
        )
        assertEquals(CastUploadClient.UploadResult.Unreachable, serverError)
    }

    @Test
    fun streamingBodyStreamsProvidedBytesWithContentLength() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        val payload = "STREAMED-AUDIO".toByteArray()
        val streaming = CastUploadClient.streamingBody(
            contentType = null,
            sizeBytes = payload.size.toLong()
        ) { payload.inputStream() }

        val result = client.uploadForCast(
            baseUrl = baseUrl(),
            existingSessionId = "s",
            fingerprint = "0123456789abcdef",
            audioBody = streaming,
            analysisBody = analysisBody
        )

        assertTrue(result is CastUploadClient.UploadResult.Success)
        val audioRequest = server.takeRequest()
        assertEquals("STREAMED-AUDIO", audioRequest.body.readUtf8())
        assertEquals(payload.size.toLong(), audioRequest.bodySize)
    }
}
