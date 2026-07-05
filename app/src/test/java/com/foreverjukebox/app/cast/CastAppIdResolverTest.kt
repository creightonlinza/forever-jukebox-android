package com.foreverjukebox.app.cast

import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastAppIdResolverTest {

    @Test
    fun appIdForModeIsDeterministicAndNeverOrderDependent() {
        // Local always casts to the relay; the pre-preferences null case also defaults to it.
        assertEquals(CastAppIdResolver.RELAY_APP_ID, CastAppIdResolver.appIdForMode(AppMode.Local, null))
        assertEquals(
            CastAppIdResolver.RELAY_APP_ID,
            CastAppIdResolver.appIdForMode(AppMode.Local, "AD4A468D")
        )
        assertEquals(CastAppIdResolver.RELAY_APP_ID, CastAppIdResolver.appIdForMode(null, "AD4A468D"))

        // Server uses exactly the resolved server app ID — nothing else.
        assertEquals("AD4A468D", CastAppIdResolver.appIdForMode(AppMode.Server, "AD4A468D"))
        assertNull(CastAppIdResolver.appIdForMode(AppMode.Server, null))
    }

    @Test
    fun normalizeReturnsNullForBlankInputs() {
        assertNull(CastAppIdResolver.normalize(null))
        assertNull(CastAppIdResolver.normalize("   "))
    }

    @Test
    fun normalizeStripsTrailingSlashWhenUriParsingFallsBackToRawText() {
        assertEquals(
            "HTTPS://Example.COM:8443/api",
            CastAppIdResolver.normalize(" HTTPS://Example.COM:8443/api/ ")
        )
    }

    @Test
    fun normalizeKeepsStructuredCaseAndStripsTrailingSlash() {
        assertEquals(
            "https://example.com:8443/api",
            CastAppIdResolver.normalize("https://example.com:8443/api/")
        )
    }

    @Test
    fun normalizeLeavesNonHierarchicalLikeInputAsTrimmedText() {
        assertEquals(
            "example.com/listen",
            CastAppIdResolver.normalize("example.com/listen/")
        )
    }
}
