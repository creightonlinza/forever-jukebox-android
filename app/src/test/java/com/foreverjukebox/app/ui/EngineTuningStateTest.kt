package com.foreverjukebox.app.ui

import com.foreverjukebox.app.engine.AnchorParam
import com.foreverjukebox.app.engine.CustomAnchor
import com.foreverjukebox.app.engine.JukeboxConfig
import com.foreverjukebox.app.engine.withMinimumJumpDistancePercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTuningStateTest {

    @Test
    fun defaultEngineConfigPercentsMatchTuningStateDefaults() {
        // buildSavedTuningParams compares against TuningState() defaults, so the engine
        // defaults must map onto the same percent values or captures would drift.
        val state = engineTuningState(config = JukeboxConfig(), computedThreshold = null)
        val defaults = TuningState()

        assertEquals(defaults.minProb, state.minProb)
        assertEquals(defaults.maxProb, state.maxProb)
        assertEquals(defaults.ramp, state.ramp)
    }

    @Test
    fun defaultEngineConfigSerializesToNoParams() {
        val raw = TuningParamsCodec.buildSavedTuningParams(
            tuning = engineTuningState(config = JukeboxConfig(), computedThreshold = 31),
            audioModeWireValue = JukeboxAudioMode.Off.wireValue
        )

        assertNull(raw)
    }

    @Test
    fun fullyTunedEngineConfigSerializesInEngineCaptureFormat() {
        val config = JukeboxConfig(
            currentThreshold = 45,
            justBackwards = true,
            removeSequentialBranches = true,
            minRandomBranchChance = 0.3,
            maxRandomBranchChance = 0.8,
            randomBranchChanceDelta = 0.04
        ).withMinimumJumpDistancePercent(20)

        val raw = TuningParamsCodec.buildSavedTuningParams(
            tuning = engineTuningState(
                config = config,
                computedThreshold = 31,
                deletedEdgeIds = listOf(3, 9),
                anchor = AnchorParam.EdgeId(7)
            ),
            audioModeWireValue = "lofi"
        )

        assertEquals("jb=1&bl=20&sq=0&thresh=45&bp=30,80,20&d=3,9&ab=7&am=lofi", raw)
    }

    @Test
    fun autoThresholdIsOmitted() {
        val raw = TuningParamsCodec.buildSavedTuningParams(
            engineTuningState(
                config = JukeboxConfig(currentThreshold = 0, justBackwards = true),
                computedThreshold = 31
            )
        )

        assertEquals("jb=1", raw)
    }

    @Test
    fun aChosenThresholdIsPersistedEvenWhereItMatchesTheComputedValue() {
        // The engine holds a threshold rather than the auto sentinel, so it was chosen, and that
        // survives being numerically equal to what the track would have computed on its own.
        val raw = TuningParamsCodec.buildSavedTuningParams(
            engineTuningState(
                config = JukeboxConfig(currentThreshold = 31, justBackwards = true),
                computedThreshold = 31
            )
        )

        assertEquals("jb=1&thresh=31", raw)
    }

    @Test
    fun theEngineAutoSentinelPersistsAsNoThresholdAtAll() {
        val raw = TuningParamsCodec.buildSavedTuningParams(
            engineTuningState(
                config = JukeboxConfig(justBackwards = true),
                computedThreshold = 31
            )
        )

        assertEquals("jb=1", raw)
    }

    @Test
    fun disabledLongBranchesEmitNoBranchLength() {
        val raw = TuningParamsCodec.buildSavedTuningParams(
            engineTuningState(
                config = JukeboxConfig(justLongBranches = false, minLongBranchPercent = 20),
                computedThreshold = 31
            )
        )

        assertNull(raw)
    }

    @Test
    fun mirroringTheEngineCopiesItsAnchorAndKeepsDeletedBranchesAndHighlight() {
        val custom = AnchorParam.Custom(CustomAnchor(289, 23))
        val current = TuningState(highlightAnchorBranch = true, deletedEdgeIds = listOf(4, 9))

        val mirrored = tuningMirroringEngine(
            current,
            engineTuningState(config = JukeboxConfig(), computedThreshold = 31, anchor = custom)
        )

        assertEquals(custom, mirrored.anchor)
        assertEquals(31, mirrored.computedThreshold)
        assertEquals(listOf(4, 9), mirrored.deletedEdgeIds)
        assertTrue(mirrored.highlightAnchorBranch)
    }

    @Test
    fun mirroringAResetEngineClearsTheAnchorSoTheSavedOneReadsAsChanged() {
        val saved = "ab=289-23"
        val loaded = tuningMirroringEngine(
            TuningState(),
            engineTuningState(
                config = JukeboxConfig(),
                computedThreshold = null,
                anchor = AnchorParam.Custom(CustomAnchor(289, 23))
            )
        )
        val reset = tuningMirroringEngine(
            loaded,
            engineTuningState(config = JukeboxConfig(), computedThreshold = null)
        )

        assertNull(reset.anchor)
        assertTrue(
            TuningParamsCodec.savedTuningParamsEquivalent(
                TuningParamsCodec.buildSavedTuningParams(loaded),
                saved
            )
        )
        assertFalse(
            TuningParamsCodec.savedTuningParamsEquivalent(
                TuningParamsCodec.buildSavedTuningParams(reset),
                saved
            )
        )
    }
}
