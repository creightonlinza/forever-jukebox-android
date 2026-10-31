package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BeatVisitsUiStateTest {

    private val played = PlaybackState(beatVisits = listOf(1, 2))

    @Test
    fun newTrackClearsVisits() {
        assertEquals(emptyList<Int>(), played.resetForNewTrack(keepLoadVisible = false).beatVisits)
    }

    @Test
    fun autocanonizerStartClearsVisits() {
        assertEquals(emptyList<Int>(), playbackStateAfterAutocanonizerStart(played).beatVisits)
    }
}
