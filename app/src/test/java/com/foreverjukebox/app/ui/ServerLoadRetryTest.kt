package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.HttpStatusException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerLoadRetryTest {

    @Test
    fun retriesPlainIoExceptionWithExponentialBackoff() = runTest {
        val delays = mutableListOf<Long>()
        var attempts = 0

        val result = retryTransientServerLoad(
            delayFn = { delays += it }
        ) {
            attempts += 1
            if (attempts < SERVER_LOAD_RETRY_MAX_ATTEMPTS) {
                throw IOException("transient")
            }
            "loaded"
        }

        assertEquals("loaded", result)
        assertEquals(SERVER_LOAD_RETRY_MAX_ATTEMPTS, attempts)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
    }

    @Test
    fun retriesRetryableHttpStatusFailures() {
        assertTrue(shouldRetryServerLoadFailure(HttpStatusException(408)))
        assertTrue(shouldRetryServerLoadFailure(HttpStatusException(429)))
        assertTrue(shouldRetryServerLoadFailure(HttpStatusException(500)))
        assertTrue(shouldRetryServerLoadFailure(HttpStatusException(503)))
    }

    @Test
    fun doesNotRetryNonRetryableHttpStatusFailures() {
        assertFalse(shouldRetryServerLoadFailure(HttpStatusException(404)))
        assertFalse(shouldRetryServerLoadFailure(HttpStatusException(422)))
        assertFalse(shouldRetryServerLoadFailure(HttpStatusException(400)))
    }

    @Test
    fun stopsImmediatelyForNonRetryableHttpStatusFailures() = runTest {
        val delays = mutableListOf<Long>()
        var attempts = 0

        try {
            retryTransientServerLoad(
                delayFn = { delays += it }
            ) {
                attempts += 1
                throw HttpStatusException(422)
            }
            fail("Expected HttpStatusException")
        } catch (expected: HttpStatusException) {
            assertEquals(422, expected.statusCode)
        }

        assertEquals(1, attempts)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun exhaustsRetryableFailuresAfterMaxAttempts() = runTest {
        val delays = mutableListOf<Long>()
        var attempts = 0

        try {
            retryTransientServerLoad(
                delayFn = { delays += it }
            ) {
                attempts += 1
                throw HttpStatusException(503)
            }
            fail("Expected HttpStatusException")
        } catch (expected: HttpStatusException) {
            assertEquals(503, expected.statusCode)
        }

        assertEquals(SERVER_LOAD_RETRY_MAX_ATTEMPTS, attempts)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
    }

    @Test
    fun propagatesCancellationWithoutRetry() = runTest {
        val delays = mutableListOf<Long>()
        var attempts = 0

        try {
            retryTransientServerLoad(
                delayFn = { delays += it }
            ) {
                attempts += 1
                throw CancellationException("cancelled")
            }
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
        }

        assertEquals(1, attempts)
        assertTrue(delays.isEmpty())
    }
}
