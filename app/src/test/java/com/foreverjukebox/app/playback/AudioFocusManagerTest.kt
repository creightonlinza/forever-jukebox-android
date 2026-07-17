package com.foreverjukebox.app.playback

import android.media.AudioManager
import org.junit.Assert.assertEquals
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
        assertEquals(
            AudioFocusRequestResult.Granted,
            NoOpPlaybackAudioFocusController.requestAudioFocus()
        )
        NoOpPlaybackAudioFocusController.abandonAudioFocus()
    }

    @Test
    fun grantedRequestMapsToGranted() {
        assertEquals(
            AudioFocusRequestResult.Granted,
            audioFocusRequestResultFor(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        )
    }

    @Test
    fun delayedRequestMapsToDelayed() {
        assertEquals(
            AudioFocusRequestResult.Delayed,
            audioFocusRequestResultFor(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        )
    }

    @Test
    fun failedRequestMapsToDenied() {
        assertEquals(
            AudioFocusRequestResult.Denied,
            audioFocusRequestResultFor(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        )
        assertEquals(AudioFocusRequestResult.Denied, audioFocusRequestResultFor(Int.MIN_VALUE))
    }
}
