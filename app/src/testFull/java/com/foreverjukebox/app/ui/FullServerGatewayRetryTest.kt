package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.HttpStatusException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Verifies retry policy at the gateway boundary: idempotent remote reads are retried, while
 * mutations are not replayed after transient failures.
 */
class FullServerGatewayRetryTest {

    private lateinit var server: MockWebServer
    private lateinit var gateway: ServerGateway

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        gateway = createServerGateway()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun retriesTransientServerErrorThenSucceeds() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
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
        val result = gateway.searchMusic(baseUrl = baseUrl, query = "daft punk")

        assertEquals(2, server.requestCount)
        assertEquals(1, result.size)
        assertEquals("sp1", result.first().id)
    }

    @Test
    fun doesNotRetryTransientServerErrorForMutation() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val baseUrl = server.url("/base/").toString()
        try {
            gateway.postPlay(baseUrl = baseUrl, jobId = "job_123")
            fail("Expected HttpStatusException")
        } catch (expected: HttpStatusException) {
            assertEquals(503, expected.statusCode)
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun doesNotRetryTransientServerErrorForFavoritesSyncCreation() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val baseUrl = server.url("/base/").toString()
        try {
            gateway.createFavoritesSync(
                baseUrl = baseUrl,
                favorites = emptyList(),
                maxFavorites = 150
            )
            fail("Expected HttpStatusException")
        } catch (expected: HttpStatusException) {
            assertEquals(503, expected.statusCode)
        }

        assertEquals(1, server.requestCount)
    }
}
