package com.foreverjukebox.app.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastAppIdResolverTest {

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
