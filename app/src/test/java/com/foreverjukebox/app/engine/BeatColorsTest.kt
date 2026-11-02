package com.foreverjukebox.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class BeatColorsTest {

    @Test
    fun mapsNormalizedTimbreToBlueRedAndGreen() {
        val low = segment(-10.0, -20.0, -30.0)
        val high = segment(10.0, 20.0, 30.0)
        val mixed = segment(10.0, -20.0, -30.0)

        val colors = computeBeatColors(
            listOf(listOf(low), listOf(high), listOf(mixed)),
            listOf(low, high, mixed)
        )

        assertEquals(listOf("#000000", "#ffffff", "#0000ff"), colors)
    }

    @Test
    fun usesNeutralColorForBeatsWithoutSegments() {
        assertEquals(listOf("#333333"), computeBeatColors(listOf(emptyList()), emptyList()))
    }

    private fun segment(t1: Double, t2: Double, t3: Double): Segment {
        return Segment(
            start = 0.0,
            duration = 1.0,
            confidence = 1.0,
            loudnessStart = 0.0,
            loudnessMax = 0.0,
            loudnessMaxTime = 0.0,
            pitches = emptyList(),
            timbre = listOf(0.0, t1, t2, t3),
            which = 0
        )
    }
}
