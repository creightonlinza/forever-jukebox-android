package com.foreverjukebox.app.wubmachine

import com.foreverjukebox.app.engine.Segment
import com.foreverjukebox.app.engine.TrackMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frame arithmetic mirrored from dubstepRenderer.test.ts (beat-grid mode). */
class WubRenderPlanTest {

    private val sampleRate = 1000
    // 8 bars at 140 BPM.
    private val bedFrames = 13714

    private fun makeAnalysis(): DubstepAnalysis {
        val beats = List(32) { i -> Quantum(i * 0.5, 0.5) }
        val pitches = MutableList(12) { 0.1 }
        pitches[0] = 1.0
        return DubstepAnalysis(
            sections = listOf(Quantum(0.0, 16.0)),
            beats = beats,
            segments = beats.mapIndexed { which, beat ->
                Segment(
                    start = beat.start + 0.125,
                    duration = 0.5,
                    confidence = 1.0,
                    loudnessStart = -60.0,
                    loudnessMax = -10.0,
                    loudnessMaxTime = 0.0,
                    pitches = pitches,
                    timbre = List(12) { 0.0 },
                    which = which
                )
            },
            track = TrackMeta(duration = 16.0, tempo = 120.0, timeSignature = 4.0)
        )
    }

    @Test
    fun partFramesAreEightBarsAtTheDubstepTempo() {
        assertEquals(bedFrames, wubPartFrames(sampleRate))
        assertEquals(Math.round(44100 * 32 * 60 / 140.0).toInt(), wubPartFrames(44100))
    }

    @Test
    fun stretchesEachSliceOntoTheGrid() {
        val plan = planDubstepRemix(makeAnalysis())
        val request = buildWubRenderRequest(plan, sampleRate, 16 * sampleRate)
        val beat = 60.0 / 140 * sampleRate

        val intro = request.parts[0]
        assertEquals(WubSliceFrames(0, 500, Math.round(beat).toInt()), intro.slices[0])
        // Half and quarter cuts of beats 8, 12 and 14.
        assertEquals(WubSliceFrames(4000, 4250, Math.round(beat / 2).toInt()), intro.slices[24])
        assertEquals(Math.round(beat / 4).toInt(), intro.slices[32].targetFrames)
        // One stretch per distinct slice: 16 intro beats plus the three cuts.
        assertEquals(16 + 3, intro.slices.toSet().size)
        assertEquals(16, request.parts[1].slices.toSet().size)
    }

    @Test
    fun dropAndBreakShareOneSliceGroup() {
        val plan = planDubstepRemix(makeAnalysis())
        val request = buildWubRenderRequest(plan, sampleRate, 16 * sampleRate)

        assertEquals(listOf(DubstepPartKind.Intro, DubstepPartKind.Drop, DubstepPartKind.Break, DubstepPartKind.Ending), request.parts.map { it.kind })
        assertEquals(request.parts[1].sliceGroup, request.parts[2].sliceGroup)
        assertTrue(request.parts[0].sliceGroup != request.parts[1].sliceGroup)
        assertTrue(request.parts[3].slices.isEmpty())
    }

    @Test
    fun indexesEverySampleOnce() {
        val plan = planDubstepRemix(makeAnalysis())
        val request = buildWubRenderRequest(plan, sampleRate, 16 * sampleRate)

        assertEquals(request.sampleNames.size, request.sampleNames.toSet().size)
        assertEquals(plan.parts.flatMap { it.samples }.toSet(), request.sampleNames.toSet())
        request.parts.forEachIndexed { index, part ->
            assertEquals(plan.parts[index].samples, part.sampleIndices.map { request.sampleNames[it] })
        }
    }

    @Test
    fun clampsSlicesPastTheEndOfTheSource() {
        val plan = planDubstepRemix(makeAnalysis())
        // Only the first 4 s of the 16 s the analysis describes exist.
        val request = buildWubRenderRequest(plan, sampleRate, 4 * sampleRate)

        val slices = request.parts.flatMap { it.slices }
        assertTrue(slices.all { it.stopFrame <= 4000 && it.startFrame <= it.stopFrame })
        assertTrue(slices.any { it.startFrame == it.stopFrame })
        assertTrue(slices.all { it.targetFrames > 0 })
    }

    @Test
    fun laysPartsOutAtEightBarsWithTheEndingTakingTheRest() {
        val plan = planDubstepRemix(makeAnalysis())
        val endingFrames = 500
        val parts = layoutWubParts(plan, sampleRate, bedFrames, 3 * bedFrames + endingFrames)

        assertEquals(listOf(0.0, 13.714, 27.428, 41.142), parts.map { it.start })
        assertEquals(listOf(13.714, 13.714, 13.714, 0.5), parts.map { it.duration })
        assertEquals(DubstepPartKind.Ending, parts.last().kind)
    }

    @Test
    fun loopRegionRunsFromTheEndOfTheIntroToTheEnding() {
        val plan = planDubstepRemix(makeAnalysis())
        val parts = layoutWubParts(plan, sampleRate, bedFrames, 3 * bedFrames + 500)

        val loop = wubLoopRegion(parts, 41.642)
        assertEquals(13.714, loop.start, 1e-9)
        assertEquals(41.142, loop.end, 1e-9)
        assertEquals(WubLoopRegion(0.0, 12.0), wubLoopRegion(emptyList(), 12.0))
    }

    @Test
    fun loopedPositionWrapsInsideTheBody() {
        val loop = WubLoopRegion(10.0, 30.0)
        assertEquals(15.0, loopedPosition(10.0, 5.0, true, loop), 0.0)
        assertEquals(15.0, loopedPosition(10.0, 25.0, true, loop), 1e-9)
        assertEquals(35.0, loopedPosition(10.0, 25.0, false, loop), 0.0)
        assertEquals(35.0, loopedPosition(10.0, 25.0, true, WubLoopRegion(30.0, 30.0)), 0.0)
    }
}
