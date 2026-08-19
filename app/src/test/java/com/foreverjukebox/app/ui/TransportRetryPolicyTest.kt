package com.foreverjukebox.app.ui

import com.foreverjukebox.app.BuildConfig
import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the transport-retry contract end to end at the policy layer:
 *
 * 1. Visibility — [resolvePlaybackServiceSession] (the UiState entry point the
 *    coordinator syncs through) must keep a retryable failure on screen as
 *    [PlaybackServiceSession.LocalFailed]. Any failure with a retryable track id
 *    resolves to LocalFailed, never Hidden — including when the track is still
 *    fully loaded in memory. Tearing the notification down strands the user:
 *    a stopped foreground service cannot be restarted from the background.
 * 2. Press behavior — [transportRetryPressAction] decides what the button does,
 *    independently of visibility: resume the in-memory track when it is still
 *    playable, otherwise reload via the retry flow.
 *
 * These are separate rules on purpose. Gating visibility on loaded-track state
 * (or any press-behavior concern) is the drift this test exists to reject.
 *
 * One precedence exception: audibly running playback outranks the failed surface,
 * because the failed surface drops Pause/Stop presses — the transport must keep
 * controlling audio that is actually playing.
 */
class TransportRetryPolicyTest {

    // In server mode a retryable failure keeps the failed notification; builds
    // without server mode have no retry flow, so the session hides instead.
    private val expectedRetryableFailure =
        if (BuildConfig.SERVER_MODE_AVAILABLE) {
            PlaybackServiceSession.LocalFailed
        } else {
            PlaybackServiceSession.Hidden
        }

    private fun serverState(playback: PlaybackState) = UiState(
        appMode = AppMode.Server,
        playback = playback
    )

    @Test
    fun loadFailureWithJobIdKeepsFailedNotification() {
        val state = serverState(
            PlaybackState(
                analysisErrorMessage = "Loading failed.",
                lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2"
            )
        )

        assertEquals(expectedRetryableFailure, resolvePlaybackServiceSession(state))
    }

    @Test
    fun playbackFailureWithLoadedTrackKeepsFailedNotification() {
        // The track is still fully loaded, so the press will resume from memory —
        // but that is the press's concern. The notification must stay up either
        // way; resolving to Hidden here makes the transport button unreachable.
        val state = serverState(
            PlaybackState(
                analysisErrorMessage = "Playback failed.",
                lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2",
                audioLoaded = true,
                analysisLoaded = true
            )
        )

        assertEquals(expectedRetryableFailure, resolvePlaybackServiceSession(state))
        assertEquals(
            TransportRetryPressAction.ResumePlayback,
            transportRetryPressAction(state.playback)
        )
    }

    @Test
    fun pausedFailedTrackKeepsFailedNotification() {
        val state = serverState(
            PlaybackState(
                analysisErrorMessage = "Playback failed.",
                lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2",
                audioLoaded = true,
                analysisLoaded = true,
                isPaused = true
            )
        )

        // Without server mode there is no retry flow, and the paused loaded track
        // falls through to its normal paused notification instead.
        val expected = if (BuildConfig.SERVER_MODE_AVAILABLE) {
            PlaybackServiceSession.LocalFailed
        } else {
            PlaybackServiceSession.LocalPaused
        }
        assertEquals(expected, resolvePlaybackServiceSession(state))
    }

    @Test
    fun youtubeOnlyFailureBeforeJobAssignedKeepsFailedNotification() {
        // A load can fail before the server assigns a job id (e.g. the initial
        // lookup request never lands). The YouTube source id is enough to retry
        // with, so the notification must survive.
        val state = serverState(
            PlaybackState(
                analysisErrorMessage = "Network error.",
                lastYouTubeId = "dQw4w9WgXcQ"
            )
        )

        assertEquals(expectedRetryableFailure, resolvePlaybackServiceSession(state))
    }

    @Test
    fun playlistSkipLoadFailureKeepsFailedNotificationAndRetriesOnPress() {
        // Skipping to the next playlist track resets loaded audio/analysis before
        // the new load; when that load fails the button must stay up and a press
        // must run the retry flow (nothing is left in memory to resume).
        val state = serverState(
            PlaybackState(
                analysisErrorMessage = "Loading failed.",
                lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2",
                audioLoaded = false,
                analysisLoaded = false
            )
        )

        assertEquals(expectedRetryableFailure, resolvePlaybackServiceSession(state))
        assertEquals(
            TransportRetryPressAction.RetryLoad,
            transportRetryPressAction(state.playback)
        )
    }

    @Test
    fun runningTrackWithSurfacedErrorKeepsPlayingTransport() {
        // An error surfaced without stopping playback must not swap the notification
        // to the failed surface: that surface ignores Pause/Stop, so a headset pause
        // would be dropped while audio keeps playing, and its Retry press would pause
        // the running track. The failed surface takes over once playback stops.
        val state = serverState(
            PlaybackState(
                analysisErrorMessage = "Autocanonizer not ready.",
                lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2",
                audioLoaded = true,
                analysisLoaded = true,
                isRunning = true
            )
        )

        assertEquals(PlaybackServiceSession.LocalPlaying, resolvePlaybackServiceSession(state))
    }

    @Test
    fun unparseableIdsDoNotAdvertiseRetry() {
        // A retry id the load pipeline cannot parse would make the retry press a
        // silent no-op, stranding the failed notification — so such states hide it
        // instead of advertising a dead-end button.
        val state = serverState(
            PlaybackState(
                analysisErrorMessage = "Loading failed.",
                lastYouTubeId = "not a parseable id"
            )
        )

        assertEquals(PlaybackServiceSession.Hidden, resolvePlaybackServiceSession(state))
    }

    @Test
    fun failureWithNoRetryableIdHidesNotification() {
        val state = serverState(
            PlaybackState(analysisErrorMessage = "Loading failed.")
        )

        assertEquals(PlaybackServiceSession.Hidden, resolvePlaybackServiceSession(state))
    }

    @Test
    fun localModeFailureHidesNotification() {
        // Local analysis has no server retry flow, so its failures never keep a
        // failed notification.
        val state = UiState(
            appMode = AppMode.Local,
            playback = PlaybackState(
                analysisErrorMessage = "Local analysis failed.",
                lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2"
            )
        )

        assertEquals(PlaybackServiceSession.Hidden, resolvePlaybackServiceSession(state))
    }

    @Test
    fun retryInFlightShowsLoadingNotFailed() {
        // Once a retry press restarts the load, the loading notification takes
        // over from the failed one on the same live service — even if the error
        // message is still set while the new load spins up.
        val state = serverState(
            PlaybackState(
                analysisErrorMessage = "Loading failed.",
                analysisInFlight = true,
                lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2"
            )
        )

        assertEquals(
            PlaybackServiceSessionKind.LocalLoading,
            resolvePlaybackServiceSession(state).kind
        )
    }

    @Test
    fun pressResumesOnlyWhenAudioAndAnalysisAreLoaded() {
        val playable = PlaybackState(
            analysisErrorMessage = "Playback failed.",
            audioLoaded = true,
            analysisLoaded = true
        )

        assertEquals(
            TransportRetryPressAction.ResumePlayback,
            transportRetryPressAction(playable)
        )
        assertEquals(
            TransportRetryPressAction.RetryLoad,
            transportRetryPressAction(playable.copy(audioLoaded = false))
        )
        assertEquals(
            TransportRetryPressAction.RetryLoad,
            transportRetryPressAction(playable.copy(analysisLoaded = false))
        )
        assertEquals(
            TransportRetryPressAction.RetryLoad,
            transportRetryPressAction(playable.copy(isCasting = true))
        )
    }
}
