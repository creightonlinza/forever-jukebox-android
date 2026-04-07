package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TuningCoordinatorCastPolicyTest {

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
    fun buildCastTuningUpdateUsesFullPayloadWhenThresholdChanges() {
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
            threshold = 9,
            minProb = 0.10,
            maxProb = 0.40,
            ramp = 0.05,
            highlightAnchorBranch = false,
            justBackwards = true,
            justLongBranches = false,
            removeSequentialBranches = true,
            randomBranchDeltaPercentScale = 500.0
        )

        assertEquals(TuningParamsCodec.buildFromTuningState(update.nextTuning), update.castParams)
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
}
