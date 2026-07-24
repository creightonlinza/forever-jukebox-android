package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.HttpStatusException
import java.io.IOException
import java.net.UnknownServiceException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteLoadRetryTest {

    @Test
    fun retriesPlainIoExceptionWithExponentialBackoff() = runTest {
        val delays = mutableListOf<Long>()
        var attempts = 0

        val result = retryTransientRemoteLoad(
            delayFn = { delays += it }
        ) {
            attempts += 1
            if (attempts < REMOTE_LOAD_RETRY_MAX_ATTEMPTS) {
                throw IOException("transient")
            }
            "loaded"
        }

        assertEquals("loaded", result)
        assertEquals(REMOTE_LOAD_RETRY_MAX_ATTEMPTS, attempts)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
    }

    @Test
    fun retriesRetryableHttpStatusFailures() {
        assertTrue(shouldRetryRemoteLoadFailure(HttpStatusException(408)))
        assertTrue(shouldRetryRemoteLoadFailure(HttpStatusException(429)))
        assertTrue(shouldRetryRemoteLoadFailure(HttpStatusException(500)))
        assertTrue(shouldRetryRemoteLoadFailure(HttpStatusException(503)))
    }

    @Test
    fun doesNotRetryNonRetryableHttpStatusFailures() {
        assertFalse(shouldRetryRemoteLoadFailure(HttpStatusException(404)))
        assertFalse(shouldRetryRemoteLoadFailure(HttpStatusException(422)))
        assertFalse(shouldRetryRemoteLoadFailure(HttpStatusException(400)))
    }

    @Test
    fun stopsImmediatelyForNonRetryableHttpStatusFailures() = runTest {
        val delays = mutableListOf<Long>()
        var attempts = 0

        try {
            retryTransientRemoteLoad(
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
            retryTransientRemoteLoad(
                delayFn = { delays += it }
            ) {
                attempts += 1
                throw HttpStatusException(503)
            }
            fail("Expected HttpStatusException")
        } catch (expected: HttpStatusException) {
            assertEquals(503, expected.statusCode)
        }

        assertEquals(REMOTE_LOAD_RETRY_MAX_ATTEMPTS, attempts)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
    }

    @Test
    fun doesNotRetryCleartextGuardRejections() = runTest {
        // CleartextGuardInterceptor throws UnknownServiceException (an IOException) for a blocked
        // http request. It is deterministic, so it must fail fast rather than exhaust retries.
        assertFalse(shouldRetryRemoteLoadFailure(UnknownServiceException("CLEARTEXT not permitted")))

        val delays = mutableListOf<Long>()
        var attempts = 0

        try {
            retryTransientRemoteLoad(
                delayFn = { delays += it }
            ) {
                attempts += 1
                throw UnknownServiceException("CLEARTEXT not permitted")
            }
            fail("Expected UnknownServiceException")
        } catch (_: UnknownServiceException) {
        }

        assertEquals(1, attempts)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun propagatesCancellationWithoutRetry() = runTest {
        val delays = mutableListOf<Long>()
        var attempts = 0

        try {
            retryTransientRemoteLoad(
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
