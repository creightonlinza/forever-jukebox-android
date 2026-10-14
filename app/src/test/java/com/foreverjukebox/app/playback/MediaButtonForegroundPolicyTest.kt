package com.foreverjukebox.app.playback

import android.os.Build
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaButtonForegroundPolicyTest {

    private fun resolve(
        sdkInt: Int = Build.VERSION_CODES.R,
        inForeground: Boolean = false,
        sessionVisible: Boolean = true,
        keyCode: Int? = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    ) = resolveMediaButtonStart(sdkInt, inForeground, sessionVisible, keyCode)

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
    fun serviceAlreadyInForegroundNeedsNothingMore() {
        assertEquals(MediaButtonStartAction.None, resolve(inForeground = true))
        assertEquals(
            MediaButtonStartAction.None,
            resolve(inForeground = true, sessionVisible = false)
        )
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
