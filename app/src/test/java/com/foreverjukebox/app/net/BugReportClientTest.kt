package com.foreverjukebox.app.net

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the Google Forms submission contract against a MockWebServer: the form-urlencoded
 * entry mapping, the redirect-to-confirmation success path, and failure mapping. Version and
 * device strings are passed as literals — [BugReportClient.deviceSummary] reads
 * `android.os.Build` and can't run on the JVM.
 */
class BugReportClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BugReportClient

    private val feedback = "It broke"
    private val appVersion = "2026.08.1 (Build 42)"
    private val deviceInfo = "Acme Phone, Android 14 (SDK 34)"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BugReportClient(formUrl = server.url("/formResponse").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun submitPostsFormEncodedEntries() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = client.submit(feedback, appVersion, deviceInfo)

        assertTrue(result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/formResponse", request.path)
        assertEquals(
            "application/x-www-form-urlencoded",
            request.getHeader("Content-Type")?.substringBefore(';')
        )
        assertEquals(
            "${BugReportClient.ENTRY_FEEDBACK}=It+broke" +
                "&${BugReportClient.ENTRY_APP_VERSION}=2026.08.1+%28Build+42%29" +
                "&${BugReportClient.ENTRY_DEVICE_INFO}=Acme+Phone%2C+Android+14+%28SDK+34%29",
            request.body.readUtf8()
        )
    }

    @Test
    fun submitFollowsRedirectToSuccess() = runTest {
        // Google Forms answers a submission with a redirect to the confirmation page.
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/confirmation").toString())
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val result = client.submit(feedback, appVersion, deviceInfo)

        assertTrue(result)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun submitReturnsFalseWhenRedirectedBackToViewform() = runTest {
        // Google Forms answers a rejected submission (form closed, stale entry ids,
        // missing required answer) with a 200 redirect back to the form itself.
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/viewform").toString())
        )
        server.enqueue(MockResponse().setResponseCode(200))

        assertFalse(client.submit(feedback, appVersion, deviceInfo))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun submitReturnsFalseOnHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))
        assertFalse(client.submit(feedback, appVersion, deviceInfo))

        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(client.submit(feedback, appVersion, deviceInfo))
    }

    @Test
    fun submitReturnsFalseOnConnectionFailure() = runTest {
        val deadUrl = server.url("/formResponse").toString()
        server.shutdown()
        val deadClient = BugReportClient(formUrl = deadUrl)

        assertFalse(deadClient.submit(feedback, appVersion, deviceInfo))
    }
}
