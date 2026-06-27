package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatAndUrlPolicyTest {

    @Test
    fun formatDurationAlwaysReturnsHhMmSs() {
        assertEquals("00:00:00", formatDuration(0.0))
        assertEquals("00:01:05", formatDuration(65.9))
        assertEquals("01:00:00", formatDuration(3600.0))
    }

    @Test
    fun formatTrackDurationUsesCompactSearchResultFormat() {
        assertEquals("0:00", formatTrackDuration(0.0))
        assertEquals("0:01", formatTrackDuration(1.0))
        assertEquals("1:01", formatTrackDuration(61.0))
        assertEquals("59:59", formatTrackDuration(3599.0))
        assertEquals("1:00:00", formatTrackDuration(3600.0))
        assertEquals("1:01:01", formatTrackDuration(3661.0))
        assertEquals("12:00:00", formatTrackDuration(43_200.0))
    }

    @Test
    fun formatTrackDurationFloorsFractionsAndRejectsMissingOrInvalidValues() {
        assertEquals("1:05", formatTrackDuration(65.9))
        assertEquals("-", formatTrackDuration(null))
        assertEquals("-", formatTrackDuration(-1.0))
        assertEquals("-", formatTrackDuration(Double.NaN))
        assertEquals("-", formatTrackDuration(Double.POSITIVE_INFINITY))
        assertEquals("-", formatTrackDuration(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun formatCursorTimeUsesUnboundedMinutesAndSafeInvalidDefaults() {
        assertEquals("0:00", formatCursorTime(0.0))
        assertEquals("1:02", formatCursorTime(62.9))
        assertEquals("62:15", formatCursorTime(3735.0))
        assertEquals("0:00", formatCursorTime(-1.0))
        assertEquals("0:00", formatCursorTime(Double.NaN))
        assertEquals("0:00", formatCursorTime(Double.POSITIVE_INFINITY))
        assertEquals("0:00", formatCursorTime(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun isValidBaseUrlRequiresHttpOrHttpsAndHost() {
        assertTrue(isValidBaseUrl("https://example.com"))
        assertTrue(isValidBaseUrl("http://localhost:8080"))
        assertFalse(isValidBaseUrl("ftp://example.com"))
        assertFalse(isValidBaseUrl("https://"))
        assertFalse(isValidBaseUrl("   "))
    }

    @Test
    fun baseUrlChangeDetectionUsesNormalizedServerIdentity() {
        assertFalse(
            hasBaseUrlServerChanged(
                "https://Example.com/",
                "https://example.com"
            )
        )
        assertFalse(
            hasBaseUrlServerChanged(
                "https://example.com/api/",
                "https://example.com/api"
            )
        )
        assertTrue(
            hasBaseUrlServerChanged(
                "https://example.com/api",
                "https://example.com/other"
            )
        )
    }
}
