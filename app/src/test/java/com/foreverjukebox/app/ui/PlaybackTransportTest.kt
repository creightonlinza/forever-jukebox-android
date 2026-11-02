package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransportTest {

    @Test
    fun stopAllPlaybackTransportsInvokesAllStops() {
        val controls = FakeStopControls()

        stopAllPlaybackTransports(controls)

        assertEquals(1, controls.stopJukeboxCalls)
        assertEquals(1, controls.stopAutocanonizerCalls)
        assertEquals(1, controls.stopExternalCalls)
    }

    @Test
    fun startAutocanonizerTransportStartsExternalOnSuccess() {
        val controls = FakeAutocanonizerControls(startResult = true)

        val started = startAutocanonizerTransport(
            controls = controls,
            index = 12,
            resetTimers = true
        )

        assertTrue(started)
        assertEquals(1, controls.requestFocusCalls)
        assertEquals(1, controls.resetCalls)
        assertEquals(1, controls.startCalls)
        assertEquals(12, controls.lastStartIndex)
        assertEquals(1, controls.startExternalCalls)
        assertEquals(true, controls.lastResetTimers)
    }

    @Test
    fun startAutocanonizerTransportSkipsExternalOnFailure() {
        val controls = FakeAutocanonizerControls(startResult = false)

        val started = startAutocanonizerTransport(
            controls = controls,
            index = 7,
            resetTimers = false
        )

        assertFalse(started)
        assertEquals(1, controls.requestFocusCalls)
        assertEquals(1, controls.resetCalls)
        assertEquals(1, controls.startCalls)
        assertEquals(7, controls.lastStartIndex)
        assertEquals(0, controls.startExternalCalls)
    }

    @Test
    fun startAutocanonizerTransportSkipsStartWhenFocusDenied() {
        val controls = FakeAutocanonizerControls(startResult = true, focusResult = false)

        val started = startAutocanonizerTransport(
            controls = controls,
            index = 7,
            resetTimers = false
        )

        assertFalse(started)
        assertEquals(1, controls.requestFocusCalls)
        assertEquals(0, controls.resetCalls)
        assertEquals(0, controls.startCalls)
        assertEquals(0, controls.startExternalCalls)
    }

    @Test
    fun seekOrStartJukeboxAtBeatSeeksOnlyWhenAlreadyRunning() {
        val controls = FakeJukeboxBeatControls(
            isPlayingInitially = true,
            seekResults = mutableListOf(true)
        )

        val result = seekOrStartJukeboxAtBeat(
            controls = controls,
            index = 5,
            data = null
        )

        assertTrue(result.success)
        assertFalse(result.startedPlayback)
        assertEquals(1, controls.seekCalls)
        assertEquals(emptyList<Int>(), controls.startIndexes)
        assertEquals(5, controls.lastSeekIndex)
    }

    @Test
    fun seekOrStartJukeboxAtBeatStartsOnTheBeatWithoutSeekingWhenNotRunning() {
        val controls = FakeJukeboxBeatControls(
            isPlayingInitially = false,
            seekResults = mutableListOf()
        )

        val result = seekOrStartJukeboxAtBeat(
            controls = controls,
            index = 9,
            data = null
        )

        assertTrue(result.success)
        assertTrue(result.startedPlayback)
        assertEquals(0, controls.seekCalls)
        assertEquals(listOf(9), controls.startIndexes)
    }

    @Test
    fun seekOrStartJukeboxAtBeatFailsWhenSeekFailsWhileRunning() {
        val controls = FakeJukeboxBeatControls(
            isPlayingInitially = true,
            seekResults = mutableListOf(false)
        )

        val result = seekOrStartJukeboxAtBeat(
            controls = controls,
            index = 2,
            data = null
        )

        assertFalse(result.success)
        assertFalse(result.startedPlayback)
        assertEquals(emptyList<Int>(), controls.startIndexes)
    }

    @Test
    fun seekOrStartJukeboxAtBeatFailsWhenStartFails() {
        val controls = FakeJukeboxBeatControls(
            isPlayingInitially = false,
            seekResults = mutableListOf(),
            startResult = false
        )

        val result = seekOrStartJukeboxAtBeat(
            controls = controls,
            index = 4,
            data = null
        )

        assertFalse(result.success)
        assertFalse(result.startedPlayback)
        assertEquals(listOf(4), controls.startIndexes)
    }
}

private class FakeStopControls : TransportStopControls {
    var stopJukeboxCalls = 0
    var stopAutocanonizerCalls = 0
    var stopWubMachineCalls = 0
    var stopExternalCalls = 0

    override fun stopJukeboxPlayback() {
        stopJukeboxCalls += 1
    }

    override fun stopAutocanonizerPlayback() {
        stopAutocanonizerCalls += 1
    }

    override fun stopWubMachinePlayback() {
        stopWubMachineCalls += 1
    }

    override fun stopExternalPlayback() {
        stopExternalCalls += 1
    }
}

private class FakeAutocanonizerControls(
    private val startResult: Boolean,
    private val focusResult: Boolean = true
) : AutocanonizerTransportControls {
    var requestFocusCalls = 0
    var resetCalls = 0
    var startCalls = 0
    var startExternalCalls = 0
    var lastStartIndex: Int? = null
    var lastResetTimers: Boolean? = null

    override fun requestAudioFocus(): Boolean {
        requestFocusCalls += 1
        return focusResult
    }

    override fun resetVisualization() {
        resetCalls += 1
    }

    override fun startAtIndex(index: Int): Boolean {
        startCalls += 1
        lastStartIndex = index
        return startResult
    }

    override fun startExternalPlayback(resetTimers: Boolean) {
        startExternalCalls += 1
        lastResetTimers = resetTimers
    }
}

private class FakeJukeboxBeatControls(
    isPlayingInitially: Boolean,
    private val seekResults: MutableList<Boolean>,
    private val startResult: Boolean = true
) : JukeboxBeatSelectControls {
    private var playing = isPlayingInitially
    var seekCalls = 0
    val startIndexes = mutableListOf<Int>()
    var lastSeekIndex: Int? = null

    override fun seekToBeat(index: Int, data: com.foreverjukebox.app.engine.VisualizationData?): Boolean {
        seekCalls += 1
        lastSeekIndex = index
        return seekResults.removeFirstOrNull() ?: true
    }

    override fun isPlaying(): Boolean = playing

    override fun startAtBeat(index: Int, data: com.foreverjukebox.app.engine.VisualizationData?): Boolean {
        startIndexes += index
        if (startResult) {
            playing = true
        }
        return startResult
    }
}
