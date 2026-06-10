package com.foreverjukebox.app.ui

import com.foreverjukebox.app.engine.JukeboxConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class TuningCoordinatorCastPolicyTest {

    @Test
    fun buildCastTuningResetParamsResetsGraphAndAudioButOmitsHighlight() {
        val params = buildCastTuningResetParams(
            defaultConfig = JukeboxConfig(),
            randomBranchDeltaPercentScale = 500.0,
            resetThreshold = 34
        )

        assertEquals("jb=0&lg=0&sq=1&thresh=34&bp=18,50,10&d=&am=off", params)
    }

    @Test
    fun buildCastTuningUpdateUsesHighlightOnlyPayloadWhenOnlyHighlightChanges() {
        val current = TuningState(
            threshold = 22,
            minProb = 10,
            maxProb = 40,
            ramp = 25,
            highlightAnchorBranch = false,
            justBackwards = true,
            justLong = false,
            removeSequential = true
        )

        val update = buildCastTuningUpdate(
            currentTuning = current,
            threshold = 22,
            minProb = 0.10,
            maxProb = 0.40,
            ramp = 0.05,
            highlightAnchorBranch = true,
            justBackwards = true,
            justLongBranches = false,
            removeSequentialBranches = true,
            randomBranchDeltaPercentScale = 500.0
        )

        assertEquals(current.copy(highlightAnchorBranch = true), update.nextTuning)
        assertEquals("ah=1", update.castParams)
    }

    @Test
    fun buildCastTuningUpdateUsesAudioOnlyPayloadWhenOnlyAudioModeChanges() {
        val current = TuningState(
            threshold = 22,
            minProb = 10,
            maxProb = 40,
            ramp = 25,
            highlightAnchorBranch = false,
            justBackwards = true,
            justLong = false,
            removeSequential = true
        )

        val enabled = buildCastTuningUpdate(
            currentTuning = current,
            currentAudioMode = JukeboxAudioMode.Off,
            threshold = 22,
            minProb = 0.10,
            maxProb = 0.40,
            ramp = 0.05,
            highlightAnchorBranch = false,
            justBackwards = true,
            justLongBranches = false,
            removeSequentialBranches = true,
            randomBranchDeltaPercentScale = 500.0,
            audioMode = JukeboxAudioMode.Lofi
        )
        val disabled = buildCastTuningUpdate(
            currentTuning = current,
            currentAudioMode = JukeboxAudioMode.Lofi,
            threshold = 22,
            minProb = 0.10,
            maxProb = 0.40,
            ramp = 0.05,
            highlightAnchorBranch = false,
            justBackwards = true,
            justLongBranches = false,
            removeSequentialBranches = true,
            randomBranchDeltaPercentScale = 500.0,
            audioMode = JukeboxAudioMode.Off
        )

        assertEquals("am=lofi", enabled.castParams)
        assertEquals("am=off", disabled.castParams)
    }

    @Test
    fun buildCastTuningUpdateEmitsLatestAudioModes() {
        val current = TuningState(
            threshold = 22,
            minProb = 10,
            maxProb = 40,
            ramp = 25,
            highlightAnchorBranch = false,
            justBackwards = true,
            justLong = false,
            removeSequential = true
        )

        val eightBit = buildAudioOnlyCastUpdate(current, JukeboxAudioMode.EightBit)
        val underwater = buildAudioOnlyCastUpdate(current, JukeboxAudioMode.Underwater)
        val cathedral = buildAudioOnlyCastUpdate(current, JukeboxAudioMode.Cathedral)
        val cowbell = buildAudioOnlyCastUpdate(current, JukeboxAudioMode.Cowbell)

        assertEquals("am=eight_bit", eightBit.castParams)
        assertEquals("am=underwater", underwater.castParams)
        assertEquals("am=cathedral", cathedral.castParams)
        assertEquals("am=cowbell", cowbell.castParams)
    }

    @Test
    fun buildCastTuningUpdateEmitsReceiverOnlyAudioModeWireValue() {
        val current = TuningState(
            threshold = 22,
            minProb = 10,
            maxProb = 40,
            ramp = 25,
            highlightAnchorBranch = false,
            justBackwards = true,
            justLong = false,
            removeSequential = true
        )

        val update = buildCastTuningUpdate(
            currentTuning = current,
            currentAudioMode = JukeboxAudioMode.Off,
            currentAudioModeWireValue = "off",
            threshold = 22,
            minProb = 0.10,
            maxProb = 0.40,
            ramp = 0.05,
            highlightAnchorBranch = false,
            justBackwards = true,
            justLongBranches = false,
            removeSequentialBranches = true,
            randomBranchDeltaPercentScale = 500.0,
            audioMode = JukeboxAudioMode.Off,
            audioModeWireValue = "future_mode"
        )

        assertEquals("am=future_mode", update.castParams)
    }

    @Test
    fun buildCastTuningUpdateUsesChangedThresholdOnly() {
        val current = TuningState(
            threshold = 22,
            minProb = 10,
            maxProb = 40,
            ramp = 25,
            highlightAnchorBranch = false,
            justBackwards = true,
            justLong = false,
            removeSequential = true
        )

        val update = buildCastTuningUpdate(
            currentTuning = current,
            currentAudioMode = JukeboxAudioMode.Lofi,
            threshold = 9,
            minProb = 0.10,
            maxProb = 0.40,
            ramp = 0.05,
            highlightAnchorBranch = false,
            justBackwards = true,
            justLongBranches = false,
            removeSequentialBranches = true,
            randomBranchDeltaPercentScale = 500.0,
            audioMode = JukeboxAudioMode.Lofi
        )

        assertEquals("thresh=9", update.castParams)
    }

    @Test
    fun buildCastTuningUpdateUsesOnlyChangedTuningAndAudioKeys() {
        val current = TuningState(
            threshold = 22,
            minProb = 10,
            maxProb = 40,
            ramp = 25,
            highlightAnchorBranch = false,
            justBackwards = true,
            justLong = false,
            removeSequential = true
        )

        val update = buildCastTuningUpdate(
            currentTuning = current,
            currentAudioMode = JukeboxAudioMode.Off,
            threshold = 22,
            minProb = 0.10,
            maxProb = 0.40,
            ramp = 0.10,
            highlightAnchorBranch = true,
            justBackwards = true,
            justLongBranches = false,
            removeSequentialBranches = true,
            randomBranchDeltaPercentScale = 500.0,
            audioMode = JukeboxAudioMode.Vaporwave
        )

        assertEquals("bp=10,40,50&ah=1&am=vaporwave", update.castParams)
    }

    @Test
    fun buildCastTuningUpdateClampsThresholdAndPercents() {
        val update = buildCastTuningUpdate(
            currentTuning = TuningState(),
            threshold = 1,
            minProb = -2.0,
            maxProb = 5.0,
            ramp = 1.0,
            highlightAnchorBranch = false,
            justBackwards = false,
            justLongBranches = false,
            removeSequentialBranches = false,
            randomBranchDeltaPercentScale = 500.0
        )

        assertEquals(2, update.nextTuning.threshold)
        assertEquals(0, update.nextTuning.minProb)
        assertEquals(100, update.nextTuning.maxProb)
        assertEquals(100, update.nextTuning.ramp)
    }

    private fun buildAudioOnlyCastUpdate(
        current: TuningState,
        audioMode: JukeboxAudioMode
    ): CastTuningUpdate {
        return buildCastTuningUpdate(
            currentTuning = current,
            currentAudioMode = JukeboxAudioMode.Off,
            threshold = current.threshold,
            minProb = current.minProb / 100.0,
            maxProb = current.maxProb / 100.0,
            ramp = current.ramp / 500.0,
            highlightAnchorBranch = current.highlightAnchorBranch,
            justBackwards = current.justBackwards,
            justLongBranches = current.justLong,
            removeSequentialBranches = current.removeSequential,
            randomBranchDeltaPercentScale = 500.0,
            audioMode = audioMode
        )
    }
}
