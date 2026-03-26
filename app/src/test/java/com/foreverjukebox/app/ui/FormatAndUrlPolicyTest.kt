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
    fun formatDurationShortUsesMmSsUntilOneHour() {
        assertEquals("00:05", formatDurationShort(5.0))
        assertEquals("59:59", formatDurationShort(3599.0))
        assertEquals("01:00:00", formatDurationShort(3600.0))
    }

    @Test
    fun isValidBaseUrlRequiresHttpOrHttpsAndHost() {
        assertTrue(isValidBaseUrl("https://example.com"))
        assertTrue(isValidBaseUrl("http://localhost:8080"))
        assertFalse(isValidBaseUrl("ftp://example.com"))
        assertFalse(isValidBaseUrl("https://"))
        assertFalse(isValidBaseUrl("   "))
    }
}
