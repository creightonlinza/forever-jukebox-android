package com.foreverjukebox.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorStatusPolicyTest {

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
    fun playbackServiceSessionMapsIdleToHidden() {
        assertEquals(
            PlaybackServiceSession.Hidden,
            resolvePlaybackServiceSession(PlaybackState())
        )
    }

    @Test
    fun playbackServiceSessionMapsLoadingToLocalLoading() {
        assertEquals(
            PlaybackServiceSession.LocalLoading(progress = 42),
            resolvePlaybackServiceSession(
                PlaybackState(
                    analysisInFlight = true,
                    analysisProgress = 42
                )
            )
        )
    }

    @Test
    fun playbackServiceSessionMapsLoadedTrackToLocalReady() {
        assertEquals(
            PlaybackServiceSession.LocalReady,
            resolvePlaybackServiceSession(
                PlaybackState(
                    audioLoaded = true,
                    analysisLoaded = true
                )
            )
        )
    }

    @Test
    fun playbackServiceSessionKeepsReadyStateForPlayAfterLoadedHandoff() {
        val readyForAutoPlay = PlaybackState(
            playAfterLoaded = true,
            audioLoaded = true,
            analysisLoaded = true
        )

        assertEquals(
            PlaybackServiceSession.LocalReady,
            resolvePlaybackServiceSession(readyForAutoPlay)
        )
    }

    @Test
    fun playbackServiceSessionMapsTransportStates() {
        assertEquals(
            PlaybackServiceSession.LocalPlaying,
            resolvePlaybackServiceSession(PlaybackState(isRunning = true))
        )
        assertEquals(
            PlaybackServiceSession.LocalPaused,
            resolvePlaybackServiceSession(PlaybackState(isPaused = true))
        )
    }

    @Test
    fun playbackServiceSessionHidesUnreadyOrFailedTracks() {
        assertEquals(
            PlaybackServiceSession.Hidden,
            resolvePlaybackServiceSession(
                PlaybackState(
                    playAfterLoaded = true,
                    audioLoaded = true,
                    analysisLoaded = false
                )
            )
        )
        assertEquals(
            PlaybackServiceSession.Hidden,
            resolvePlaybackServiceSession(
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
    fun playbackServiceSessionWouldHideIntermediateCachedAudioState() {
        assertEquals(
            PlaybackServiceSession.Hidden,
            resolvePlaybackServiceSession(
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
    fun playbackServiceSessionKeepsCachedAudioDecodeVisibleWhileLoading() {
        assertEquals(
            PlaybackServiceSession.LocalLoading(progress = null),
            resolvePlaybackServiceSession(
                PlaybackState(
                    audioLoaded = true,
                    analysisLoaded = false,
                    audioLoading = true
                )
            )
        )
    }

    @Test
    fun playbackServiceSessionMapsCastNotificationState() {
        assertEquals(
            PlaybackServiceSession.Cast(
                isPlaying = false,
                title = "Cast Track",
                artist = "Cast Artist",
                deviceName = "Living Room"
            ),
            resolvePlaybackServiceSession(
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
    fun playbackServiceSkipAvailabilityFollowsPlaylistState() {
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

        assertEquals(
            PlaybackServiceSkipAvailability(canSkipPrevious = true, canSkipNext = true),
            resolvePlaybackServiceSkipAvailability(state)
        )
    }

    @Test
    fun playbackServiceSkipAvailabilityDisablesWhileTrackLoading() {
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

        assertEquals(
            PlaybackServiceSkipAvailability(canSkipPrevious = false, canSkipNext = false),
            resolvePlaybackServiceSkipAvailability(state)
        )
    }
}
