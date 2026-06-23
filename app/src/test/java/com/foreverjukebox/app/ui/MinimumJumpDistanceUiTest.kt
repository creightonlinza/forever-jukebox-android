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

    @Test
    fun labelsUseStrictGreaterThanDisplay() {
        assertEquals(
            "Minimum Jump Distance: Any distance",
            minimumJumpDistanceLabel(0)
        )
        val label = minimumJumpDistanceLabel(30)
        assertEquals("Minimum Jump Distance: >30% of track", label)
        assertTrue(label.contains(">"))
        assertFalse(label.contains("≥"))
    }
}
