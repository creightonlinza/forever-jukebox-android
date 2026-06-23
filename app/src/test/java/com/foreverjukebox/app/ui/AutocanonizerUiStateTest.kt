package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocanonizerUiStateTest {
    @Test
    fun pauseRetainsCursorTimes() {
        val before = playbackWithCursorTimes()

        val after = playbackStateAfterAutocanonizerPause(before)

        assertFalse(after.isRunning)
        assertTrue(after.isPaused)
        assertEquals(before.autocanonizer, after.autocanonizer)
    }

    @Test
    fun stopAndNaturalCompletionResetCursorTimesButRetainDuration() {
        val after = playbackStateAfterAutocanonizerStop(playbackWithCursorTimes())

        assertFalse(after.isRunning)
        assertFalse(after.isPaused)
        assertEquals("0:00", formatCursorTime(after.autocanonizer.mainSeconds))
        assertEquals("0:00", formatCursorTime(after.autocanonizer.otherSeconds))
        assertEquals(223.0, after.autocanonizer.trackDurationSeconds, 0.0)
    }

    @Test
    fun freshPlaybackResetsCursorTimesButRetainsDuration() {
        val after = playbackStateAfterAutocanonizerStart(playbackWithCursorTimes())

        assertEquals(0.0, after.autocanonizer.mainSeconds, 0.0)
        assertEquals(0.0, after.autocanonizer.otherSeconds, 0.0)
        assertEquals(223.0, after.autocanonizer.trackDurationSeconds, 0.0)
    }

    @Test
    fun newTrackStateResetsCursorsAndUpdatesDuration() {
        val state = autocanonizerUiStateForTrack(trackDurationSeconds = 431.5)

        assertEquals("0:00", formatCursorTime(state.mainSeconds))
        assertEquals("0:00", formatCursorTime(state.otherSeconds))
        assertEquals(431.5, state.trackDurationSeconds, 0.0)
    }

    @Test
    fun trackDurationPrefersAutocanonizerSourceAndNeverUsesPlaybackClock() {
        assertEquals(
            223.0,
            resolveAutocanonizerTrackDuration(
                autocanonizerTrackDuration = 223.0,
                sourceDuration = 999.0
            ),
            0.0
        )
        assertEquals(
            431.5,
            resolveAutocanonizerTrackDuration(
                autocanonizerTrackDuration = null,
                sourceDuration = 431.5
            ),
            0.0
        )
        assertEquals(
            0.0,
            resolveAutocanonizerTrackDuration(
                autocanonizerTrackDuration = null,
                sourceDuration = null
            ),
            0.0
        )
    }

    private fun playbackWithCursorTimes(): PlaybackState {
        return PlaybackState(
            isRunning = true,
            autocanonizer = AutocanonizerUiState(
                mainSeconds = 62.0,
                otherSeconds = 135.0,
                trackDurationSeconds = 223.0
            )
        )
    }
}
