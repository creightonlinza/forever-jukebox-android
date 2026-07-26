package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimumJumpDistanceUiTest {

    @Test
    fun sliderIndicesMapToSupportedPercentages() {
        assertEquals(
            listOf(0, 5, 10, 20, 30),
            MIN_JUMP_DISTANCE_OPTIONS.indices.map(::minJumpDistancePercentForIndex)
        )
    }

    @Test
    fun sliderInitialIndexFallsBackToAnyDistance() {
        assertEquals(0, minJumpDistanceIndexForPercent(0))
        assertEquals(3, minJumpDistanceIndexForPercent(20))
        assertEquals(0, minJumpDistanceIndexForPercent(15))
    }

    // The `tune` analytics diff compares minJumpDistancePercent with a plain !=, which is only
    // safe while every stored percent is a slider option: a stored non-snap value would survive
    // an untouched Apply as a phantom min_branch_length change.
    @Test
    fun sliderOptionsCoverEveryStorableBranchLength() {
        assertEquals(ALLOWED_BRANCH_LENGTHS + 0, MIN_JUMP_DISTANCE_OPTIONS.toSet())
    }

    @Test
    fun labelsUseStrictGreaterThanDisplay() {
        assertEquals(
            "Any distance",
            minimumJumpDistanceLabel(0)
        )
        val label = minimumJumpDistanceLabel(30)
        assertEquals(">30% of track", label)
        assertTrue(label.contains(">"))
        assertFalse(label.contains("≥"))
    }
}
