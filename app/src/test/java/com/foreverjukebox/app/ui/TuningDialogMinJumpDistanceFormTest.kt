package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TuningDialogMinJumpDistanceFormTest {

    @Test
    fun openedFormKeepsEveryStop() {
        for (percent in MIN_JUMP_DISTANCE_OPTIONS) {
            assertEquals(percent, openedForm(TuningState(minJumpDistancePercent = percent)).minJumpDistancePercent)
        }
    }

    @Test
    fun untouchedDialogMatchesTheOpenedFormAtEveryStop() {
        for (percent in MIN_JUMP_DISTANCE_OPTIONS) {
            val tuning = TuningState(minJumpDistancePercent = percent, computedThreshold = 31)
            assertEquals(openedForm(tuning), untouchedForm(tuning))
        }
    }

    @Test
    fun untouchedDialogOnAnUntunedTrackAppliesAutoAndAnyDistance() {
        val tuning = TuningState(computedThreshold = 31)
        val form = untouchedForm(tuning)

        assertNull(form.threshold)
        assertEquals(0, form.minJumpDistancePercent)
        val applied = tuning.withAppliedTuning(
            threshold = form.threshold,
            minProb = form.minProb / 100.0,
            maxProb = form.maxProb / 100.0,
            ramp = form.ramp / 500.0,
            highlightAnchorBranch = tuning.highlightAnchorBranch,
            justBackwards = form.justBackwards,
            minJumpDistancePercent = form.minJumpDistancePercent,
            removeSequentialBranches = form.removeSequential,
            randomBranchDeltaPercentScale = 500.0
        )
        assertEquals(tuning, applied)
        assertNull(TuningParamsCodec.buildSavedTuningParams(applied))
    }

    @Test
    fun sliderValueReadsAsTheNearestStop() {
        assertEquals(0, minJumpDistancePercentForSliderValue(0f))
        assertEquals(0, minJumpDistancePercentForSliderValue(0.4f))
        assertEquals(5, minJumpDistancePercentForSliderValue(0.9999f))
        assertEquals(5, minJumpDistancePercentForSliderValue(1.0001f))
        assertEquals(10, minJumpDistancePercentForSliderValue(1.9999f))
        assertEquals(30, minJumpDistancePercentForSliderValue(4f))
    }

    @Test
    fun sliderValueOutsideTheRangeClampsToTheEnds() {
        assertEquals(0, minJumpDistancePercentForSliderValue(-1f))
        assertEquals(30, minJumpDistancePercentForSliderValue(7f))
    }

    @Test
    fun sliderStopsRoundTripThroughTheirIndex() {
        for (percent in MIN_JUMP_DISTANCE_OPTIONS) {
            val index = minJumpDistanceIndexForPercent(percent)
            assertEquals(percent, minJumpDistancePercentForSliderValue(index.toFloat()))
        }
    }

    private fun openedForm(tuning: TuningState): BranchTuningForm = openedBranchTuningForm(
        initialThreshold = tuning.threshold,
        initialMinProb = tuning.minProb,
        initialMaxProb = tuning.maxProb,
        initialRamp = tuning.ramp,
        initialJustBackwards = tuning.justBackwards,
        initialMinJumpDistancePercent = tuning.minJumpDistancePercent,
        initialRemoveSequential = tuning.removeSequential
    )

    // The controls exactly as the dialog seeds them from tuning, with nothing moved.
    private fun untouchedForm(tuning: TuningState): BranchTuningForm = editedBranchTuningForm(
        threshold = (tuning.threshold ?: tuning.computedThreshold ?: 0).toFloat(),
        thresholdChosen = tuning.threshold != null,
        thresholdMoved = false,
        computedThreshold = tuning.computedThreshold,
        probRange = tuning.minProb.toFloat()..tuning.maxProb.toFloat(),
        ramp = tuning.ramp.toFloat(),
        justBackwards = tuning.justBackwards,
        minJumpIndex = minJumpDistanceIndexForPercent(tuning.minJumpDistancePercent).toFloat(),
        removeSequential = tuning.removeSequential
    )
}
