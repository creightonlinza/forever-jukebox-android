package com.foreverjukebox.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSessionStateTest {

    private val initial = PlaybackSessionState.Published(PlaybackSessionSnapshot(), 0L, 0L)

    @Test
    fun everyPublishAdvancesTheRevision() {
        val first = nextPublished(initial, PlaybackSessionSnapshot())
        val second = nextPublished(first, PlaybackSessionSnapshot())

        assertEquals(1L, first.revision)
        assertEquals(2L, second.revision)
    }

    @Test
    fun surfaceEpochAdvancesOnlyWhenTheKindOfSurfaceChanges() {
        val loading = nextPublished(
            initial,
            PlaybackSessionSnapshot(PlaybackSessionSurface.Loading(progressBucket = 10))
        )
        val moreLoading = nextPublished(
            loading,
            PlaybackSessionSnapshot(PlaybackSessionSurface.Loading(progressBucket = 20))
        )
        val local = nextPublished(moreLoading, PlaybackSessionSnapshot(PlaybackSessionSurface.Local))
        val sameLocal = nextPublished(
            local,
            PlaybackSessionSnapshot(PlaybackSessionSurface.Local, canSkipNext = true)
        )

        assertEquals(1L, loading.surfaceEpoch)
        assertEquals(1L, moreLoading.surfaceEpoch)
        assertEquals(2L, local.surfaceEpoch)
        assertEquals(2L, sameLocal.surfaceEpoch)
    }

    @Test
    fun castPlayStateChangesKeepTheEpoch() {
        val playing = nextPublished(
            initial,
            PlaybackSessionSnapshot(PlaybackSessionSurface.Cast(isPlaying = true, title = null, artist = null))
        )
        val paused = nextPublished(
            playing,
            PlaybackSessionSnapshot(PlaybackSessionSurface.Cast(isPlaying = false, title = null, artist = null))
        )

        assertEquals(playing.surfaceEpoch, paused.surfaceEpoch)
    }
}
