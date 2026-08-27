package com.foreverjukebox.app.export

import com.foreverjukebox.app.engine.Edge
import com.foreverjukebox.app.engine.JukeboxConfig
import com.foreverjukebox.app.engine.QuantumBase
import com.foreverjukebox.app.engine.RandomMode
import com.foreverjukebox.app.engine.TrackAnalysis
import com.foreverjukebox.app.engine.TrackMeta
import com.foreverjukebox.app.engine.createRng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JukeboxPathGeneratorTest {

    @Test
    fun seededPathIsReproducible() {
        val traceA = collectSteps(makeGenerator(seed = 42), STEP_COUNT)
        val traceB = collectSteps(makeGenerator(seed = 42), STEP_COUNT)
        assertEquals(
            traceA.map { it.beatIndex to it.jump?.targetIndex },
            traceB.map { it.beatIndex to it.jump?.targetIndex }
        )
        assertTrue("seeded path should branch", traceA.any { it.jump != null })
    }

    @Test
    fun stepsAdvanceLinearlyOrViaPlannedJump() {
        val steps = collectSteps(makeGenerator(seed = 7), STEP_COUNT)
        for (i in 0 until steps.size - 1) {
            val step = steps[i]
            val next = steps[i + 1]
            val expectedNext = step.jump?.targetIndex ?: (step.beatIndex + 1)
            assertEquals("step $i continuity", expectedNext, next.beatIndex)
            val jump = step.jump
            if (jump != null) {
                assertEquals(
                    "step $i jump target time",
                    BEAT_DURATION * jump.targetIndex,
                    jump.targetTime,
                    1e-9
                )
            }
            val expectedBoundary = if (step.beatIndex == BEAT_COUNT - 1) {
                BEAT_DURATION * BEAT_COUNT
            } else {
                BEAT_DURATION * (step.beatIndex + 1)
            }
            assertEquals("step $i boundary", expectedBoundary, step.boundarySourceTime, 1e-9)
        }
    }

    @Test
    fun deletedEdgesAreNeverTaken() {
        val seed = 12345
        val withEdge = collectSteps(makeGenerator(seed = seed), STEP_COUNT)
        assertTrue(
            "control run should take the edge that the deletion test removes",
            withEdge.any { jumpSourceSeed(it) == 6 && it.jump?.targetIndex == 2 }
        )
        val withoutEdge = collectSteps(
            makeGenerator(seed = seed, deletedEdgePairs = setOf(6 to 2)),
            STEP_COUNT
        )
        assertTrue(
            "deleted edge must never be taken",
            withoutEdge.none { jumpSourceSeed(it) == 6 && it.jump?.targetIndex == 2 }
        )
    }

    @Test
    fun userAnchorForcesItsBranchAndCapsPlayback() {
        val steps = collectSteps(
            makeGenerator(seed = 99, userAnchorPair = 5 to 1),
            STEP_COUNT
        )
        assertTrue("playback must never pass the anchor source", steps.all { it.beatIndex <= 5 })
        val anchorSeedJumps = steps.filter { jumpSourceSeed(it) == 5 }
        assertTrue("anchor source should be reached", anchorSeedJumps.isNotEmpty())
        assertTrue(
            "every branch from the anchor source must take the anchor edge",
            anchorSeedJumps.all { it.jump?.targetIndex == 1 }
        )
    }

    // The seed considered at a step's boundary is the timeline successor; a
    // branch recorded on the step is an edge from that seed.
    private fun jumpSourceSeed(step: JukeboxPathGenerator.Step): Int? {
        if (step.jump == null) return null
        return if (step.beatIndex == BEAT_COUNT - 1) 0 else step.beatIndex + 1
    }

    private fun collectSteps(
        generator: JukeboxPathGenerator,
        count: Int
    ): List<JukeboxPathGenerator.Step> {
        return (0 until count).mapNotNull { generator.nextStep() }
    }

    private fun makeGenerator(
        seed: Int,
        deletedEdgePairs: Set<Pair<Int, Int>> = emptySet(),
        userAnchorPair: Pair<Int, Int>? = null
    ): JukeboxPathGenerator {
        val analysis = makeAnalysis()
        var edgeId = 0
        for ((src, dest, distance) in EDGES) {
            val edge = Edge(
                id = edgeId,
                src = analysis.beats[src],
                dest = analysis.beats[dest],
                distance = distance,
                deleted = false
            )
            analysis.beats[src].allNeighbors.add(edge)
            edgeId += 1
        }
        return JukeboxPathGenerator(
            analysis = analysis,
            config = makeConfig(),
            deletedEdgePairs = deletedEdgePairs,
            userAnchorPair = userAnchorPair,
            rng = createRng(RandomMode.Seeded, seed)
        )
    }

    private fun makeAnalysis(): TrackAnalysis {
        val beats = (0 until BEAT_COUNT).map { which ->
            QuantumBase(
                start = BEAT_DURATION * which,
                duration = BEAT_DURATION,
                confidence = null,
                which = which
            )
        }.toMutableList()
        beats.forEachIndexed { index, beat ->
            beat.prev = beats.getOrNull(index - 1)
            beat.next = beats.getOrNull(index + 1)
        }
        return TrackAnalysis(
            sections = mutableListOf(),
            bars = mutableListOf(),
            beats = beats,
            tatums = mutableListOf(),
            segments = mutableListOf(),
            track = TrackMeta(duration = BEAT_DURATION * BEAT_COUNT)
        )
    }

    private fun makeConfig(): JukeboxConfig {
        return JukeboxConfig(
            maxBranches = 4,
            maxBranchThreshold = 80,
            currentThreshold = 60,
            minRandomBranchChance = 0.18,
            maxRandomBranchChance = 0.5,
            randomBranchChanceDelta = 0.018,
            minLongBranch = 1
        )
    }

    private companion object {
        const val BEAT_COUNT = 8
        const val BEAT_DURATION = 0.5
        const val STEP_COUNT = 400
        val EDGES = listOf(
            Triple(0, 4, 30.0),
            Triple(3, 0, 25.0),
            Triple(5, 1, 20.0),
            Triple(6, 2, 15.0),
            Triple(7, 3, 10.0)
        )
    }
}
