package com.foreverjukebox.app.engine

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `ab` wire format and custom anchor playback. Mirrors customAnchorParityFixtures.test.ts in
 * the web repo: both parse the same strings and replay the same seeded sequences.
 */
class CustomAnchorParityFixtureTest {

    @Test
    fun parsesEveryAbValueTheSameWay() {
        val root = loadEngineParityFixture(FIXTURE)
        for (element in root["param_cases"]!!.jsonArray) {
            val testCase = element.jsonObject
            val raw = testCase["raw"]!!.jsonPrimitive.content
            val expectedJson = testCase["expected"]!!
            val expected = if (expectedJson is JsonNull) {
                null
            } else {
                val fields = expectedJson.jsonObject
                fields["edgeId"]?.let { AnchorParam.EdgeId(it.jsonPrimitive.int) }
                    ?: AnchorParam.Custom(
                        CustomAnchor(fields["src"]!!.jsonPrimitive.int, fields["dest"]!!.jsonPrimitive.int)
                    )
            }
            assertEquals("\"$raw\"", expected, parseAnchorParam(raw))
        }
    }

    @Test
    fun replaysTheSeededSequenceWithACustomAnchor() {
        val root = loadEngineParityFixture(FIXTURE)
        val analysisRoot = loadEngineParityFixture(root["analysis_source"]!!.jsonPrimitive.content)
        for (element in root["sequence_cases"]!!.jsonArray) {
            val testCase = element.jsonObject
            val id = testCase["id"]!!.jsonPrimitive.content
            val anchorRaw = testCase["anchor"]!!.jsonPrimitive.content
            val expected = testCase["expected"]!!.jsonObject
            val replay = replay(analysisRoot, testCase)

            assertEquals(
                "$id: pair distance",
                expected["pairDistance"]!!.jsonPrimitive.double,
                replay.pairDistance,
                0.0
            )
            assertEquals(
                "$id: same bar position",
                expected["sameBarPosition"]!!.jsonPrimitive.boolean,
                replay.sameBarPosition
            )
            assertEquals("$id: jump count", expected["jumpCount"]!!.jsonPrimitive.int, replay.jumps.size)
            assertEquals(
                "$id: jumps",
                expected["jumps"]!!.jsonArray.map { jump ->
                    val values = jump.jsonArray
                    Triple(values[0].jsonPrimitive.int, values[1].jsonPrimitive.int, values[2].jsonPrimitive.int)
                },
                replay.jumps
            )
            assertEquals(
                "$id: beat sequence",
                expected["beatSequence"]!!.jsonArray.map { it.jsonPrimitive.int },
                replay.beatSequence
            )
            assertTrue(
                "$id: the anchor jump is taken",
                replay.jumps.any { (_, from, to) -> "$from-$to" == anchorRaw }
            )
        }
    }

    // Mirrors JukeboxEngine: the custom edge joins its source beat's neighbors after the graph is built.
    private fun replay(analysisRoot: JsonObject, testCase: JsonObject): Replay {
        val id = testCase["id"]!!.jsonPrimitive.content
        val analysis = normalizeAnalysis(analysisRoot["analysis"]!!)
        val config = config(testCase["config"]!!.jsonObject)
        val graph = buildJumpGraph(analysis, config)
        val param = parseAnchorParam(testCase["anchor"]!!.jsonPrimitive.content)
        assertTrue("$id: anchor parses as a pair", param is AnchorParam.Custom)
        val edge = createCustomAnchorEdge(analysis.beats, (param as AnchorParam.Custom).anchor)
        assertNotNull("$id: anchor fits the track", edge)
        edge!!.src.neighbors.add(edge)
        val userAnchor = UserAnchorSelection(edgeId = edge.id, sourceIndex = edge.src.which)
        val rng = createRng(RandomMode.Seeded, testCase["seed"]!!.jsonPrimitive.int)
        val state = BranchState(curRandomBranchChance = config.minRandomBranchChance)

        val beats = analysis.beats
        val beatSequence = mutableListOf<Int>()
        val jumps = mutableListOf<Triple<Int, Int, Int>>()
        var current = 0
        repeat(testCase["steps"]!!.jsonPrimitive.int) { step ->
            val seedIndex = (current + 1) % beats.size
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
        return Replay(
            pairDistance = Math.round(beatPairDistance(edge.src, edge.dest) * ROUNDING) / ROUNDING,
            sameBarPosition = edge.src.indexInParent == edge.dest.indexInParent,
            jumps = jumps,
            beatSequence = beatSequence
        )
    }

    private fun config(config: JsonObject): JukeboxConfig {
        return JukeboxConfig(
            maxBranches = config["maxBranches"]!!.jsonPrimitive.int,
            maxBranchThreshold = config["maxBranchThreshold"]!!.jsonPrimitive.int,
            currentThreshold = config["currentThreshold"]!!.jsonPrimitive.int,
            justBackwards = config["justBackwards"]!!.jsonPrimitive.boolean,
            justLongBranches = config["justLongBranches"]!!.jsonPrimitive.boolean,
            removeSequentialBranches = config["removeSequentialBranches"]!!.jsonPrimitive.boolean,
            minRandomBranchChance = config["minRandomBranchChance"]!!.jsonPrimitive.double,
            maxRandomBranchChance = config["maxRandomBranchChance"]!!.jsonPrimitive.double,
            randomBranchChanceDelta = config["randomBranchChanceDelta"]!!.jsonPrimitive.double,
            minLongBranch = config["minLongBranch"]!!.jsonPrimitive.int
        )
    }

    private data class Replay(
        val pairDistance: Double,
        val sameBarPosition: Boolean,
        val jumps: List<Triple<Int, Int, Int>>,
        val beatSequence: List<Int>
    )

    private companion object {
        const val FIXTURE = "custom-anchor-cases.json"
        const val ROUNDING = 1_000_000.0
    }
}
