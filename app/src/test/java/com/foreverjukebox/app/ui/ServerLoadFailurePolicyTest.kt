package com.foreverjukebox.app.ui

import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLoadFailurePolicyTest {

    private val jobId = "7e6deb7a9efe4078a6a62ed74bd11818"

    // A restricted device fails the cached decode first and the fallback fetch second.
    // The decode failure is the root cause and must be what the user sees and what
    // Crashlytics records; the network error rides along as a suppressed exception.
    @Test
    fun blockedCachedDecodeWinsOverNetworkFallbackFailure() {
        val decodeFailure = IllegalStateException("codec unavailable")
        val networkFailure = UnknownHostException("Unable to resolve host")

        val surface = resolveServerLoadFailureSurface(
            error = networkFailure,
            cachedJobId = jobId,
            cachedDecodeFailure = decodeFailure
        )

        assertEquals("Loading failed.", surface.message)
        assertEquals("IllegalStateException", surface.reason)
        assertSame(decodeFailure, surface.cause.cause)
        assertTrue(surface.cause.suppressed.contains(networkFailure))
        assertTrue(surface.cause.message.orEmpty().contains(jobId))
    }

    @Test
    fun networkFailureWithoutDecodeMemoSurfacesAsNetworkError() {
        val networkFailure = UnknownHostException("Unable to resolve host")

        val surface = resolveServerLoadFailureSurface(
            error = networkFailure,
            cachedJobId = jobId,
            cachedDecodeFailure = null
        )

        assertEquals("Network error.", surface.message)
        assertSame(networkFailure, surface.cause)
        assertEquals("UnknownHostException", surface.reason)
    }

    @Test
    fun nonNetworkFailureSurfacesAsLoadingFailedWithItsOwnCause() {
        val failure = IOException("Failed to decode audio")

        val surface = resolveServerLoadFailureSurface(
            error = failure,
            cachedJobId = jobId,
            cachedDecodeFailure = null
        )

        assertEquals("Loading failed.", surface.message)
        assertSame(failure, surface.cause)
        assertEquals("IOException", surface.reason)
    }

    // A decode memo only re-attributes failures whose surfaced error is the secondary
    // network one; a non-network final error already is the real failure.
    @Test
    fun decodeMemoDoesNotOverrideNonNetworkFailure() {
        val decodeFailure = IllegalStateException("codec unavailable")
        val failure = IOException("Failed to decode audio")

        val surface = resolveServerLoadFailureSurface(
            error = failure,
            cachedJobId = jobId,
            cachedDecodeFailure = decodeFailure
        )

        assertEquals("Loading failed.", surface.message)
        assertSame(failure, surface.cause)
    }
}
