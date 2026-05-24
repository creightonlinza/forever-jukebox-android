package com.foreverjukebox.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFullscreenVisualizationPolicyTest {

    @Test
    fun fullscreenCanOpenOnlyForLocalReadyPlayback() {
        assertFalse(shouldShowFullscreenVisualization(PlaybackState()))
        assertFalse(
            shouldShowFullscreenVisualization(
                PlaybackState(
                    audioLoaded = true,
                    analysisLoaded = true,
                    isCasting = true
                )
            )
        )
        assertFalse(
            shouldShowFullscreenVisualization(
                PlaybackState(
                    audioLoaded = true,
                    analysisLoaded = true,
                    analysisErrorMessage = "Loading failed."
                )
            )
        )
        assertTrue(
            shouldShowFullscreenVisualization(
                PlaybackState(
                    audioLoaded = true,
                    analysisLoaded = true
                )
            )
        )
    }

    @Test
    fun fullscreenOpenLeavesInvalidStateClosed() {
        val state = UiState(playback = PlaybackState())

        assertSame(state, stateAfterFullscreenVisualizationOpen(state))
    }

    @Test
    fun fullscreenOpenMarksReadyStateVisible() {
        val state = UiState(
            playback = PlaybackState(
                audioLoaded = true,
                analysisLoaded = true
            )
        )

        assertTrue(stateAfterFullscreenVisualizationOpen(state).fullscreenVisualizationVisible)
    }

    @Test
    fun fullscreenCloseActionMapsToHiddenState() {
        val state = UiState(fullscreenVisualizationVisible = true)

        assertFalse(stateAfterFullscreenVisualizationClose(state).fullscreenVisualizationVisible)
    }

    @Test
    fun fullscreenSyncClosesWhenSessionIsClearedOrFailed() {
        val cleared = UiState(
            playback = PlaybackState(),
            fullscreenVisualizationVisible = true
        )
        val failed = UiState(
            playback = PlaybackState(
                audioLoaded = true,
                analysisLoaded = true,
                analysisErrorMessage = "boom"
            ),
            fullscreenVisualizationVisible = true
        )

        assertFalse(stateAfterFullscreenVisualizationSync(cleared).fullscreenVisualizationVisible)
        assertFalse(stateAfterFullscreenVisualizationSync(failed).fullscreenVisualizationVisible)
    }
}
