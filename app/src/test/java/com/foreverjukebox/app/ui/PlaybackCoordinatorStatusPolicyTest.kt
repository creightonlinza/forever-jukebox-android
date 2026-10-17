package com.foreverjukebox.app.ui

import java.io.IOException
import org.junit.Assert.assertFalse
import com.foreverjukebox.app.playback.PlaybackSessionSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorStatusPolicyTest {

    @Test
    fun outOfMemoryIsTreatedAsTransientDecodeError() {
        // Heap pressure is momentary and unrelated to the cached file's integrity, so a
        // cached track must not be discarded when decoding it runs out of memory.
        assertTrue(isTransientDecodeError(OutOfMemoryError("decode")))
    }

    @Test
    fun genuineDecodeFailuresAreNotTransient() {
        // Non-transient errors (e.g. an unreadable/corrupt file) should still allow the
        // cache entry to be discarded and re-fetched.
        assertFalse(isTransientDecodeError(IOException("bad file")))
        assertFalse(isTransientDecodeError(IllegalStateException("No audio track found")))
        assertFalse(isTransientDecodeError(IllegalArgumentException("bad format")))
        assertFalse(isTransientDecodeError(RuntimeException("boom")))
    }

    @Test
    fun analysisInProgressStatusIncludesQueuedDownloadingAndProcessing() {
        assertTrue(isAnalysisInProgressStatus("downloading"))
        assertTrue(isAnalysisInProgressStatus("queued"))
        assertTrue(isAnalysisInProgressStatus("processing"))
    }

    @Test
    fun analysisInProgressStatusExcludesFailedCompleteAndNull() {
        assertFalse(isAnalysisInProgressStatus("failed"))
        assertFalse(isAnalysisInProgressStatus("complete"))
        assertFalse(isAnalysisInProgressStatus("unknown"))
        assertFalse(isAnalysisInProgressStatus(null))
    }

    @Test
    fun resolveLoadedTrackMetaPrefersBackendMetadataWhenPresent() {
        val resolved = resolveLoadedTrackMeta(
            backendTrackMeta = TrackMetaJson(
                title = " Backend Title ",
                artist = " Backend Artist ",
                duration = 123.0
            ),
            currentPlayback = PlaybackState(
                trackTitle = "Selected Title",
                trackArtist = "Selected Artist"
            )
        )

        assertEquals("Backend Title", resolved.title)
        assertEquals("Backend Artist", resolved.artist)
        assertEquals(123.0, resolved.durationSeconds)
    }

    @Test
    fun resolveLoadedTrackMetaFallsBackToExistingMetadataWhenBackendIsMissing() {
        val resolved = resolveLoadedTrackMeta(
            backendTrackMeta = TrackMetaJson(
                title = " ",
                artist = null,
                duration = 45.0
            ),
            currentPlayback = PlaybackState(
                trackTitle = "Selected Title",
                trackArtist = "Selected Artist"
            )
        )

        assertEquals("Selected Title", resolved.title)
        assertEquals("Selected Artist", resolved.artist)
        assertEquals(45.0, resolved.durationSeconds)
    }

    @Test
    fun resolveLoadedTrackMetaReturnsNullWhenNoMetadataExists() {
        val resolved = resolveLoadedTrackMeta(
            backendTrackMeta = null,
            currentPlayback = PlaybackState(
                trackTitle = "",
                trackArtist = "   "
            )
        )

        assertNull(resolved.title)
        assertNull(resolved.artist)
        assertNull(resolved.durationSeconds)
    }

    @Test
    fun playbackSessionMapsIdleToHidden() {
        assertEquals(
            PlaybackSessionSurface.Hidden,
            resolvePlaybackSessionSurface(PlaybackState())
        )
    }

    @Test
    fun playbackSessionMapsLoadingToBucketedLoading() {
        assertEquals(
            PlaybackSessionSurface.Loading(progressBucket = 40),
            resolvePlaybackSessionSurface(
                PlaybackState(
                    analysisInFlight = true,
                    analysisProgress = 42
                )
            )
        )
    }

    // A track-to-track reset (a new load follows immediately) must leave state that
    // keeps the service alive: resolving to Hidden would let a racing sync stop the
    // foreground service mid-transition, which a backgrounded app cannot reliably
    // restart — and without it the OS restricts the codec the load needs.
    @Test
    fun resetIntoNewLoadKeepsPlaybackSessionVisible() {
        val playing = PlaybackState(
            audioLoaded = true,
            analysisLoaded = true,
            isRunning = true,
            trackTitle = "Outgoing"
        )
        val reset = playing.resetForNewTrack(keepLoadVisible = true)
        assertEquals(
            PlaybackSessionSurface.Loading(progressBucket = null),
            resolvePlaybackSessionSurface(reset)
        )
        assertFalse(reset.audioLoaded)
        assertFalse(reset.isRunning)
        assertNull(reset.trackTitle)
    }

    // A reset that is not followed by a load (stop, cast handoff, lifecycle teardown)
    // must not leave a phantom loading state behind.
    @Test
    fun resetWithoutNewLoadResolvesHidden() {
        val playing = PlaybackState(audioLoaded = true, analysisLoaded = true, isRunning = true)
        assertEquals(
            PlaybackSessionSurface.Hidden,
            resolvePlaybackSessionSurface(playing.resetForNewTrack(keepLoadVisible = false))
        )
    }

    @Test
    fun playbackSessionMapsLoadedTrackToLocal() {
        assertEquals(
            PlaybackSessionSurface.Local,
            resolvePlaybackSessionSurface(
                PlaybackState(
                    audioLoaded = true,
                    analysisLoaded = true
                )
            )
        )
    }

    @Test
    fun playbackSessionKeepsReadyStateForPlayAfterLoadedHandoff() {
        val readyForAutoPlay = PlaybackState(
            playAfterLoaded = true,
            audioLoaded = true,
            analysisLoaded = true
        )

        assertEquals(
            PlaybackSessionSurface.Local,
            resolvePlaybackSessionSurface(readyForAutoPlay)
        )
    }

    @Test
    fun playbackSessionMapsTransportStates() {
        assertEquals(
            PlaybackSessionSurface.Local,
            resolvePlaybackSessionSurface(PlaybackState(isRunning = true))
        )
        assertEquals(
            PlaybackSessionSurface.Local,
            resolvePlaybackSessionSurface(PlaybackState(isPaused = true))
        )
    }

    @Test
    fun playbackSessionHidesUnreadyOrFailedTracks() {
        assertEquals(
            PlaybackSessionSurface.Hidden,
            resolvePlaybackSessionSurface(
                PlaybackState(
                    playAfterLoaded = true,
                    audioLoaded = true,
                    analysisLoaded = false
                )
            )
        )
        assertEquals(
            PlaybackSessionSurface.Hidden,
            resolvePlaybackSessionSurface(
                PlaybackState(
                    playAfterLoaded = true,
                    audioLoaded = true,
                    analysisLoaded = true,
                    analysisErrorMessage = "boom"
                )
            )
        )
    }

    @Test
    fun playbackSessionKeepsRetryableFailedLoadVisibleWhenRequested() {
        val failed = PlaybackState(
            analysisErrorMessage = "Loading failed.",
            lastJobId = "job_123"
        )

        assertEquals(
            PlaybackSessionSurface.Hidden,
            resolvePlaybackSessionSurface(failed)
        )
        assertEquals(
            PlaybackSessionSurface.LoadFailed,
            resolvePlaybackSessionSurface(
                playback = failed,
                keepFailedLoadVisible = true
            )
        )
    }

    @Test
    fun playbackSessionWouldHideIntermediateCachedAudioState() {
        assertEquals(
            PlaybackSessionSurface.Hidden,
            resolvePlaybackSessionSurface(
                PlaybackState(
                    audioLoaded = true,
                    analysisLoaded = false,
                    audioLoading = false,
                    analysisInFlight = false,
                    analysisCalculating = false
                )
            )
        )
    }

    @Test
    fun playbackSessionKeepsCachedAudioDecodeVisibleWhileLoading() {
        assertEquals(
            PlaybackSessionSurface.Loading(progressBucket = null),
            resolvePlaybackSessionSurface(
                PlaybackState(
                    audioLoaded = true,
                    analysisLoaded = false,
                    audioLoading = true
                )
            )
        )
    }

    @Test
    fun playbackSessionMapsCastNotificationState() {
        assertEquals(
            PlaybackSessionSurface.Cast(
                isPlaying = false,
                title = "Cast Track",
                artist = "Cast Artist"
            ),
            resolvePlaybackSessionSurface(
                PlaybackState(
                    isCasting = true,
                    trackTitle = "Cast Track",
                    trackArtist = "Cast Artist",
                    castDeviceName = "Living Room"
                )
            )
        )
    }

    @Test
    fun playbackSessionSkipAvailabilityFollowsPlaylistState() {
        val state = UiState(
            playlist = JukeboxPlaylistState(
                tracks = listOf(
                    PlaylistTrack("one", PlaylistTrackType.Server, "One", null),
                    PlaylistTrack("two", PlaylistTrackType.Server, "Two", null),
                    PlaylistTrack("three", PlaylistTrackType.Server, "Three", null)
                ),
                currentIndex = 1
            )
        )

        val snapshot = resolvePlaybackSessionSnapshot(state)
        assertTrue(snapshot.canSkipPrevious)
        assertTrue(snapshot.canSkipNext)
    }

    @Test
    fun playbackSessionSkipAvailabilityDisablesWhileTrackLoading() {
        val state = UiState(
            playback = PlaybackState(audioLoading = true),
            playlist = JukeboxPlaylistState(
                tracks = listOf(
                    PlaylistTrack("one", PlaylistTrackType.Server, "One", null),
                    PlaylistTrack("two", PlaylistTrackType.Server, "Two", null),
                    PlaylistTrack("three", PlaylistTrackType.Server, "Three", null)
                ),
                currentIndex = 1
            )
        )

        val snapshot = resolvePlaybackSessionSnapshot(state)
        assertFalse(snapshot.canSkipPrevious)
        assertFalse(snapshot.canSkipNext)
    }
}
