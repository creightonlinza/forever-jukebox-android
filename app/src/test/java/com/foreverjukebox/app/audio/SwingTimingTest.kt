package com.foreverjukebox.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the web app's swingTiming and swingRenderer geometry cases so both platforms agree. */
class SwingTimingTest {

    @Test
    fun defaultSwingAmountMatchesWeb() {
        assertEquals(0.33, DEFAULT_SWING_AMOUNT, 0.0)
    }

    @Test
    fun clampsSwingAmountDefensively() {
        assertEquals(0.9, clampSwingAmount(2.0), 0.0)
        assertEquals(-0.9, clampSwingAmount(-2.0), 0.0)
        assertEquals(DEFAULT_SWING_AMOUNT, clampSwingAmount(Double.NaN), 0.0)
    }

    @Test
    fun splitsOneSecondBeatIntoDefaultSwingOutputDurations() {
        val (first, second) = swingSegmentsForBeat(SwingBeat(start = 10.0, duration = 1.0))

        assertEquals(0.665, first.outputDuration, 1e-12)
        assertEquals(0.335, second.outputDuration, 1e-12)
        assertEquals(1.0, first.outputDuration + second.outputDuration, 1e-12)
    }

    @Test
    fun playbackRatesAreInputDurationOverOutputDuration() {
        val (first, second) = swingSegmentsForBeat(SwingBeat(start = 0.0, duration = 1.0))

        assertEquals(first.inputDuration / first.outputDuration, first.playbackRate, 1e-12)
        assertEquals(second.inputDuration / second.outputDuration, second.playbackRate, 1e-12)
    }

    @Test
    fun segmentInputStartsAndDurationsLineUp() {
        val (first, second) = swingSegmentsForBeat(SwingBeat(start = 4.0, duration = 2.0))

        assertEquals(4.0, first.inputStart, 0.0)
        assertEquals(1.0, first.inputDuration, 0.0)
        assertEquals(5.0, second.inputStart, 0.0)
        assertEquals(1.0, second.inputDuration, 0.0)
    }

    @Test
    fun splitsEachBeatIntoFixedSwingTargetFrameCounts() {
        val segments = swingFrameSegments(
            beats = listOf(SwingBeat(start = 0.0, duration = 1.0)),
            sampleRate = 100,
            sourceFrames = 100
        )

        assertEquals(
            listOf(
                SwingFrameSegment(
                    inputStartFrame = 0,
                    inputFrameCount = 50,
                    outputStartFrame = 0,
                    outputFrameCount = 67
                ),
                SwingFrameSegment(
                    inputStartFrame = 50,
                    inputFrameCount = 50,
                    outputStartFrame = 67,
                    outputFrameCount = 33
                )
            ),
            segments
        )
    }

    @Test
    fun oddBeatFrameCountsGiveTheSecondHalfTheExtraInputFrame() {
        val (first, second) = swingFrameSegmentsForBeat(
            beat = SwingBeat(start = 0.2, duration = 0.5),
            sampleRate = 10,
            sourceFrames = 10
        )!!

        assertEquals(2, first.inputStartFrame)
        assertEquals(2, first.inputFrameCount)
        assertEquals(3, second.inputFrameCount)
        assertEquals(5, first.outputFrameCount + second.outputFrameCount)
        assertEquals(first.outputStartFrame + first.outputFrameCount, second.outputStartFrame)
    }

    @Test
    fun outputHalvesNeverCollapseAtExtremeSwing() {
        val (first, second) = swingFrameSegmentsForBeat(
            beat = SwingBeat(start = 0.0, duration = 0.2),
            sampleRate = 10,
            sourceFrames = 10,
            swingAmount = 0.9
        )!!

        assertEquals(1, first.outputFrameCount)
        assertEquals(1, second.outputFrameCount)
    }

    @Test
    fun skipsBeatsWithBadTimingOrTooShortToSplit() {
        assertNull(swingFrameSegmentsForBeat(SwingBeat(Double.NaN, 0.2), sampleRate = 10, sourceFrames = 100))
        assertNull(swingFrameSegmentsForBeat(SwingBeat(0.0, Double.NaN), sampleRate = 10, sourceFrames = 100))
        assertNull(swingFrameSegmentsForBeat(SwingBeat(0.0, -0.5), sampleRate = 10, sourceFrames = 100))
        assertNull(swingFrameSegmentsForBeat(SwingBeat(0.0, 0.0), sampleRate = 10, sourceFrames = 100))
        assertNull(swingFrameSegmentsForBeat(SwingBeat(1.0, 0.1), sampleRate = 10, sourceFrames = 100))
        assertNull(swingFrameSegmentsForBeat(SwingBeat(9.95, 1.0), sampleRate = 10, sourceFrames = 100))
    }

    @Test
    fun keepsFullSwingTimingWhenABeatRunsPastTheEnd() {
        // 0.8-1.1 s on a 1 s source: the first half (15 -> 20 frames) ends
        // exactly at the end, so the second half is pushed out entirely.
        val segments = swingFrameSegments(
            beats = listOf(SwingBeat(start = 0.8, duration = 0.3)),
            sampleRate = 100,
            sourceFrames = 100
        )

        assertEquals(listOf(15, 0), segments.map { it.inputFrameCount })
        assertEquals(listOf(20, 0), segments.map { it.outputFrameCount })
        assertEquals(listOf(80, 100), segments.map { it.outputStartFrame })
    }

    @Test
    fun clipsAStretchedHalfAtTheEndAtItsOwnRatio() {
        // 0.85-1.1 s: the first half stretches 12 -> 17 frames from frame 85,
        // so only 15 output frames fit; 11 input frames keep that ratio.
        val segments = swingFrameSegments(
            beats = listOf(SwingBeat(start = 0.85, duration = 0.25)),
            sampleRate = 100,
            sourceFrames = 100
        )

        assertEquals(listOf(11, 0), segments.map { it.inputFrameCount })
        assertEquals(listOf(15, 0), segments.map { it.outputFrameCount })
    }

    @Test
    fun pinsASecondHalfThatWouldStartPastTheEnd() {
        val segments = swingFrameSegments(
            beats = listOf(SwingBeat(start = 0.9, duration = 0.4)),
            sampleRate = 100,
            sourceFrames = 100
        )

        assertEquals(listOf(7, 0), segments.map { it.inputFrameCount })
        assertEquals(listOf(10, 0), segments.map { it.outputFrameCount })
        assertEquals(listOf(90, 100), segments.map { it.outputStartFrame })
        assertEquals(listOf(90, 100), segments.map { it.inputStartFrame })
    }

    @Test
    fun clipsBySpaceForOutputSoNegativeSwingReadsSilencePastTheEnd() {
        // Negative swing slows the second half: its output reaches the end
        // before its input does, so the input runs past the source end.
        val (first, second) = swingFrameSegmentsForBeat(
            SwingBeat(start = 0.8, duration = 0.3),
            sampleRate = 100,
            sourceFrames = 100,
            swingAmount = -0.5
        )!!

        assertEquals(100, second.outputStartFrame + second.outputFrameCount)
        assertEquals(first.inputStartFrame + first.inputFrameCount, second.inputStartFrame)
        assertTrue(second.inputStartFrame + second.inputFrameCount > 100)
    }

    @Test
    fun skipsABeatThatStartsBeforeTheSource() {
        assertNull(swingFrameSegmentsForBeat(SwingBeat(-0.1, 0.3), sampleRate = 100, sourceFrames = 100))
    }
}
