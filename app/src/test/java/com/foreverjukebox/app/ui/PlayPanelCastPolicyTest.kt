package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // A replacement track's upload/LOAD hides controls even while the old track reports "playing".
        val uploading = ready.copy(castTransfer = CastTransfer.Uploading("job_2", percent = 10))
        val waiting = ready.copy(castTransfer = CastTransfer.WaitingForReceiver("job_2"))

        assertTrue(ready.castControlsReady())
        assertFalse(loading.castControlsReady())
        assertTrue(legacyPending.castControlsReady())
        assertFalse(errored.castControlsReady())
        assertFalse(noTrack.castControlsReady())
        assertFalse(notCasting.castControlsReady())
        assertFalse(uploading.castControlsReady())
        assertFalse(waiting.castControlsReady())
    }

    @Test
    fun resolveCastScreenStatusPrecedenceErrorAnalysisTransferReceiver() {
        val casting = PlaybackState(
            isCasting = true,
            lastJobId = "job_1",
            castPlaybackState = "playing"
        )

        val errored = casting.copy(
            analysisErrorMessage = "boom",
            analysisInFlight = true,
            castTransfer = CastTransfer.Uploading("job_2", percent = 5)
        )
        val analyzing = casting.copy(
            analysisInFlight = true,
            analysisProgress = 42,
            analysisMessage = "Processing beats",
            castTransfer = CastTransfer.Uploading("job_2", percent = 5)
        )
        val uploading = casting.copy(castTransfer = CastTransfer.Uploading("job_2", percent = 63))
        val waiting = casting.copy(castTransfer = CastTransfer.WaitingForReceiver("job_2"))
        val idle = casting

        assertEquals(
            CastScreenStatus.Failed("boom", canRetry = false),
            resolveCastScreenStatus(AppMode.Local, errored)
        )
        assertEquals(
            CastScreenStatus.Analyzing(progress = 42, message = "Processing beats", showCancel = true),
            resolveCastScreenStatus(AppMode.Local, analyzing)
        )
        assertEquals(
            CastScreenStatus.Uploading(percent = 63),
            resolveCastScreenStatus(AppMode.Local, uploading)
        )
        assertEquals(
            CastScreenStatus.WaitingForReceiver,
            resolveCastScreenStatus(AppMode.Local, waiting)
        )
        assertNull(resolveCastScreenStatus(AppMode.Local, idle))
    }

    @Test
    fun resolveCastScreenStatusRetryOnlyForCastPipelineErrors() {
        val castError = PlaybackState(
            isCasting = true,
            lastJobId = "job_1",
            castPlaybackState = "error",
            analysisErrorMessage = "relay unreachable"
        )
        val analysisError = castError.copy(
            castPlaybackState = "playing",
            analysisErrorMessage = "Unsupported audio format"
        )

        assertEquals(
            CastScreenStatus.Failed("relay unreachable", canRetry = true),
            resolveCastScreenStatus(AppMode.Local, castError)
        )
        assertEquals(
            CastScreenStatus.Failed("Unsupported audio format", canRetry = false),
            resolveCastScreenStatus(AppMode.Local, analysisError)
        )
    }

    @Test
    fun resolveCastScreenStatusShowsWaitingForReceiverDrivenLoading() {
        // Server-mode casts have no sender-side transfer; receiver status alone drives the spinner.
        val receiverLoading = PlaybackState(
            isCasting = true,
            lastJobId = "job_1",
            castPlaybackState = "loading",
            isCastLoading = true
        )
        val noTrackYet = PlaybackState(isCasting = true)
        val notCasting = PlaybackState(castPlaybackState = "loading")

        assertEquals(
            CastScreenStatus.WaitingForReceiver,
            resolveCastScreenStatus(AppMode.Server, receiverLoading)
        )
        assertNull(resolveCastScreenStatus(AppMode.Server, noTrackYet))
        assertNull(resolveCastScreenStatus(AppMode.Server, notCasting))
    }

    @Test
    fun isTrackLoadingIncludesSenderTransfer() {
        assertTrue(
            PlaybackState(castTransfer = CastTransfer.Uploading("job_2", percent = null))
                .isTrackLoading()
        )
        assertTrue(
            PlaybackState(castTransfer = CastTransfer.WaitingForReceiver("job_2")).isTrackLoading()
        )
    }

    @Test
    fun stateAfterCastAnalysisCancelClearsAnalysisAndProvisionalMetadataOnly() {
        val current = UiState(
            activeTab = TabId.Play,
            localSelectedFileName = "new-track.mp3",
            localAnalysisJsonPath = "/cache/analysis.json",
            playback = PlaybackState(
                isCasting = true,
                castDeviceName = "Living Room TV",
                lastJobId = "job_1",
                analysisInFlight = true,
                analysisProgress = 55,
                analysisMessage = "Processing beats",
                trackTitle = "New Track",
                trackArtist = "New Artist",
                playTitle = "New Track — New Artist"
            )
        )

        val next = stateAfterCastAnalysisCancel(current)

        assertNull(next.localSelectedFileName)
        assertNull(next.localAnalysisJsonPath)
        assertFalse(next.playback.analysisInFlight)
        assertNull(next.playback.analysisProgress)
        assertNull(next.playback.analysisMessage)
        assertNull(next.playback.trackTitle)
        assertNull(next.playback.trackArtist)
        assertEquals("", next.playback.playTitle)
        // The cast session and its track survive the cancel.
        assertTrue(next.playback.isCasting)
        assertEquals("Living Room TV", next.playback.castDeviceName)
        assertEquals("job_1", next.playback.lastJobId)
        assertEquals(TabId.Play, next.activeTab)
    }

    @Test
    fun stateAfterCastTrackRejectionShowsDialogOnlyAndResetsCastScreenToIdle() {
        val current = UiState(
            activeTab = TabId.Play,
            playback = PlaybackState(
                isCasting = true,
                castDeviceName = "Living Room TV",
                lastJobId = "job_1",
                lastYouTubeId = "abc123def45",
                trackDurationSeconds = 393.0,
                castTotalBeats = 625,
                castTotalBranches = 40,
                castPlaybackState = "error",
                isCastLoading = true,
                castTransfer = CastTransfer.WaitingForReceiver("job_1"),
                analysisErrorMessage = "Sorry, tracks longer than 6 minutes cannot be cast.",
                trackTitle = "Long Track",
                trackArtist = "Artist"
            )
        )

        val next = stateAfterCastTrackRejection(current, "Sorry, too long.")

        // The message lives in the dialog alone.
        assertEquals("Sorry, too long.", next.trackLengthLimitErrorMessage)
        assertNull(next.playback.analysisErrorMessage)
        // The cast screen renders its idle no-track content, not a Failed status.
        assertNull(resolveCastScreenStatus(AppMode.Server, next.playback))
        assertFalse(next.playback.hasCastTrack())
        assertNull(next.playback.trackDurationSeconds)
        assertNull(next.playback.castTotalBeats)
        assertNull(next.playback.castTotalBranches)
        assertNull(next.playback.castPlaybackState)
        assertNull(next.playback.castTransfer)
        assertFalse(next.playback.isCastLoading)
        // The cast session itself survives.
        assertTrue(next.playback.isCasting)
        assertEquals("Living Room TV", next.playback.castDeviceName)
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
