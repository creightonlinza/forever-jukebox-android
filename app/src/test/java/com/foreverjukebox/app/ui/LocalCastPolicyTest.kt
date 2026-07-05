package com.foreverjukebox.app.ui

import com.foreverjukebox.app.cast.CastUploadClient
import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCastPolicyTest {

    @Test
    fun serverModeRequiresResolvedAppId() {
        assertTrue(resolveCastEnabled(AppMode.Server, serverAppId = "AD4A468D", relayConfigured = false))
        assertFalse(resolveCastEnabled(AppMode.Server, serverAppId = null, relayConfigured = true))
        assertFalse(resolveCastEnabled(AppMode.Server, serverAppId = "  ", relayConfigured = true))
    }

    @Test
    fun localModeRequiresRelayConfigured() {
        assertTrue(resolveCastEnabled(AppMode.Local, serverAppId = null, relayConfigured = true))
        assertFalse(resolveCastEnabled(AppMode.Local, serverAppId = "AD4A468D", relayConfigured = false))
    }

    @Test
    fun nullModeNeverEnablesCasting() {
        assertFalse(resolveCastEnabled(null, serverAppId = "AD4A468D", relayConfigured = true))
    }

    @Test
    fun fileTooLargeOnlyWhenSizeKnownAndOverCap() {
        assertFalse(isLocalCastFileTooLarge(null))
        assertFalse(isLocalCastFileTooLarge(0))
        assertFalse(isLocalCastFileTooLarge(CastUploadClient.MAX_AUDIO_BYTES))
        assertTrue(isLocalCastFileTooLarge(CastUploadClient.MAX_AUDIO_BYTES + 1))
    }
}
