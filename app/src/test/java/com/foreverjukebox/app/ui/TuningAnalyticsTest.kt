package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `control` strings are asserted as literals on purpose: they are the wire contract
 * shared with the web app's GA4 dictionary, so a rename must fail here rather than follow
 * a shared constant into production.
 */
class TuningAnalyticsTest {

    @Test
    fun unchangedTuningReportsNoControls() {
        val tuning = TuningState()
        assertEquals(emptyList<String>(), analyticsChangedTuneControls(tuning, tuning))
    }

    @Test
    fun eachControlReportsOnlyItsOwnName() {
        val previous = TuningState()
        assertEquals(
            listOf("threshold"),
            analyticsChangedTuneControls(previous, previous.copy(threshold = 5))
        )
        assertEquals(
            listOf("min_branch_length"),
            analyticsChangedTuneControls(previous, previous.copy(minJumpDistancePercent = 20))
        )
        assertEquals(
            listOf("just_backwards"),
            analyticsChangedTuneControls(previous, previous.copy(justBackwards = true))
        )
        assertEquals(
            listOf("sequential"),
            analyticsChangedTuneControls(previous, previous.copy(removeSequential = true))
        )
        assertEquals(
            listOf("anchor_highlight"),
            analyticsChangedTuneControls(previous, previous.copy(highlightAnchorBranch = true))
        )
    }

    @Test
    fun probabilityFieldsCollapseIntoOneBranchProbabilityControl() {
        val previous = TuningState()
        assertEquals(
            listOf("branch_probability"),
            analyticsChangedTuneControls(previous, previous.copy(minProb = 25))
        )
        assertEquals(
            listOf("branch_probability"),
            analyticsChangedTuneControls(previous, previous.copy(maxProb = 60))
        )
        assertEquals(
            listOf("branch_probability"),
            analyticsChangedTuneControls(previous, previous.copy(ramp = 30))
        )
        assertEquals(
            listOf("branch_probability"),
            analyticsChangedTuneControls(
                previous,
                previous.copy(minProb = 25, maxProb = 60, ramp = 30)
            )
        )
    }

    @Test
    fun multipleChangedControlsReportInDialogOrder() {
        val previous = TuningState()
        val next = previous.copy(
            threshold = 7,
            minJumpDistancePercent = 30,
            maxProb = 70,
            justBackwards = true,
            removeSequential = true,
            highlightAnchorBranch = true
        )
        assertEquals(
            listOf(
                "threshold",
                "min_branch_length",
                "branch_probability",
                "just_backwards",
                "sequential",
                "anchor_highlight"
            ),
            analyticsChangedTuneControls(previous, next)
        )
    }

    @Test
    fun engineDerivedFieldsAreNotUserControls() {
        val previous = TuningState()
        val next = previous.copy(
            computedThreshold = 12,
            deletedEdgeIds = listOf(1, 2, 3),
            anchorBranchId = 9
        )
        assertEquals(emptyList<String>(), analyticsChangedTuneControls(previous, next))
    }

    @Test
    fun appliedTuningNormalizesDialogFractionsAndKeepsEngineFields() {
        val previous = TuningState(
            computedThreshold = 12,
            deletedEdgeIds = listOf(4),
            anchorBranchId = 2
        )
        val next = previous.withAppliedTuning(
            threshold = 1,
            minProb = 0.25,
            maxProb = 0.6,
            ramp = 0.06,
            highlightAnchorBranch = true,
            justBackwards = true,
            minJumpDistancePercent = 20,
            removeSequentialBranches = true,
            randomBranchDeltaPercentScale = 500.0
        )

        assertEquals(2, next.threshold)
        assertEquals(25, next.minProb)
        assertEquals(60, next.maxProb)
        assertEquals(30, next.ramp)
        assertEquals(12, next.computedThreshold)
        assertEquals(listOf(4), next.deletedEdgeIds)
        assertEquals(2, next.anchorBranchId)
    }

    @Test
    fun dialogRoundTripOfUntouchedValuesReportsNoControls() {
        // The dialog seeds from TuningState percents and sends back fractions, so an
        // Apply with nothing touched must diff to zero controls.
        val previous = TuningState(threshold = 6, minProb = 18, maxProb = 50, ramp = 10)
        val next = previous.withAppliedTuning(
            threshold = previous.threshold,
            minProb = previous.minProb / 100.0,
            maxProb = previous.maxProb / 100.0,
            ramp = previous.ramp / 500.0,
            highlightAnchorBranch = previous.highlightAnchorBranch,
            justBackwards = previous.justBackwards,
            minJumpDistancePercent = previous.minJumpDistancePercent,
            removeSequentialBranches = previous.removeSequential,
            randomBranchDeltaPercentScale = 500.0
        )

        assertEquals(emptyList<String>(), analyticsChangedTuneControls(previous, next))
    }

    @Test
    fun intensityAccompaniesOnlyModesThisBuildKnowsHaveTheSlider() {
        assertEquals(130, analyticsAudioModeIntensity("nightcore", 130))
        assertNull(analyticsAudioModeIntensity("lofi", 130))
        // A receiver-only mode is still reported by wire value, but carries no intensity:
        // the sender has no slider for it, so any value here would be a stale local one.
        assertNull(analyticsAudioModeIntensity("swing", 130))
        assertNull(analyticsAudioModeIntensity("", 130))
    }
}
