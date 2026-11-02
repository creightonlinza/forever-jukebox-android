package com.foreverjukebox.app.visualization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatTilesTest {

    private val geometry = BeatTileGeometry(width = 1000f, height = 1000f, count = 100)

    @Test
    fun scaleHalvesAtNineTwentyOneAndFortyFivePlays() {
        assertEquals(1f, tileScale(0), 0f)
        assertEquals(1f, tileScale(8), 0f)
        assertEquals(0.5f, tileScale(9), 0f)
        assertEquals(0.5f, tileScale(20), 0f)
        assertEquals(0.25f, tileScale(21), 0f)
        assertEquals(0.25f, tileScale(44), 0f)
        assertEquals(0.125f, tileScale(45), 0f)
    }

    @Test
    fun levelGrowsWithVisitsAndNeverPassesFullHeight() {
        assertEquals(0f, tileLevel(0, 1f), 0f)
        assertEquals(5f / 12f, tileLevel(1, 1f), 1e-6f)
        assertEquals(1f, tileLevel(8, 1f), 1e-6f)
        assertEquals(13f / 24f, tileLevel(9, tileScale(9)), 1e-6f)
        for (visits in 1..200) {
            assertTrue(tileLevel(visits, tileScale(visits)) <= 1f)
        }
    }

    @Test
    fun widthFollowsTheBeatSlotUpToTheCap() {
        val sparse = BeatTileGeometry(1000f, 1000f, count = 4)
        assertEquals(30f, sparse.tileWidth(), 1e-3f)

        val dense = BeatTileGeometry(1000f, 1000f, count = 1000)
        assertEquals(2.513f, dense.tileWidth(), 1e-3f)
        assertEquals(3f, dense.tileWidth(minWidth = 3f), 1e-3f)
    }

    @Test
    fun unplayedThinTileSpansTheDotEitherSideOfTheRing() {
        val ends = geometry.tileLineEnds(height = 0f, strokeWidth = 1f, dotRadius = 2f)
        // Caps add half the stroke at each end, so the drawn span is 398..402.
        assertEquals(398.5f, ends.start, 1e-3f)
        assertEquals(401.5f, ends.end, 1e-3f)
    }

    @Test
    fun lineKeepsNonZeroLengthWhenTheCapsAreWiderThanTheDot() {
        val ends = geometry.tileLineEnds(height = 0f, strokeWidth = 30f, dotRadius = 2f)
        assertEquals(400f, ends.start, 1e-3f)
        assertTrue(ends.end > ends.start)
    }

    @Test
    fun playedTileEndsAtItsHeightMinusTheCap() {
        val ends = geometry.tileLineEnds(height = 100f, strokeWidth = 10f, dotRadius = 2f)
        assertEquals(495f, ends.end, 1e-3f)
    }

    @Test
    fun hitTestingMapsAnglesToBeats() {
        assertEquals(0, geometry.tileIndexAt(500f, 50f, MARGIN))
        assertEquals(25, geometry.tileIndexAt(950f, 500f, MARGIN))
        assertEquals(50, geometry.tileIndexAt(500f, 950f, MARGIN))
        assertEquals(75, geometry.tileIndexAt(50f, 500f, MARGIN))
    }

    @Test
    fun hitTestingWrapsAroundTheTop() {
        assertEquals(0, geometry.tileIndexAt(499f, 50f, MARGIN))
        assertEquals(99, geometry.tileIndexAt(470f, 60f, MARGIN))
    }

    @Test
    fun hitTestingStaysInsideTheBand() {
        // Above the center: the ring is at y = 100 and full tile height ends at y = 0.
        assertEquals(0, geometry.tileIndexAt(500f, 0f, MARGIN))
        assertEquals(0, geometry.tileIndexAt(500f, 108f, MARGIN))
        assertNull(geometry.tileIndexAt(500f, 109f, MARGIN))
        assertNull(geometry.tileIndexAt(500f, 500f, MARGIN))

        val wide = BeatTileGeometry(2000f, 1000f, count = 100)
        assertNull(wide.tileIndexAt(1000f, -1f, MARGIN))
    }

    private companion object {
        const val MARGIN = 8f
    }
}
