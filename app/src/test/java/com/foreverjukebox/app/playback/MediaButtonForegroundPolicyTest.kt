package com.foreverjukebox.app.playback

import android.os.Build
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaButtonForegroundPolicyTest {

    private fun resolve(
        sdkInt: Int = Build.VERSION_CODES.R,
        sessionVisible: Boolean = true,
        keyCode: Int? = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    ) = resolveMediaButtonStart(sdkInt, sessionVisible, keyCode)

    @Test
    fun notificationPlayPressBeforeApi33PromotesTheSession() {
        listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE).forEach { keyCode ->
            assertEquals(MediaButtonStartAction.PromoteSession, resolve(keyCode = keyCode))
        }
        assertEquals(
            MediaButtonStartAction.PromoteSession,
            resolve(sdkInt = Build.VERSION_CODES.S_V2)
        )
    }

    @Test
    fun playPressPromotesEvenWhenTheServiceMayAlreadyBeInTheForeground() {
        // Media3 can leave the foreground without telling the service, so the guard never
        // trusts a cached foreground flag.
        assertEquals(MediaButtonStartAction.PromoteSession, resolve())
        assertEquals(MediaButtonStartAction.ShutDown, resolve(sessionVisible = false))
    }

    @Test
    fun api33AndLaterDrivesNotificationButtonsThroughTheSession() {
        assertEquals(MediaButtonStartAction.None, resolve(sdkInt = Build.VERSION_CODES.TIRAMISU))
        assertEquals(
            MediaButtonStartAction.None,
            resolve(sdkInt = Build.VERSION_CODES.TIRAMISU, sessionVisible = false)
        )
    }

    @Test
    fun nonPlayKeysOnAVisibleSessionAreOrdinaryStarts() {
        listOf(
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS
        ).forEach { keyCode ->
            assertEquals(MediaButtonStartAction.None, resolve(keyCode = keyCode))
        }
    }

    @Test
    fun anyKeyBeforeApi31WithNothingLoadedShutsTheServiceDown() {
        listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT).forEach { keyCode ->
            assertEquals(
                MediaButtonStartAction.ShutDown,
                resolve(sessionVisible = false, keyCode = keyCode)
            )
        }
    }

    @Test
    fun api31And32UseARuntimeReceiverSoAnEmptySessionNeedsNothing() {
        assertEquals(
            MediaButtonStartAction.None,
            resolve(sdkInt = Build.VERSION_CODES.S, sessionVisible = false)
        )
    }

    @Test
    fun nonMediaButtonStartsAreLeftToMedia3() {
        assertEquals(MediaButtonStartAction.None, resolve(keyCode = null))
        assertEquals(MediaButtonStartAction.None, resolve(keyCode = null, sessionVisible = false))
    }
}
