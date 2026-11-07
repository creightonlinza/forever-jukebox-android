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
    fun notificationPlayPressPromotesTheSession() {
        listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE).forEach { keyCode ->
            assertEquals(MediaButtonStartAction.PromoteSession, resolve(keyCode = keyCode))
        }
        listOf(Build.VERSION_CODES.S_V2, Build.VERSION_CODES.TIRAMISU).forEach { sdkInt ->
            assertEquals(MediaButtonStartAction.PromoteSession, resolve(sdkInt = sdkInt))
        }
    }

    @Test
    fun playPressPromotesEvenWhenTheServiceMayAlreadyBeInTheForeground() {
        // Media3 can leave the foreground without telling the service, so the guard never
        // trusts a cached foreground flag.
        assertEquals(MediaButtonStartAction.PromoteSession, resolve())
        assertEquals(MediaButtonStartAction.ShutDown, resolve(sessionVisible = false))
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
    fun api31AndLaterPlayPressOnANotificationThatOutlivedItsProcessShutsTheServiceDown() {
        listOf(
            Build.VERSION_CODES.S,
            Build.VERSION_CODES.S_V2,
            Build.VERSION_CODES.TIRAMISU,
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ).forEach { sdkInt ->
            assertEquals(
                MediaButtonStartAction.ShutDown,
                resolve(sdkInt = sdkInt, sessionVisible = false)
            )
        }
    }

    @Test
    fun api31AndLaterOtherKeysOnAnEmptySessionNeedNothing() {
        listOf(Build.VERSION_CODES.S, Build.VERSION_CODES.TIRAMISU).forEach { sdkInt ->
            listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS).forEach { keyCode ->
                assertEquals(
                    MediaButtonStartAction.None,
                    resolve(sdkInt = sdkInt, sessionVisible = false, keyCode = keyCode)
                )
            }
        }
    }

    @Test
    fun nonMediaButtonStartsAreLeftToMedia3() {
        assertEquals(MediaButtonStartAction.None, resolve(keyCode = null))
        assertEquals(MediaButtonStartAction.None, resolve(keyCode = null, sessionVisible = false))
    }
}
