package com.foreverjukebox.app.ui

import com.foreverjukebox.app.cast.CastRelayClient
import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCastPolicyTest {

    @Test
    fun bothModesRequireOnlyRelayConfigured() {
        assertTrue(resolveCastEnabled(AppMode.Server, relayConfigured = true))
        assertTrue(resolveCastEnabled(AppMode.Local, relayConfigured = true))
        assertFalse(resolveCastEnabled(AppMode.Server, relayConfigured = false))
        assertFalse(resolveCastEnabled(AppMode.Local, relayConfigured = false))
    }

    @Test
    fun nullModeNeverEnablesCasting() {
        assertFalse(resolveCastEnabled(null, relayConfigured = true))
    }

    @Test
    fun fileTooLargeOnlyWhenSizeKnownAndOverCap() {
        assertFalse(isLocalCastFileTooLarge(null))
        assertFalse(isLocalCastFileTooLarge(0))
        assertFalse(isLocalCastFileTooLarge(CastRelayClient.MAX_AUDIO_BYTES))
        assertTrue(isLocalCastFileTooLarge(CastRelayClient.MAX_AUDIO_BYTES + 1))
    }

    @Test
    fun uploadPercentNullWhenTotalUnknown() {
        assertNull(castUploadPercent(bytesSent = 512, totalBytes = null))
        assertNull(castUploadPercent(bytesSent = 512, totalBytes = 0))
        assertNull(castUploadPercent(bytesSent = 512, totalBytes = -1))
    }

    @Test
    fun uploadPercentTruncatesAndClamps() {
        assertEquals(0, castUploadPercent(bytesSent = 0, totalBytes = 1000))
        assertEquals(0, castUploadPercent(bytesSent = 9, totalBytes = 1000))
        assertEquals(50, castUploadPercent(bytesSent = 509, totalBytes = 1000))
        assertEquals(100, castUploadPercent(bytesSent = 1000, totalBytes = 1000))
        // A content provider can under-report the size; never exceed 100.
        assertEquals(100, castUploadPercent(bytesSent = 1500, totalBytes = 1000))
    }
}
