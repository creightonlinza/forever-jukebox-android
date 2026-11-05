package com.foreverjukebox.app.ui

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class FullServerGatewaySearchTest {

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
    fun searchMusicCarriesAlbumArtWhenPresent() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "items": [
                        {"id": "sp1", "name": "Around the World", "duration": 431.0,
                         "image": "https://i.scdn.co/image/sp1-64"},
                        {"id": "sp2", "name": "Da Funk", "duration": 328.0}
                      ]
                    }
                    """.trimIndent()
                )
        )

        val result = gateway.searchMusic(baseUrl = server.url("/base/").toString(), query = "daft punk")

        assertEquals("https://i.scdn.co/image/sp1-64", result[0].image)
        assertNull(result[1].image)
    }
}
