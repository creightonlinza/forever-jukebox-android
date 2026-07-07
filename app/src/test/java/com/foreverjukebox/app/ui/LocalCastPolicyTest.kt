package com.foreverjukebox.app.ui

import com.foreverjukebox.app.cast.CastRelayClient
import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertFalse
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
}
