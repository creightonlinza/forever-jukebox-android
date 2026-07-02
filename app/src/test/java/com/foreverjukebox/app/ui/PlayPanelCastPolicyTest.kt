package com.foreverjukebox.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayPanelCastPolicyTest {

    @Test
    fun hasCastTrackWhenJobIdIsAvailable() {
        val withYoutube = PlaybackState(lastYouTubeId = "abc123def45")
        val withJob = PlaybackState(lastJobId = "job_1")
        val empty = PlaybackState()

        assertFalse(withYoutube.hasCastTrack())
        assertTrue(withJob.hasCastTrack())
        assertFalse(empty.hasCastTrack())
    }

    @Test
    fun castControlsReadyHidesOnlyForReceiverLoadingOrError() {
        val ready = PlaybackState(
            isCasting = true,
            lastJobId = "job_1",
            lastYouTubeId = "abc123def45",
            castPlaybackState = "playing",
            analysisErrorMessage = null
        )
        val loading = ready.copy(castPlaybackState = "loading")
        val legacyPending = ready.copy(isCastLoading = true, analysisInFlight = true)
        val errored = ready.copy(analysisErrorMessage = "load failed")
        val noTrack = ready.copy(lastJobId = null)
        val notCasting = ready.copy(isCasting = false)

        assertTrue(ready.castControlsReady())
        assertFalse(loading.castControlsReady())
        assertTrue(legacyPending.castControlsReady())
        assertFalse(errored.castControlsReady())
        assertFalse(noTrack.castControlsReady())
        assertFalse(notCasting.castControlsReady())
    }

    @Test
    fun shouldShowPlaybackTransportInLocalAndReadyCastStates() {
        val local = PlaybackState(isCasting = false)
        val castingLoading = PlaybackState(
            isCasting = true,
            lastJobId = "job_1",
            lastYouTubeId = "abc123def45",
            castPlaybackState = "loading"
        )
        val castingReady = PlaybackState(
            isCasting = true,
            lastJobId = "job_1",
            lastYouTubeId = "abc123def45",
            castPlaybackState = "playing",
            analysisErrorMessage = null
        )

        assertTrue(shouldShowPlaybackTransport(local))
        assertFalse(shouldShowPlaybackTransport(castingLoading))
        assertTrue(shouldShowPlaybackTransport(castingReady))
    }

    @Test
    fun resolveListenContentModeSelectsCastBeforeAnythingElse() {
        val playback = PlaybackState(
            isCasting = true,
            audioLoaded = true,
            analysisLoaded = true
        )

        assertEquals(ListenContentMode.Cast, resolveListenContentMode(playback))
    }

    @Test
    fun resolveListenContentModeSelectsLocalWhenAudioAndAnalysisReady() {
        val playback = PlaybackState(
            isCasting = false,
            audioLoaded = true,
            analysisLoaded = true
        )

        assertEquals(ListenContentMode.LocalReady, resolveListenContentMode(playback))
    }

    @Test
    fun resolveListenContentModeSelectsEmptyWhenIdleWithNoTrack() {
        val playback = PlaybackState(
            isCasting = false,
            audioLoaded = false,
            analysisLoaded = false,
            analysisInFlight = false,
            analysisCalculating = false,
            audioLoading = false,
            analysisErrorMessage = null
        )

        assertEquals(ListenContentMode.Empty, resolveListenContentMode(playback))
    }

    @Test
    fun resolveListenContentModeReturnsNoneDuringLoadingOrError() {
        val loading = PlaybackState(
            isCasting = false,
            analysisInFlight = true
        )
        val errored = PlaybackState(
            isCasting = false,
            analysisErrorMessage = "boom"
        )

        assertEquals(ListenContentMode.None, resolveListenContentMode(loading))
        assertEquals(ListenContentMode.None, resolveListenContentMode(errored))
    }
}
