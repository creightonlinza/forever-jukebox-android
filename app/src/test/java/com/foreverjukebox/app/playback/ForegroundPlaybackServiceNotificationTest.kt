package com.foreverjukebox.app.playback

import android.support.v4.media.session.PlaybackStateCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPlaybackServiceNotificationTest {

    @Test
    fun mediaSessionActionsIncludePlaylistSkipRequests() {
        val actions = mediaSessionPlaybackActions()

        assertTrue(actions and PlaybackStateCompat.ACTION_PLAY != 0L)
        assertTrue(actions and PlaybackStateCompat.ACTION_PAUSE != 0L)
        assertTrue(actions and PlaybackStateCompat.ACTION_PLAY_PAUSE != 0L)
        assertTrue(actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS != 0L)
        assertTrue(actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L)
    }

    @Test
    fun notificationActionsIncludePreviousToggleNextWhenBothSkipsAvailable() {
        val actions = playbackNotificationActionSlots(
            canSkipPrevious = true,
            canSkipNext = true
        )

        assertEquals(
            listOf(
                PlaybackNotificationActionSlot.Previous,
                PlaybackNotificationActionSlot.Toggle,
                PlaybackNotificationActionSlot.Next
            ),
            actions
        )
    }

    @Test
    fun notificationActionsOmitUnavailableSkipDirections() {
        assertEquals(
            listOf(
                PlaybackNotificationActionSlot.Previous,
                PlaybackNotificationActionSlot.Toggle
            ),
            playbackNotificationActionSlots(canSkipPrevious = true, canSkipNext = false)
        )
        assertEquals(
            listOf(
                PlaybackNotificationActionSlot.Toggle,
                PlaybackNotificationActionSlot.Next
            ),
            playbackNotificationActionSlots(canSkipPrevious = false, canSkipNext = true)
        )
        assertEquals(
            listOf(PlaybackNotificationActionSlot.Toggle),
            playbackNotificationActionSlots(canSkipPrevious = false, canSkipNext = false)
        )
    }

    @Test
    fun notificationActionsAreHiddenWhileLoading() {
        val actions = playbackNotificationActionSlots(
            canSkipPrevious = true,
            canSkipNext = true,
            isLoading = true
        )

        assertEquals(emptyList<PlaybackNotificationActionSlot>(), actions)
    }

    @Test
    fun notificationActionsShowOnlyRetryWhenLoadFailed() {
        val actions = playbackNotificationActionSlots(
            canSkipPrevious = true,
            canSkipNext = true,
            isLoadFailed = true
        )

        assertEquals(listOf(PlaybackNotificationActionSlot.Toggle), actions)
    }

    @Test
    fun compactActionIndicesUseAllVisibleNotificationActions() {
        assertEquals(emptyList<Int>(), compactActionIndices(0).toList())
        assertEquals(listOf(0), compactActionIndices(1).toList())
        assertEquals(listOf(0, 1), compactActionIndices(2).toList())
        assertEquals(listOf(0, 1, 2), compactActionIndices(3).toList())
        assertEquals(listOf(0, 1, 2), compactActionIndices(4).toList())
    }
}
