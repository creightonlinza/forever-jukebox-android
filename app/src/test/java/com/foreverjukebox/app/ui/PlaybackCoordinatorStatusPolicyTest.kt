package com.foreverjukebox.app.ui

import org.junit.Assert.assertFalse
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
    fun playbackServiceStaysAliveAfterLoadingWhenPlayAfterLoadedWillStart() {
        val readyForAutoPlay = PlaybackState(
            playAfterLoaded = true,
            audioLoaded = true,
            analysisLoaded = true
        )

        assertTrue(shouldKeepPlaybackServiceAfterLoading(readyForAutoPlay))
        assertTrue(shouldKeepPlaybackServiceAfterLoading(PlaybackState(isRunning = true)))
        assertTrue(shouldKeepPlaybackServiceAfterLoading(PlaybackState(isPaused = true)))
    }

    @Test
    fun playbackServiceCanStopAfterLoadingWhenNothingWillUseIt() {
        assertFalse(shouldKeepPlaybackServiceAfterLoading(PlaybackState()))
        assertFalse(
            shouldKeepPlaybackServiceAfterLoading(
                PlaybackState(
                    playAfterLoaded = true,
                    audioLoaded = true,
                    analysisLoaded = false
                )
            )
        )
        assertFalse(
            shouldKeepPlaybackServiceAfterLoading(
                PlaybackState(
                    playAfterLoaded = true,
                    audioLoaded = true,
                    analysisLoaded = true,
                    analysisErrorMessage = "boom"
                )
            )
        )
    }
}
