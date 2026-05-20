package com.foreverjukebox.app.playback

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusManagerTest {

    @Test
    fun transientCanDuckMapsToDuck() {
        assertEquals(
            AudioFocusAction.Duck,
            audioFocusActionForChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        )
    }

    @Test
    fun gainMapsToUnduck() {
        assertEquals(
            AudioFocusAction.Unduck,
            audioFocusActionForChange(AudioManager.AUDIOFOCUS_GAIN)
        )
    }

    @Test
    fun focusLossesMapToPause() {
        assertEquals(
            AudioFocusAction.Pause,
            audioFocusActionForChange(AudioManager.AUDIOFOCUS_LOSS)
        )
        assertEquals(
            AudioFocusAction.Pause,
            audioFocusActionForChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        )
    }

    @Test
    fun unknownFocusChangeIsIgnored() {
        assertEquals(AudioFocusAction.Ignore, audioFocusActionForChange(Int.MIN_VALUE))
    }

    @Test
    fun noOpFocusControllerAllowsPlaybackAndAbandonIsSafe() {
        assertTrue(NoOpPlaybackAudioFocusController.requestAudioFocus())
        NoOpPlaybackAudioFocusController.abandonAudioFocus()
    }
}
