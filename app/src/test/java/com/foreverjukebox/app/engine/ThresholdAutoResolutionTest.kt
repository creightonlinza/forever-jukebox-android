package com.foreverjukebox.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The threshold contract at the engine boundary, stated without reference to how auto is spelled
 * anywhere above it. An auto threshold is an engine *input*; what the engine returns is always a
 * concrete threshold plus the auto value it would have picked, and those two are what the rest of
 * the app and the other Forever Jukebox frontends have to agree on.
 */
class ThresholdAutoResolutionTest {

    @Test
    fun autoResolvesToTheComputedThresholdAndTheComputedValueIsUsable() {
        val graph = buildGraph(currentThreshold = AUTO)

        assertEquals(graph.computedThreshold, graph.currentThreshold)
        // A computed threshold is always a threshold a control can display and a wire format can
        // carry, never one of the low values that stand in for "no threshold chosen".
        assertTrue(graph.computedThreshold >= 2)
    }

    @Test
    fun aChosenThresholdIsUsedVerbatimAndLeavesTheComputedValueAlone() {
        val auto = buildGraph(currentThreshold = AUTO)
        val chosen = auto.computedThreshold + 7
        val graph = buildGraph(currentThreshold = chosen)

        assertEquals(chosen, graph.currentThreshold)
        assertNotEquals(graph.computedThreshold, graph.currentThreshold)
        // The auto value describes the track, so choosing a threshold must not move it.
        assertEquals(auto.computedThreshold, graph.computedThreshold)
    }

    @Test
    fun choosingExactlyTheComputedThresholdSoundsIdenticalToAuto() {
        // Whether a threshold equal to the auto value is held as auto or as that number is
        // bookkeeping: the graph the listener hears is the same either way.
        val auto = buildGraph(currentThreshold = AUTO)
        val pinned = buildGraph(currentThreshold = auto.computedThreshold)

        assertEquals(auto.currentThreshold, pinned.currentThreshold)
        assertEquals(auto.computedThreshold, pinned.computedThreshold)
        assertEquals(auto.lastBranchPoint, pinned.lastBranchPoint)
        assertEquals(auto.longestReach, pinned.longestReach, 0.0)
        assertEquals(edgeSignature(auto), edgeSignature(pinned))
    }

    @Test
    fun everyThresholdTheEngineReportsIsAConcreteThreshold() {
        // The auto sentinel is an input spelling only; nothing downstream should have to defend
        // against reading it back out.
        for (input in listOf(AUTO, 1, 2, 25, 60)) {
            val graph = buildGraph(currentThreshold = input)

            assertTrue("currentThreshold for input $input", graph.currentThreshold >= 1)
            assertTrue("computedThreshold for input $input", graph.computedThreshold >= 2)
        }
    }

    private fun edgeSignature(graph: JukeboxGraphState): List<Triple<Int, Int, Boolean>> =
        graph.allEdges.map { Triple(it.src.which, it.dest.which, it.deleted) }

    private fun buildGraph(currentThreshold: Int): JukeboxGraphState {
        val analysis = makeAnalysis(TOTAL_BEATS)
        var edgeId = 0
        // A spread of distances so the density sweep has somewhere to settle.
        for (src in 0 until TOTAL_BEATS) {
            for (offset in listOf(9, 17, 33)) {
                val dest = (src + offset) % TOTAL_BEATS
                analysis.beats[src].allNeighbors.add(
                    makeEdge(
                        id = edgeId++,
                        src = analysis.beats[src],
                        dest = analysis.beats[dest],
                        distance = ((src * 7 + offset * 13) % 70).toDouble()
                    )
                )
            }
        }
        return buildJumpGraph(analysis, config(currentThreshold))
    }

    private fun config(currentThreshold: Int): JukeboxConfig = JukeboxConfig(
        maxBranches = 4,
        maxBranchThreshold = 80,
        currentThreshold = currentThreshold,
        justBackwards = false,
        justLongBranches = false,
        removeSequentialBranches = false,
        minRandomBranchChance = 0.18,
        maxRandomBranchChance = 0.5,
        randomBranchChanceDelta = 0.018
    )

    private fun makeAnalysis(totalBeats: Int): TrackAnalysis {
        val beats = (0 until totalBeats).map {
            QuantumBase(start = it.toDouble(), duration = 1.0, confidence = null, which = it)
        }.toMutableList()
        beats.forEachIndexed { index, beat ->
            beat.prev = if (index > 0) beats[index - 1] else null
            beat.next = if (index < beats.size - 1) beats[index + 1] else null
        }
        return TrackAnalysis(
            sections = mutableListOf(),
            bars = mutableListOf(),
            beats = beats,
            tatums = mutableListOf(),
            segments = mutableListOf(),
            track = TrackMeta(duration = totalBeats.toDouble())
        )
    }

    private fun makeEdge(id: Int, src: QuantumBase, dest: QuantumBase, distance: Double): Edge =
        Edge(id = id, src = src, dest = dest, distance = distance, deleted = false)

    private companion object {
        /** How a caller asks the engine to pick the threshold itself. */
        const val AUTO = 0
        const val TOTAL_BEATS = 120
    }
}
