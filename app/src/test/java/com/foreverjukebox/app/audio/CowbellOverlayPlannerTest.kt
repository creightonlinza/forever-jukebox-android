package com.foreverjukebox.app.audio

import com.foreverjukebox.app.engine.QuantumBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class CowbellOverlayPlannerTest {

    @Test
    fun plansImmediateCowbellOnEveryBeat() {
        val planner = CowbellOverlayPlanner(random = sequenceRandom(0.0, 0.5, 0.5, 0.99))

        val hits = planner.planBeat(
            beatIndex = 1,
            beat = beat(start = 1.0, duration = 1.0),
            nextBeat = beat(start = 2.0, duration = 1.0),
            playbackRate = 1.0,
            playerVolume = 1.0,
            sectionStartBeatIndices = emptySet()
        )

        assertEquals(1, hits.size)
        assertEquals("cowbell0.wav", hits[0].sampleName)
        assertEquals(0.0, hits[0].delaySeconds, 0.000001)
        assertEquals(1.0, hits[0].gain, 0.000001)
        assertEquals(0.0, hits[0].pan, 0.000001)
    }

    @Test
    fun plansSubdivisionBurstWhenProbabilityHitsAndBeatIsLongEnough() {
        val planner = CowbellOverlayPlanner(
            random = sequenceRandom(
                0.0,
                0.5,
                0.5,
                0.01,
                0.0,
                0.0,
                0.5,
                0.5,
                1.0,
                0.5,
                0.99,
                0.5,
                0.5
            )
        )

        val hits = planner.planBeat(
            beatIndex = 1,
            beat = beat(start = 0.0, duration = 1.0),
            nextBeat = beat(start = 2.0, duration = 1.0),
            playbackRate = 2.0,
            playerVolume = 1.0,
            sectionStartBeatIndices = emptySet()
        )

        assertEquals(4, hits.size)
        assertEquals(0.25, hits[1].delaySeconds, 0.000001)
        assertEquals(0.50, hits[2].delaySeconds, 0.000001)
        assertEquals(0.75, hits[3].delaySeconds, 0.000001)
        assertTrue(hits.drop(1).all { it.gain in 0.55..0.8 })
        assertTrue(hits.all { it.pan in -PAN_RANGE..PAN_RANGE })
    }

    @Test
    fun skipsSubdivisionBurstForShortBeat() {
        val planner = CowbellOverlayPlanner(random = sequenceRandom(0.0, 0.5, 0.5, 0.01))

        val hits = planner.planBeat(
            beatIndex = 1,
            beat = beat(start = 0.0, duration = 0.29),
            nextBeat = null,
            playbackRate = 1.0,
            playerVolume = 1.0,
            sectionStartBeatIndices = emptySet()
        )

        assertEquals(1, hits.size)
    }

    @Test
    fun usesBeatDurationWhenNextBeatIsMissing() {
        val planner = CowbellOverlayPlanner(
            random = sequenceRandom(
                0.0,
                0.5,
                0.5,
                0.01,
                0.0,
                0.5,
                0.5,
                0.0,
                0.5,
                0.5,
                0.0,
                0.5,
                0.5
            )
        )

        val hits = planner.planBeat(
            beatIndex = 1,
            beat = beat(start = 0.0, duration = 0.8),
            nextBeat = null,
            playbackRate = 1.0,
            playerVolume = 1.0,
            sectionStartBeatIndices = emptySet()
        )

        assertEquals(0.2, hits[1].delaySeconds, 0.000001)
        assertEquals(0.4, hits[2].delaySeconds, 0.000001)
        assertEquals(0.6, hits[3].delaySeconds, 0.000001)
    }

    @Test
    fun plansWalkenOnSectionStartWhenProbabilityHits() {
        val planner = CowbellOverlayPlanner(
            random = sequenceRandom(0.0, 0.5, 0.5, 0.99, 0.2, 0.5, 0.5)
        )

        val hits = planner.planBeat(
            beatIndex = 3,
            beat = beat(start = 3.0, duration = 1.0),
            nextBeat = beat(start = 4.0, duration = 1.0),
            playbackRate = 1.0,
            playerVolume = 1.0,
            sectionStartBeatIndices = setOf(3)
        )

        assertEquals(2, hits.size)
        assertEquals("walken8.wav", hits[1].sampleName)
        assertEquals(2.5, hits[1].gain, 0.000001)
        assertEquals(0.0, hits[1].pan, 0.000001)
    }

    @Test
    fun plansTrillOnSectionStartWhenWalkenProbabilityMisses() {
        val planner = CowbellOverlayPlanner(random = sequenceRandom(0.0, 0.5, 0.5, 0.99, 0.8, 0.5))

        val hits = planner.planBeat(
            beatIndex = 3,
            beat = beat(start = 3.0, duration = 1.0),
            nextBeat = beat(start = 4.0, duration = 1.0),
            playbackRate = 1.0,
            playerVolume = 1.0,
            sectionStartBeatIndices = setOf(3)
        )

        assertEquals(2, hits.size)
        assertEquals(TRILL_SAMPLE_NAME, hits[1].sampleName)
        assertEquals(1.35, hits[1].gain, 0.000001)
    }

    @Test
    fun controllerPreservesOverUnityWalkenGainForNativeMixer() {
        val scheduler = FakeCowbellHitScheduler()
        val controller = NativeCowbellOverlayController(
            scheduler = scheduler,
            planner = CowbellOverlayPlanner(
                random = sequenceRandom(0.0, 0.5, 0.5, 0.99, 0.2, 0.5, 0.5)
            )
        )

        controller.setEnabled(true)
        controller.setSectionStartBeatIndices(setOf(3))
        controller.handleBeatEnter(
            beatIndex = 3,
            beat = beat(start = 3.0, duration = 1.0),
            nextBeat = beat(start = 4.0, duration = 1.0),
            playbackRate = 1.0
        )

        val walken = scheduler.hits.single { it.sampleName == "walken8.wav" }
        assertTrue(walken.leftVolume > 1.0f)
        assertTrue(walken.rightVolume > 1.0f)
    }

    private fun beat(start: Double, duration: Double): QuantumBase {
        return QuantumBase(
            start = start,
            duration = duration,
            confidence = 1.0,
            which = start.toInt()
        )
    }

    private fun sequenceRandom(vararg values: Double): () -> Double {
        val queue = ArrayDeque(values.toList())
        return {
            if (queue.isEmpty()) 0.5 else queue.removeFirst()
        }
    }

    private class FakeCowbellHitScheduler : CowbellHitScheduler {
        val hits = mutableListOf<ScheduledHit>()

        override fun preloadCowbellSamples() = Unit

        override fun scheduleCowbellHit(
            sampleName: String,
            targetTimeSeconds: Double,
            leftVolume: Float,
            rightVolume: Float
        ) {
            hits += ScheduledHit(sampleName, targetTimeSeconds, leftVolume, rightVolume)
        }

        override fun cancelPendingCowbellHits() = Unit

        override fun cancelCowbellHits() = Unit
    }

    private data class ScheduledHit(
        val sampleName: String,
        val targetTimeSeconds: Double,
        val leftVolume: Float,
        val rightVolume: Float
    )
}
