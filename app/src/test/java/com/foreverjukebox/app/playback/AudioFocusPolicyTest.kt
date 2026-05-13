package com.foreverjukebox.app.playback

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusPolicyTest {

    @Test
    fun localPlaybackRequestsFocusBeforeStarting() {
        assertTrue(
            shouldRequestLocalAudioFocus(
                targetPlayState = true,
                isLocalPlaybackRunning = false,
                isCastPlayback = false
            )
        )
        assertFalse(
            shouldRequestLocalAudioFocus(
                targetPlayState = true,
                isLocalPlaybackRunning = true,
                isCastPlayback = false
            )
        )
    }

    @Test
    fun focusRequestFailureBlocksPlaybackStart() {
        assertTrue(isAudioFocusRequestGranted(AudioManager.AUDIOFOCUS_REQUEST_GRANTED))
        assertFalse(isAudioFocusRequestGranted(AudioManager.AUDIOFOCUS_REQUEST_FAILED))
    }

    @Test
    fun transientFocusLossPausesRunningLocalPlayback() {
        assertEquals(
            AudioFocusPlaybackCommand.PauseLocalPlayback,
            audioFocusPlaybackCommand(
                focusChange = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                isLocalPlayback = true,
                isPlaybackRunning = true
            )
        )
        assertEquals(
            AudioFocusPlaybackCommand.None,
            audioFocusPlaybackCommand(
                focusChange = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                isLocalPlayback = true,
                isPlaybackRunning = false
            )
        )
    }

    @Test
    fun permanentFocusLossPausesAndClearsFocusOwnership() {
        assertEquals(
            AudioFocusPlaybackCommand.PauseAndAbandonAudioFocus,
            audioFocusPlaybackCommand(
                focusChange = AudioManager.AUDIOFOCUS_LOSS,
                isLocalPlayback = true,
                isPlaybackRunning = true
            )
        )
        assertEquals(
            AudioFocusPlaybackCommand.PauseAndAbandonAudioFocus,
            audioFocusPlaybackCommand(
                focusChange = AudioManager.AUDIOFOCUS_LOSS,
                isLocalPlayback = true,
                isPlaybackRunning = false
            )
        )
    }

    @Test
    fun duckFocusLossIsNoOp() {
        assertEquals(
            AudioFocusPlaybackCommand.None,
            audioFocusPlaybackCommand(
                focusChange = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                isLocalPlayback = true,
                isPlaybackRunning = true
            )
        )
    }

    @Test
    fun castPlaybackDoesNotRequestOrReactToLocalAudioFocus() {
        assertFalse(
            shouldRequestLocalAudioFocus(
                targetPlayState = true,
                isLocalPlaybackRunning = false,
                isCastPlayback = true
            )
        )
        assertEquals(
            AudioFocusPlaybackCommand.None,
            audioFocusPlaybackCommand(
                focusChange = AudioManager.AUDIOFOCUS_LOSS,
                isLocalPlayback = false,
                isPlaybackRunning = true
            )
        )
    }
}
