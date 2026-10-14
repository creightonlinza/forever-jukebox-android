package com.foreverjukebox.app.ui

import com.foreverjukebox.app.engine.MIN_THRESHOLD
import com.foreverjukebox.app.engine.VisualizationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningResetVisibilityTest {

    @Test
    fun defaultTuningHasNothingToReset() {
        assertFalse(
            hasNonDefaultBranchTuning(
                TuningState(),
                hasDeletedBranches = false,
                hasNonDefaultAnchor = false
            )
        )
    }

    @Test
    fun eachNonDefaultFieldCounts() {
        val cases = listOf(
            TuningState(threshold = 30),
            TuningState(minProb = 20),
            TuningState(maxProb = 60),
            TuningState(ramp = 12),
            TuningState(justBackwards = true),
            TuningState(minJumpDistancePercent = 10),
            TuningState(removeSequential = true)
        )
        cases.forEach { tuning ->
            assertTrue(
                tuning.toString(),
                hasNonDefaultBranchTuning(tuning, hasDeletedBranches = false, hasNonDefaultAnchor = false)
            )
        }
        assertTrue(hasNonDefaultBranchTuning(TuningState(), hasDeletedBranches = true, hasNonDefaultAnchor = false))
        assertTrue(hasNonDefaultBranchTuning(TuningState(), hasDeletedBranches = false, hasNonDefaultAnchor = true))
    }

    @Test
    fun highlightAnchorBranchIsStickyAndNeverCounts() {
        assertFalse(
            hasNonDefaultBranchTuning(
                TuningState(highlightAnchorBranch = true),
                hasDeletedBranches = false,
                hasNonDefaultAnchor = false
            )
        )
    }

    @Test
    fun computedThresholdAloneDoesNotCount() {
        assertFalse(
            hasNonDefaultBranchTuning(
                TuningState(computedThreshold = 31),
                hasDeletedBranches = false,
                hasNonDefaultAnchor = false
            )
        )
    }

    @Test
    fun localPlaybackReadsDeletedBranchesFromTheEngine() {
        val state = localState()

        assertFalse(hasBranchTuningToReset(state) { false })
        assertTrue(hasBranchTuningToReset(state) { true })
    }

    @Test
    fun localPlaybackCountsOnlyANonDefaultUserAnchor() {
        // The engine's visualization data leaves out an anchor on the default edge.
        assertFalse(hasBranchTuningToReset(localState(userAnchorEdgeId = null)) { false })
        assertTrue(hasBranchTuningToReset(localState(userAnchorEdgeId = 7)) { false })
    }

    @Test
    fun localPlaybackIgnoresTheCastMirrorFields() {
        val state = localState().copy(
            tuning = TuningState(deletedEdgeIds = listOf(3), anchorBranchId = 7)
        )

        assertFalse(hasBranchTuningToReset(state) { false })
    }

    @Test
    fun castingReadsTheReceiverMirroredState() {
        assertFalse(hasBranchTuningToReset(castState(TuningState())) { true })
        assertTrue(hasBranchTuningToReset(castState(TuningState(deletedEdgeIds = listOf(3)))) { false })
        assertTrue(hasBranchTuningToReset(castState(TuningState(anchorBranchId = 7))) { false })
        assertTrue(hasBranchTuningToReset(castState(TuningState(justBackwards = true))) { false })
    }

    @Test
    fun castingIgnoresTheLocalEngineAndVisualization() {
        val state = castState(TuningState()).let {
            it.copy(playback = it.playback.copy(vizData = vizData(userAnchorEdgeId = 7)))
        }
        var engineCalls = 0

        assertFalse(
            hasBranchTuningToReset(state) {
                engineCalls++
                true
            }
        )
        assertEquals(0, engineCalls)
    }

    @Test
    fun freshlyOpenedFormIsUnchanged() {
        val tuning = TuningState(computedThreshold = 31)

        assertEquals(openedFrom(tuning), Controls.asOpened(tuning).toForm(tuning))
    }

    @Test
    fun freshlyOpenedFormWithOffGridAppliedValuesIsUnchanged() {
        // A shared link can carry a min jump distance between the slider's stops, and the slider
        // floats can carry rounding noise; neither may read as an edit on open.
        val tuning = TuningState(
            threshold = 45,
            computedThreshold = 31,
            minProb = 60,
            maxProb = 40,
            ramp = 11,
            minJumpDistancePercent = 15
        )
        val controls = Controls.asOpened(tuning).copy(ramp = 10.999999f)

        assertEquals(openedFrom(tuning), controls.toForm(tuning))
    }

    @Test
    fun formLeftOnAutoAndDraggedToTheComputedValueIsUnchanged() {
        val tuning = TuningState(computedThreshold = 31)
        val controls = Controls.asOpened(tuning).moveThreshold(40f).moveThreshold(31f)

        assertEquals(openedFrom(tuning), controls.toForm(tuning))
    }

    @Test
    fun formLeftOnAutoAndDraggedElsewhereIsChanged() {
        val tuning = TuningState(computedThreshold = 31)
        val controls = Controls.asOpened(tuning).moveThreshold(40f)

        assertNotEquals(openedFrom(tuning), controls.toForm(tuning))
    }

    @Test
    fun pinnedThresholdIsUnchangedByAnUnrelatedEdit() {
        val tuning = TuningState(threshold = 31, computedThreshold = 31)
        val edited = Controls.asOpened(tuning).copy(justBackwards = true).toForm(tuning)
        val opened = openedFrom(tuning)

        assertEquals(opened.threshold, edited.threshold)
        assertNotEquals(opened, edited)
    }

    @Test
    fun movingAControlBackToItsOpeningValueIsUnchanged() {
        val tuning = TuningState(computedThreshold = 31)
        val controls = Controls.asOpened(tuning).copy(ramp = 40f).copy(ramp = 10f)

        assertEquals(openedFrom(tuning), controls.toForm(tuning))
    }

    @Test
    fun audioResetHiddenWhenAppliedAndFormAreOff() {
        assertFalse(audioModeResetVisible(appliedWireValue = "off", formWireValue = "off"))
        assertFalse(audioModeResetVisible(appliedWireValue = "", formWireValue = " off "))
        assertFalse(audioModeResetVisible(appliedWireValue = "OFF", formWireValue = "Off"))
    }

    @Test
    fun audioResetShownWhenAppliedOrFormIsOn() {
        assertTrue(audioModeResetVisible(appliedWireValue = "daycore", formWireValue = "off"))
        assertTrue(audioModeResetVisible(appliedWireValue = "off", formWireValue = "lofi"))
    }

    private fun localState(userAnchorEdgeId: Int? = null): UiState {
        return UiState(
            playback = PlaybackState(
                playMode = PlaybackMode.Jukebox,
                isCasting = false,
                vizData = vizData(userAnchorEdgeId)
            )
        )
    }

    private fun castState(tuning: TuningState): UiState {
        return UiState(
            playback = PlaybackState(playMode = PlaybackMode.Jukebox, isCasting = true),
            tuning = tuning
        )
    }

    private fun vizData(userAnchorEdgeId: Int?): VisualizationData {
        return VisualizationData(
            beats = emptyList(),
            edges = mutableListOf(),
            anchorEdgeId = 1,
            userAnchorEdgeId = userAnchorEdgeId
        )
    }

    private fun openedFrom(tuning: TuningState): BranchTuningForm {
        return openedBranchTuningForm(
            initialThreshold = tuning.threshold,
            initialMinProb = tuning.minProb,
            initialMaxProb = tuning.maxProb,
            initialRamp = tuning.ramp,
            initialJustBackwards = tuning.justBackwards,
            initialMinJumpDistancePercent = tuning.minJumpDistancePercent,
            initialRemoveSequential = tuning.removeSequential
        )
    }

    // Mirrors the dialog's control state, initialized the way TuningDialog initializes it.
    private data class Controls(
        val threshold: Float,
        val thresholdChosen: Boolean,
        val thresholdMoved: Boolean,
        val probRange: ClosedFloatingPointRange<Float>,
        val ramp: Float,
        val justBackwards: Boolean,
        val minJumpIndex: Float,
        val removeSequential: Boolean
    ) {
        fun moveThreshold(value: Float): Controls {
            return copy(threshold = value, thresholdChosen = true, thresholdMoved = true)
        }

        fun toForm(tuning: TuningState): BranchTuningForm {
            return editedBranchTuningForm(
                threshold = threshold,
                thresholdChosen = thresholdChosen,
                thresholdMoved = thresholdMoved,
                computedThreshold = tuning.computedThreshold,
                probRange = probRange,
                ramp = ramp,
                justBackwards = justBackwards,
                minJumpIndex = minJumpIndex,
                removeSequential = removeSequential
            )
        }

        companion object {
            fun asOpened(tuning: TuningState): Controls {
                val minProb = tuning.minProb.toFloat()
                val maxProb = tuning.maxProb.toFloat()
                return Controls(
                    threshold = (tuning.threshold ?: tuning.computedThreshold ?: MIN_THRESHOLD).toFloat(),
                    thresholdChosen = tuning.threshold != null,
                    thresholdMoved = false,
                    probRange = minProb.coerceAtMost(maxProb)..maxProb.coerceAtLeast(minProb),
                    ramp = tuning.ramp.toFloat(),
                    justBackwards = tuning.justBackwards,
                    minJumpIndex = minJumpDistanceIndexForPercent(tuning.minJumpDistancePercent).toFloat(),
                    removeSequential = tuning.removeSequential
                )
            }
        }
    }
}
