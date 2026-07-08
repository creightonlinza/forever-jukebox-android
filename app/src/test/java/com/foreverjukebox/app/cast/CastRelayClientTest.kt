package com.foreverjukebox.app.cast

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the sessionless relay contract against a MockWebServer: the per-track audio/analysis PUT
 * paths and status-code mapping, the server-mode pull registration, and local track-id validation.
 */
class CastRelayClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: CastRelayClient

    private val fingerprint = "0123456789abcdef"
    private val jobId = "0123456789abcdef0123456789abcdef"

    private val audioBody = "AUDIO-BYTES".toByteArray()
        .toRequestBody("audio/mp4".toMediaType())
    private val analysisBody = """{"track":{"title":"T"},"beats":[]}"""
        .toByteArray()
        .toRequestBody("application/json".toMediaType())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = CastRelayClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString()

    @Test
    fun uploadForCastPutsBothFilesToTrackPaths() = runTest {
        server.enqueue(MockResponse().setResponseCode(204)) // audio
        server.enqueue(MockResponse().setResponseCode(204)) // analysis

        val result = client.uploadForCast(baseUrl(), fingerprint, audioBody, analysisBody)

        assertEquals(CastRelayClient.UploadResult.Ok, result)
        assertEquals(2, server.requestCount)

        val audioRequest = server.takeRequest()
        assertEquals("PUT", audioRequest.method)
        assertEquals("/api/tracks/$fingerprint/audio", audioRequest.path)
        // The relay echoes the audio content type to the receiver verbatim.
        assertEquals("audio/mp4", audioRequest.getHeader("Content-Type"))
        assertEquals("AUDIO-BYTES", audioRequest.body.readUtf8())

        val analysisRequest = server.takeRequest()
        assertEquals("PUT", analysisRequest.method)
        assertEquals("/api/tracks/$fingerprint/analysis", analysisRequest.path)
        // Bare payload uploaded verbatim — no wrapper.
        assertEquals("""{"track":{"title":"T"},"beats":[]}""", analysisRequest.body.readUtf8())
    }

    @Test
    fun uploadForCastStopsAfterAudioFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(413))

        val result = client.uploadForCast(baseUrl(), fingerprint, audioBody, analysisBody)

        assertEquals(CastRelayClient.UploadResult.TooLarge, result)
        // The analysis PUT is never sent once the audio PUT fails.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun uploadForCastMapsGuardAndErrorCodes() = runTest {
        server.enqueue(MockResponse().setResponseCode(507))
        assertEquals(
            CastRelayClient.UploadResult.Guard,
            client.uploadForCast(baseUrl(), fingerprint, audioBody, analysisBody)
        )

        server.enqueue(MockResponse().setResponseCode(400))
        assertEquals(
            CastRelayClient.UploadResult.Unreachable,
            client.uploadForCast(baseUrl(), fingerprint, audioBody, analysisBody)
        )

        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(
            CastRelayClient.UploadResult.Unreachable,
            client.uploadForCast(baseUrl(), fingerprint, audioBody, analysisBody)
        )

        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(
            CastRelayClient.UploadResult.Unreachable,
            client.uploadForCast(baseUrl(), fingerprint, audioBody, analysisBody)
        )
    }

    @Test
    fun uploadForCastMapsAnalysisFailureToo() = runTest {
        server.enqueue(MockResponse().setResponseCode(204)) // audio
        server.enqueue(MockResponse().setResponseCode(413)) // analysis > 2 MB

        val result = client.uploadForCast(baseUrl(), fingerprint, audioBody, analysisBody)

        assertEquals(CastRelayClient.UploadResult.TooLarge, result)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun uploadForCastRejectsMalformedTrackIdWithoutRequests() = runTest {
        for (badId in listOf("UPPER0123456789A", "0123456789abcde", "not-hex-at-all!", "")) {
            assertEquals(
                CastRelayClient.UploadResult.Unreachable,
                client.uploadForCast(baseUrl(), badId, audioBody, analysisBody)
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun uploadForCastReturnsUnreachableOnConnectionFailure() = runTest {
        val deadUrl = baseUrl()
        server.shutdown()

        val result = client.uploadForCast(deadUrl, fingerprint, audioBody, analysisBody)

        assertEquals(CastRelayClient.UploadResult.Unreachable, result)
    }

    @Test
    fun registerPullPostsBaseUrlWithTrailingSlashTrimmed() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.registerPull(baseUrl(), jobId, "https://example.com/")

        assertEquals(CastRelayClient.PullResult.Ok, result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/tracks/$jobId/pull", request.path)
        assertEquals("application/json", request.getHeader("Content-Type")?.substringBefore(';'))
        assertEquals("""{"baseUrl":"https://example.com"}""", request.body.readUtf8())
    }

    @Test
    fun registerPullMapsErrorCodes() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(
            CastRelayClient.PullResult.Forbidden,
            client.registerPull(baseUrl(), jobId, "https://example.com")
        )

        server.enqueue(MockResponse().setResponseCode(507))
        assertEquals(
            CastRelayClient.PullResult.Guard,
            client.registerPull(baseUrl(), jobId, "https://example.com")
        )

        server.enqueue(MockResponse().setResponseCode(400))
        assertEquals(
            CastRelayClient.PullResult.BadRequest,
            client.registerPull(baseUrl(), jobId, "https://example.com")
        )

        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(
            CastRelayClient.PullResult.Unreachable,
            client.registerPull(baseUrl(), jobId, "https://example.com")
        )
    }

    @Test
    fun registerPullRejectsMalformedJobIdWithoutRequests() = runTest {
        val result = client.registerPull(baseUrl(), "NOT-A-JOB-ID", "https://example.com")

        assertEquals(CastRelayClient.PullResult.BadRequest, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun registerPullReturnsUnreachableOnConnectionFailure() = runTest {
        val deadUrl = baseUrl()
        server.shutdown()

        val result = client.registerPull(deadUrl, jobId, "https://example.com")

        assertEquals(CastRelayClient.PullResult.Unreachable, result)
    }

    @Test
    fun streamingBodyStreamsProvidedBytesWithContentLength() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        val payload = "STREAMED-AUDIO".toByteArray()
        val streaming = CastRelayClient.streamingBody(
            contentType = "audio/mpeg".toMediaType(),
            sizeBytes = payload.size.toLong()
        ) { payload.inputStream() }

        val result = client.uploadForCast(baseUrl(), fingerprint, streaming, analysisBody)

        assertEquals(CastRelayClient.UploadResult.Ok, result)
        val audioRequest = server.takeRequest()
        assertEquals("STREAMED-AUDIO", audioRequest.body.readUtf8())
        assertEquals(payload.size.toLong(), audioRequest.bodySize)
    }

    @Test
    fun streamingBodyReportsCumulativeBytesWritten() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        // Multiple 8 KiB segments so the callback fires more than once.
        val payload = ByteArray(20 * 1024) { (it % 251).toByte() }
        val reported = mutableListOf<Long>()
        val streaming = CastRelayClient.streamingBody(
            contentType = "audio/mpeg".toMediaType(),
            sizeBytes = payload.size.toLong(),
            onBytesWritten = { reported.add(it) }
        ) { payload.inputStream() }

        val result = client.uploadForCast(baseUrl(), fingerprint, streaming, analysisBody)

        assertEquals(CastRelayClient.UploadResult.Ok, result)
        assertTrue("expected multiple progress callbacks, got ${reported.size}", reported.size > 1)
        assertEquals(payload.size.toLong(), reported.last())
        assertEquals(reported, reported.sorted())
        val audioRequest = server.takeRequest()
        assertEquals(payload.size.toLong(), audioRequest.bodySize)
    }

    @Test
    fun streamingBodyProgressResetsPerWriteToInvocation() {
        val payload = ByteArray(12 * 1024) { 7 }
        val reported = mutableListOf<Long>()
        val streaming = CastRelayClient.streamingBody(
            contentType = "audio/mpeg".toMediaType(),
            sizeBytes = payload.size.toLong(),
            onBytesWritten = { reported.add(it) }
        ) { payload.inputStream() }

        // OkHttp may re-invoke writeTo on a retry; the cumulative count must restart each time.
        streaming.writeTo(Buffer())
        val firstInvocation = reported.toList()
        reported.clear()
        streaming.writeTo(Buffer())

        assertEquals(payload.size.toLong(), firstInvocation.last())
        assertEquals(firstInvocation, reported)
        assertTrue(reported.first() <= 8L * 1024)
    }

    @Test
    fun streamingBodyWithUnknownSizeStillStreamsEverything() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        val payload = "NO-SIZE-AUDIO".toByteArray()
        val streaming = CastRelayClient.streamingBody(
            contentType = "audio/mpeg".toMediaType(),
            sizeBytes = -1L
        ) { payload.inputStream() }

        val result = client.uploadForCast(baseUrl(), fingerprint, streaming, analysisBody)

        assertEquals(CastRelayClient.UploadResult.Ok, result)
        assertEquals("NO-SIZE-AUDIO", server.takeRequest().body.readUtf8())
    }
}
