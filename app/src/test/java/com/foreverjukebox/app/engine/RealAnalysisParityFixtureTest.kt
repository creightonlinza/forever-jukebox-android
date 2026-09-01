package com.foreverjukebox.app.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A full engine analysis of a CC0 track, replayed through the graph builder and
 * the branch selector so that a change in threshold defaults, edge selection,
 * anchor placement, or the seeded jump sequence has to be an explicit fixture
 * update. Mirrors realAnalysisParityFixtures.test.ts in the web repo.
 */
class RealAnalysisParityFixtureTest {

    @Test
    fun matchesPinnedGraphSignatureForEveryTuningCase() {
        val root = loadEngineParityFixture(FIXTURE)
        for (testCaseElement in root["cases"]!!.jsonArray) {
            val testCase = testCaseElement.jsonObject
            val id = testCase["id"]!!.jsonPrimitive.content
            val expected = testCase["expected"]!!.jsonObject

            val analysis = normalizeAnalysis(root["analysis"]!!)
            val graph = buildJumpGraph(analysis, graphConfig(testCase["config"]!!.jsonObject))
            val counts = countActiveEdges(analysis)

            assertEquals(
                "$id: computedThreshold",
                expected["computedThreshold"]!!.jsonPrimitive.int,
                graph.computedThreshold
            )
            assertEquals(
                "$id: currentThreshold",
                expected["currentThreshold"]!!.jsonPrimitive.int,
                graph.currentThreshold
            )
            assertEquals(
                "$id: lastBranchPoint",
                expected["lastBranchPoint"]!!.jsonPrimitive.int,
                graph.lastBranchPoint
            )
            assertEquals(
                "$id: totalBeats",
                expected["totalBeats"]!!.jsonPrimitive.int,
                graph.totalBeats
            )
            assertEquals(
                "$id: longestReach",
                expected["longestReach"]!!.jsonPrimitive.double,
                rounded(graph.longestReach),
                0.0
            )
            assertEquals(
                "$id: allEdgesCount",
                expected["allEdgesCount"]!!.jsonPrimitive.int,
                graph.allEdges.size
            )
            assertEquals(
                "$id: activeEdgeCount",
                expected["activeEdgeCount"]!!.jsonPrimitive.int,
                counts.activeEdgeCount
            )
            assertEquals(
                "$id: branchingBeatCount",
                expected["branchingBeatCount"]!!.jsonPrimitive.int,
                counts.branchingBeatCount
            )
        }
    }

    @Test
    fun keepsBranchCountsMonotonicAcrossThresholdSweep() {
        val root = loadEngineParityFixture(FIXTURE)
        val sweep = root["cases"]!!.jsonArray
            .map { it.jsonObject }
            .filter { THRESHOLD_CASE_ID.matches(it["id"]!!.jsonPrimitive.content) }
            .sortedBy { it["config"]!!.jsonObject["currentThreshold"]!!.jsonPrimitive.int }
        assertTrue("threshold sweep must have more than one case", sweep.size > 1)

        for (i in 1 until sweep.size) {
            val previous = sweep[i - 1]
            val current = sweep[i]
            val previousCount = previous["expected"]!!.jsonObject["activeEdgeCount"]!!.jsonPrimitive.int
            val currentCount = current["expected"]!!.jsonObject["activeEdgeCount"]!!.jsonPrimitive.int
            assertTrue(
                "${current["id"]!!.jsonPrimitive.content} vs " +
                    "${previous["id"]!!.jsonPrimitive.content}: $currentCount > $previousCount",
                currentCount > previousCount
            )
        }
    }

    /**
     * Deleting by id is how the d= URL param reaches the engine; the ids are
     * only stable while edge construction order is, so they are pinned too.
     */
    @Test
    fun dropsExactlyTheDeletedEdgesFromTheActiveSet() {
        val root = loadEngineParityFixture(FIXTURE)
        for (testCaseElement in root["deletion_cases"]!!.jsonArray) {
            val testCase = testCaseElement.jsonObject
            val id = testCase["id"]!!.jsonPrimitive.content
            val expected = testCase["expected"]!!.jsonObject

            val analysis = normalizeAnalysis(root["analysis"]!!)
            buildJumpGraph(analysis, graphConfig(testCase["config"]!!.jsonObject))
            assertEquals(
                "$id: before deletion",
                expected["activeEdgeCountBefore"]!!.jsonPrimitive.int,
                countActiveEdges(analysis).activeEdgeCount
            )

            val deletedIds = testCase["deleteEdgeIds"]!!.jsonArray
                .map { it.jsonPrimitive.int }
                .toSet()
            for (beat in analysis.beats) {
                for (edge in beat.allNeighbors) {
                    if (deletedIds.contains(edge.id)) {
                        edge.deleted = true
                    }
                }
                beat.neighbors = beat.neighbors.filterNot { it.deleted }.toMutableList()
            }

            assertEquals(
                "$id: after deletion",
                expected["activeEdgeCountAfter"]!!.jsonPrimitive.int,
                countActiveEdges(analysis).activeEdgeCount
            )
            assertEquals(
                "$id: every pinned id was active",
                expected["deletedEdgeCount"]!!.jsonPrimitive.int,
                expected["activeEdgeCountBefore"]!!.jsonPrimitive.int -
                    expected["activeEdgeCountAfter"]!!.jsonPrimitive.int
            )
        }
    }

    @Test
    fun reproducesTheSeededPlaybackSequence() {
        val root = loadEngineParityFixture(FIXTURE)
        for (testCaseElement in root["sequence_cases"]!!.jsonArray) {
            val testCase = testCaseElement.jsonObject
            val id = testCase["id"]!!.jsonPrimitive.content
            val expected = testCase["expected"]!!.jsonObject
            val replay = replaySequence(root, testCase)

            assertEquals(
                "$id: jump count",
                expected["jumpCount"]!!.jsonPrimitive.int,
                replay.jumps.size
            )
            assertEquals(
                "$id: jumps",
                expected["jumps"]!!.jsonArray.map { jump ->
                    val values = jump.jsonArray
                    Triple(
                        values[0].jsonPrimitive.int,
                        values[1].jsonPrimitive.int,
                        values[2].jsonPrimitive.int
                    )
                },
                replay.jumps
            )
            assertEquals(
                "$id: beat sequence",
                expected["beatSequence"]!!.jsonArray.map { it.jsonPrimitive.int },
                replay.beatSequence
            )
        }
    }

    @Test
    fun keepsTheJumpRateOrderedByConfiguredBranchChance() {
        val root = loadEngineParityFixture(FIXTURE)
        val byId = root["sequence_cases"]!!.jsonArray
            .map { it.jsonObject }
            .associateBy { it["id"]!!.jsonPrimitive.content }
        val low = byId["seeded_low_branch_chance"]
        val mid = byId["seeded_default_branch_chance"]
        val high = byId["seeded_high_branch_chance"]
        assertNotNull("seeded_low_branch_chance case", low)
        assertNotNull("seeded_default_branch_chance case", mid)
        assertNotNull("seeded_high_branch_chance case", high)

        val lowJumps = jumpCount(low!!)
        val midJumps = jumpCount(mid!!)
        val highJumps = jumpCount(high!!)
        assertTrue("low ($lowJumps) < default ($midJumps)", lowJumps < midJumps)
        assertTrue("default ($midJumps) < high ($highJumps)", midJumps < highJumps)
    }

    /**
     * Mirrors JukeboxEngine.createPendingAdvance for ordinary playback: the next
     * selection is seeded one beat ahead (wrapping at the end), and a jump
     * replaces that seed with its destination. Timing-dependent paths (schedule
     * lead, bring it home, velocity) are outside this contract.
     */
    private fun replaySequence(root: JsonObject, testCase: JsonObject): SequenceReplay {
        val id = testCase["id"]!!.jsonPrimitive.content
        val analysis = normalizeAnalysis(root["analysis"]!!)
        val config = sequenceConfig(testCase["config"]!!.jsonObject)
        val graph = buildJumpGraph(analysis, config)
        val rng = createRng(RandomMode.Seeded, testCase["seed"]!!.jsonPrimitive.int)
        val state = BranchState(curRandomBranchChance = config.minRandomBranchChance)

        val anchorEdgeId = testCase["userAnchorEdgeId"]?.jsonPrimitive?.intOrNull
        val userAnchor = anchorEdgeId?.let { edgeId ->
            val edge = graph.allEdges.firstOrNull { it.id == edgeId }
            assertNotNull("$id: anchor edge must exist", edge)
            UserAnchorSelection(edgeId = edge!!.id, sourceIndex = edge.src.which)
        }

        val beats = analysis.beats
        val beatSequence = mutableListOf<Int>()
        val jumps = mutableListOf<Triple<Int, Int, Int>>()
        var current = 0
        repeat(testCase["steps"]!!.jsonPrimitive.int) { step ->
            val seedIndex = ((current + 1) % beats.size + beats.size) % beats.size
            val (index, jumped) = selectNextBeatIndex(
                seed = beats[seedIndex],
                graph = graph,
                config = config,
                rng = rng,
                state = state,
                forceBranch = false,
                userAnchor = userAnchor
            )
            current = if (jumped) {
                jumps += Triple(step, seedIndex, index)
                index
            } else {
                seedIndex
            }
            beatSequence += current
        }
        return SequenceReplay(beatSequence = beatSequence, jumps = jumps)
    }

    private fun jumpCount(testCase: JsonObject): Int =
        testCase["expected"]!!.jsonObject["jumpCount"]!!.jsonPrimitive.int

    private fun countActiveEdges(analysis: TrackAnalysis): ActiveEdgeCounts {
        var activeEdgeCount = 0
        var branchingBeatCount = 0
        for (beat in analysis.beats) {
            val active = beat.neighbors.count { !it.deleted }
            activeEdgeCount += active
            if (active > 0) {
                branchingBeatCount += 1
            }
        }
        return ActiveEdgeCounts(activeEdgeCount, branchingBeatCount)
    }

    private fun graphConfig(config: JsonObject): JukeboxConfig {
        return JukeboxConfig(
            maxBranches = config["maxBranches"]!!.jsonPrimitive.int,
            maxBranchThreshold = config["maxBranchThreshold"]!!.jsonPrimitive.int,
            currentThreshold = config["currentThreshold"]!!.jsonPrimitive.int,
            justBackwards = config["justBackwards"]!!.jsonPrimitive.booleanOrNull == true,
            justLongBranches = config["justLongBranches"]!!.jsonPrimitive.booleanOrNull == true,
            removeSequentialBranches = config["removeSequentialBranches"]!!
                .jsonPrimitive.booleanOrNull == true,
            minRandomBranchChance = 0.18,
            maxRandomBranchChance = 0.5,
            randomBranchChanceDelta = 0.02,
            minLongBranch = config["minLongBranch"]!!.jsonPrimitive.int
        )
    }

    private fun sequenceConfig(config: JsonObject): JukeboxConfig {
        return JukeboxConfig(
            maxBranches = config["maxBranches"]!!.jsonPrimitive.int,
            maxBranchThreshold = config["maxBranchThreshold"]!!.jsonPrimitive.int,
            currentThreshold = config["currentThreshold"]!!.jsonPrimitive.int,
            justBackwards = false,
            justLongBranches = false,
            removeSequentialBranches = false,
            minRandomBranchChance = config["minRandomBranchChance"]!!.jsonPrimitive.double,
            maxRandomBranchChance = config["maxRandomBranchChance"]!!.jsonPrimitive.double,
            randomBranchChanceDelta = config["randomBranchChanceDelta"]!!.jsonPrimitive.double,
            minLongBranch = config["minLongBranch"]!!.jsonPrimitive.int
        )
    }

    private fun rounded(value: Double): Double = Math.round(value * ROUNDING) / ROUNDING

    private data class ActiveEdgeCounts(
        val activeEdgeCount: Int,
        val branchingBeatCount: Int
    )

    private data class SequenceReplay(
        val beatSequence: List<Int>,
        val jumps: List<Triple<Int, Int, Int>>
    )

    private companion object {
        const val FIXTURE = "real-analysis-cases.json"
        const val ROUNDING = 1_000_000.0
        val THRESHOLD_CASE_ID = Regex("^threshold_\\d+$")
    }
}
