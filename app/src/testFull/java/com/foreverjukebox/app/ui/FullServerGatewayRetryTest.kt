package com.foreverjukebox.app.ui

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Verifies that retry is wired into the gateway itself, so call sites no longer need to wrap
 * requests: a transient 5xx is retried transparently and the eventual success is returned.
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
}
