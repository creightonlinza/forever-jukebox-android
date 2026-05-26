package com.foreverjukebox.app.playback

import android.support.v4.media.session.PlaybackStateCompat
import com.foreverjukebox.app.ui.JukeboxAudioMode
import org.junit.Assert.assertEquals
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
    fun compactActionIndicesUseAllVisibleNotificationActions() {
        assertEquals(listOf(0), compactActionIndices(1).toList())
        assertEquals(listOf(0, 1), compactActionIndices(2).toList())
        assertEquals(listOf(0, 1, 2), compactActionIndices(3).toList())
        assertEquals(listOf(0, 1, 2), compactActionIndices(4).toList())
    }

    @Test
    fun loadingNotificationProgressBucketUsesTenPercentSteps() {
        assertEquals(null, loadingNotificationProgressBucket(null))
        assertEquals(null, loadingNotificationProgressBucket(0))
        assertEquals(null, loadingNotificationProgressBucket(9))
        assertEquals(null, loadingNotificationProgressBucket(-4))
        assertEquals(10, loadingNotificationProgressBucket(10))
        assertEquals(10, loadingNotificationProgressBucket(19))
        assertEquals(20, loadingNotificationProgressBucket(20))
        assertEquals(100, loadingNotificationProgressBucket(100))
        assertEquals(100, loadingNotificationProgressBucket(112))
    }

    @Test
    fun loadingNotificationTitleOmitsProgressUntilFirstBucket() {
        assertEquals("Loading", loadingNotificationTitle(null))
        assertEquals("Loading - 10%", loadingNotificationTitle(10))
        assertEquals("Loading - 100%", loadingNotificationTitle(100))
    }

    @Test
    fun localNotificationTitleUsesLoadingTitleBeforeTrackMetadataFallback() {
        assertEquals(
            "Loading",
            localPlaybackNotificationTitle(
                baseTitle = null,
                audioMode = JukeboxAudioMode.Off,
                isLoading = true,
                loadingProgressBucket = null
            )
        )
        assertEquals(
            "Loading - 20%",
            localPlaybackNotificationTitle(
                baseTitle = "Track",
                audioMode = JukeboxAudioMode.Nightcore,
                isLoading = true,
                loadingProgressBucket = 20
            )
        )
        assertEquals(
            "The Forever Jukebox",
            localPlaybackNotificationTitle(
                baseTitle = null,
                audioMode = JukeboxAudioMode.Off,
                isLoading = false,
                loadingProgressBucket = null
            )
        )
        assertEquals(
            "Track",
            localPlaybackNotificationTitle(
                baseTitle = "Track",
                audioMode = JukeboxAudioMode.Off,
                isLoading = false,
                loadingProgressBucket = null
            )
        )
        assertEquals(
            "Track (nightcore)",
            localPlaybackNotificationTitle(
                baseTitle = "Track",
                audioMode = JukeboxAudioMode.Nightcore,
                isLoading = false,
                loadingProgressBucket = null
            )
        )
    }

    @Test
    fun notificationArtistFallsBackOnlyWhenTitleIsPresent() {
        assertEquals(
            "The Forever Jukebox",
            playbackNotificationArtist(
                baseTitle = "Track",
                baseArtist = null,
                isLoading = false
            )
        )
        assertEquals(
            "The Forever Jukebox",
            playbackNotificationArtist(
                baseTitle = "Track",
                baseArtist = "",
                isLoading = false
            )
        )
        assertEquals(
            "Artist",
            playbackNotificationArtist(
                baseTitle = "Track",
                baseArtist = "Artist",
                isLoading = false
            )
        )
    }

    @Test
    fun notificationArtistIsBlankWhenTitleIsMissingOrLoading() {
        assertEquals(
            "",
            playbackNotificationArtist(
                baseTitle = null,
                baseArtist = "Artist",
                isLoading = false
            )
        )
        assertEquals(
            "",
            playbackNotificationArtist(
                baseTitle = "",
                baseArtist = "Artist",
                isLoading = false
            )
        )
        assertEquals(
            "",
            playbackNotificationArtist(
                baseTitle = "Track",
                baseArtist = null,
                isLoading = true
            )
        )
    }
}
