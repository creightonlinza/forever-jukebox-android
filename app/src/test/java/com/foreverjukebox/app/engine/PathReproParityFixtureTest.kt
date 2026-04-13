package com.foreverjukebox.app.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

class PathReproParityFixtureTest {

    @Test
    fun reproducesIdenticalPathForIndependentSeededSessions() {
        val root = loadFixtureRoot()
        val cases = root["cases"]!!.jsonArray

        for (testCaseElement in cases) {
            val testCase = testCaseElement.jsonObject
            val id = testCase["id"]!!.jsonPrimitive.content
            val beats = testCase["beats"]!!.jsonPrimitive.int
            val steps = testCase["steps"]!!.jsonPrimitive.int
            val randomMode = randomModeFrom(testCase["randomMode"]?.jsonPrimitive?.content)
            val seed = testCase["seed"]?.jsonPrimitive?.intOrNull
            val config = configFromFixture(testCase["config"]!!.jsonObject)
            val expected = requireNotNull(testCase["expected"]?.jsonObject) {
                "$id: fixture must define expected beat and jump traces"
            }
            val edges = testCase["edges"]!!.jsonArray.map { tuple ->
                val arr = tuple.jsonArray
                EdgeSpec(
                    src = arr[0].jsonPrimitive.int,
                    dest = arr[1].jsonPrimitive.int,
                    distance = arr[2].jsonPrimitive.double
                )
            }

            val sessionA = simulatePathTrace(
                beats = beats,
                edges = edges,
                config = config,
                mode = randomMode,
                seed = seed,
                steps = steps
            )
            val sessionB = simulatePathTrace(
                beats = beats,
                edges = edges,
                config = config,
                mode = randomMode,
                seed = seed,
                steps = steps
            )

            assertEquals("$id: beat trace length", steps, sessionA.beatTrace.size)
            assertEquals("$id: jump trace length", steps, sessionA.jumpTrace.size)
            assertEquals("$id: beat trace reproducibility", sessionA.beatTrace, sessionB.beatTrace)
            assertEquals("$id: jump trace reproducibility", sessionA.jumpTrace, sessionB.jumpTrace)
            assertTrue("$id: should include at least one jump", sessionA.jumpTrace.any { it })
            val expectedBeatTrace = expected["beatTrace"]!!.jsonArray.map { it.jsonPrimitive.int }
            val expectedJumpTrace = expected["jumpTrace"]!!.jsonArray.map { it.jsonPrimitive.booleanOrNull == true }
            assertEquals("$id: expected beat trace length", steps, expectedBeatTrace.size)
            assertEquals("$id: expected jump trace length", steps, expectedJumpTrace.size)
            assertEquals("$id: expected beat trace", expectedBeatTrace, sessionA.beatTrace)
            assertEquals("$id: expected jump trace", expectedJumpTrace, sessionA.jumpTrace)
        }
    }

    @Test
    fun engineAdvanceBeatMatchesSelectionLoopParity() {
        val root = loadFixtureRoot()
        val cases = root["cases"]!!.jsonArray

        for (testCaseElement in cases) {
            val testCase = testCaseElement.jsonObject
            val id = testCase["id"]!!.jsonPrimitive.content
            val beats = testCase["beats"]!!.jsonPrimitive.int
            val steps = testCase["steps"]!!.jsonPrimitive.int
            val randomMode = randomModeFrom(testCase["randomMode"]?.jsonPrimitive?.content)
            val seed = testCase["seed"]?.jsonPrimitive?.intOrNull
            val config = configFromFixture(testCase["config"]!!.jsonObject)
            val edges = testCase["edges"]!!.jsonArray.map { tuple ->
                val arr = tuple.jsonArray
                EdgeSpec(
                    src = arr[0].jsonPrimitive.int,
                    dest = arr[1].jsonPrimitive.int,
                    distance = arr[2].jsonPrimitive.double
                )
            }

            val selectionTrace = simulatePathTrace(
                beats = beats,
                edges = edges,
                config = config,
                mode = randomMode,
                seed = seed,
                steps = steps
            )
            val engineTrace = simulateEngineTrace(
                beats = beats,
                edges = edges,
                config = config,
                mode = randomMode,
                seed = seed,
                steps = steps
            )

            assertEquals("$id: engine beat trace parity", selectionTrace.beatTrace, engineTrace.beatTrace)
            assertEquals("$id: engine jump trace parity", selectionTrace.jumpTrace, engineTrace.jumpTrace)
            assertEquals(
                "$id: engine lastJumpFromIndex parity",
                selectionTrace.lastJumpFromTrace,
                engineTrace.lastJumpFromTrace
            )
            assertEquals(
                "$id: scheduleJump call count parity",
                selectionTrace.jumpTrace.count { it },
                engineTrace.scheduleJumpCount
            )
        }
    }

    private fun simulatePathTrace(
        beats: Int,
        edges: List<EdgeSpec>,
        config: JukeboxConfig,
        mode: RandomMode,
        seed: Int?,
        steps: Int
    ): SessionTrace {
        val analysis = makeAnalysis(beats)
        var edgeId = 0
        for (edge in edges) {
            val src = analysis.beats[edge.src]
            val dest = analysis.beats[edge.dest]
            src.allNeighbors.add(
                makeEdge(
                    id = edgeId,
                    src = src,
                    dest = dest,
                    distance = edge.distance
                )
            )
            edgeId += 1
        }
        val graph = buildJumpGraph(analysis, config)
        val rng = createRng(mode, seed)
        val branchState = BranchState(curRandomBranchChance = config.minRandomBranchChance)
        var curRandomBranchChance = config.minRandomBranchChance
        var currentBeatIndex = -1
        val beatTrace = mutableListOf<Int>()
        val jumpTrace = mutableListOf<Boolean>()
        val lastJumpFromTrace = mutableListOf<Int?>()

        repeat(steps) {
            var chosenIndex = 0
            var jumped = false
            var jumpFromIndex: Int? = null
            if (currentBeatIndex >= 0) {
                val nextIndex = if (currentBeatIndex + 1 >= analysis.beats.size) {
                    0
                } else {
                    currentBeatIndex + 1
                }
                val seedBeat = analysis.beats[nextIndex]
                branchState.curRandomBranchChance = curRandomBranchChance
                val selection = selectNextBeatIndex(
                    seed = seedBeat,
                    graph = graph,
                    config = config,
                    rng = rng,
                    state = branchState,
                    forceBranch = false
                )
                curRandomBranchChance = branchState.curRandomBranchChance
                jumped = selection.second
                chosenIndex = if (jumped) selection.first else nextIndex
                if (nextIndex == 0 && currentBeatIndex == analysis.beats.lastIndex) {
                    jumped = true
                }
                jumpFromIndex = if (jumped) {
                    if (selection.second) seedBeat.which else currentBeatIndex
                } else {
                    null
                }
            }
            beatTrace += chosenIndex
            jumpTrace += jumped
            lastJumpFromTrace += jumpFromIndex
            currentBeatIndex = chosenIndex
        }
        return SessionTrace(
            beatTrace = beatTrace,
            jumpTrace = jumpTrace,
            lastJumpFromTrace = lastJumpFromTrace
        )
    }

    private fun simulateEngineTrace(
        beats: Int,
        edges: List<EdgeSpec>,
        config: JukeboxConfig,
        mode: RandomMode,
        seed: Int?,
        steps: Int
    ): EngineSessionTrace {
        val player = TracePlayer()
        val engine = JukeboxEngine(
            player = player,
            options = JukeboxEngineOptions(
                randomMode = mode,
                seed = seed,
                config = config.toUpdate()
            ),
            graphBuilder = { analysis, _ ->
                buildFixtureGraph(analysis, edges, config)
            }
        )
        engine.loadAnalysis(makeAnalysisPayload(beats))

        val beatTrace = mutableListOf<Int>()
        val jumpTrace = mutableListOf<Boolean>()
        val lastJumpFromTrace = mutableListOf<Int?>()
        for (step in 0 until steps) {
            invokeAdvanceBeat(engine, step.toDouble())
            beatTrace += getPrivateField<Int>(engine, "currentBeatIndex")
            jumpTrace += getPrivateField<Boolean>(engine, "lastJumped")
            lastJumpFromTrace += getPrivateField<Int?>(engine, "lastJumpFromIndex")
        }
        return EngineSessionTrace(
            beatTrace = beatTrace,
            jumpTrace = jumpTrace,
            lastJumpFromTrace = lastJumpFromTrace,
            scheduleJumpCount = player.scheduleJumpCalls.size
        )
    }

    private fun buildFixtureGraph(
        analysis: TrackAnalysis,
        edges: List<EdgeSpec>,
        config: JukeboxConfig
    ): JukeboxGraphState {
        for (beat in analysis.beats) {
            beat.neighbors = mutableListOf()
            beat.allNeighbors = mutableListOf()
        }
        var edgeId = 0
        for (edge in edges) {
            val src = analysis.beats[edge.src]
            val dest = analysis.beats[edge.dest]
            src.allNeighbors.add(
                makeEdge(
                    id = edgeId,
                    src = src,
                    dest = dest,
                    distance = edge.distance
                )
            )
            edgeId += 1
        }
        return buildJumpGraph(analysis, config)
    }

    private fun configFromFixture(configObj: kotlinx.serialization.json.JsonObject): JukeboxConfig {
        val defaults = JukeboxConfig()
        return JukeboxConfig(
            maxBranches = configObj["maxBranches"]?.jsonPrimitive?.intOrNull ?: defaults.maxBranches,
            maxBranchThreshold = configObj["maxBranchThreshold"]?.jsonPrimitive?.intOrNull
                ?: defaults.maxBranchThreshold,
            currentThreshold = configObj["currentThreshold"]?.jsonPrimitive?.intOrNull ?: defaults.currentThreshold,
            justBackwards = configObj["justBackwards"]?.jsonPrimitive?.booleanOrNull ?: defaults.justBackwards,
            justLongBranches = configObj["justLongBranches"]?.jsonPrimitive?.booleanOrNull
                ?: defaults.justLongBranches,
            removeSequentialBranches = configObj["removeSequentialBranches"]?.jsonPrimitive?.booleanOrNull
                ?: defaults.removeSequentialBranches,
            minRandomBranchChance = configObj["minRandomBranchChance"]?.jsonPrimitive?.doubleOrNull
                ?: defaults.minRandomBranchChance,
            maxRandomBranchChance = configObj["maxRandomBranchChance"]?.jsonPrimitive?.doubleOrNull
                ?: defaults.maxRandomBranchChance,
            randomBranchChanceDelta = configObj["randomBranchChanceDelta"]?.jsonPrimitive?.doubleOrNull
                ?: defaults.randomBranchChanceDelta,
            minLongBranch = configObj["minLongBranch"]?.jsonPrimitive?.intOrNull ?: defaults.minLongBranch
        )
    }

    private fun randomModeFrom(raw: String?): RandomMode {
        return when (raw) {
            null -> RandomMode.Seeded
            "Random" -> error("Random mode is not allowed in exact-trace parity fixtures")
            "Seeded" -> RandomMode.Seeded
            "Deterministic" -> RandomMode.Deterministic
            else -> error("Unknown randomMode in fixture: $raw")
        }
    }

    private fun loadFixtureRoot() = run {
        val userDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val candidates = mutableListOf<File>()
        var cursor: File? = userDir
        while (cursor != null) {
            candidates.add(File(cursor, "test-fixtures/engine-parity/path-repro-cases.json"))
            cursor = cursor.parentFile
        }
        val fixtureFile = candidates.firstOrNull { it.exists() } ?: error(
            "Could not find path-repro-cases.json. Looked in: " +
                candidates.joinToString(", ") { it.absolutePath }
        )
        Json.parseToJsonElement(fixtureFile.readText()).jsonObject
    }

    private fun makeAnalysisPayload(count: Int): kotlinx.serialization.json.JsonElement {
        val beats = kotlinx.serialization.json.JsonArray((0 until count).map { i ->
            kotlinx.serialization.json.JsonObject(
                mapOf(
                    "start" to kotlinx.serialization.json.JsonPrimitive(i.toDouble()),
                    "duration" to kotlinx.serialization.json.JsonPrimitive(1.0),
                    "confidence" to kotlinx.serialization.json.JsonPrimitive(1.0)
                )
            )
        })
        val segments = kotlinx.serialization.json.JsonArray((0 until count).map { i ->
            kotlinx.serialization.json.JsonObject(
                mapOf(
                    "start" to kotlinx.serialization.json.JsonPrimitive(i.toDouble()),
                    "duration" to kotlinx.serialization.json.JsonPrimitive(1.0),
                    "confidence" to kotlinx.serialization.json.JsonPrimitive(1.0),
                    "loudness_start" to kotlinx.serialization.json.JsonPrimitive(0.0),
                    "loudness_max" to kotlinx.serialization.json.JsonPrimitive(0.0),
                    "loudness_max_time" to kotlinx.serialization.json.JsonPrimitive(0.0),
                    "pitches" to kotlinx.serialization.json.JsonArray(
                        List(12) { kotlinx.serialization.json.JsonPrimitive(0.0) }
                    ),
                    "timbre" to kotlinx.serialization.json.JsonArray(
                        List(12) { kotlinx.serialization.json.JsonPrimitive(0.0) }
                    )
                )
            )
        })
        return kotlinx.serialization.json.JsonObject(
            mapOf(
                "sections" to beats,
                "bars" to beats,
                "beats" to beats,
                "tatums" to beats,
                "segments" to segments,
                "track" to kotlinx.serialization.json.JsonObject(
                    mapOf("duration" to kotlinx.serialization.json.JsonPrimitive(count.toDouble()))
                )
            )
        )
    }

    private fun makeBeat(which: Int): QuantumBase {
        return QuantumBase(
            start = which.toDouble(),
            duration = 1.0,
            confidence = null,
            which = which
        )
    }

    private fun makeAnalysis(totalBeats: Int): TrackAnalysis {
        val beats = (0 until totalBeats).map { makeBeat(it) }.toMutableList()
        linkBeats(beats)
        return TrackAnalysis(
            sections = mutableListOf(),
            bars = mutableListOf(),
            beats = beats,
            tatums = mutableListOf(),
            segments = mutableListOf(),
            track = TrackMeta(duration = totalBeats.toDouble())
        )
    }

    private fun linkBeats(beats: MutableList<QuantumBase>) {
        beats.forEachIndexed { index, beat ->
            beat.prev = if (index > 0) beats[index - 1] else null
            beat.next = if (index < beats.size - 1) beats[index + 1] else null
        }
    }

    private fun makeEdge(id: Int, src: QuantumBase, dest: QuantumBase, distance: Double): Edge {
        return Edge(
            id = id,
            src = src,
            dest = dest,
            distance = distance,
            deleted = false
        )
    }

    private data class EdgeSpec(
        val src: Int,
        val dest: Int,
        val distance: Double
    )

    private data class SessionTrace(
        val beatTrace: List<Int>,
        val jumpTrace: List<Boolean>,
        val lastJumpFromTrace: List<Int?>
    )

    private data class EngineSessionTrace(
        val beatTrace: List<Int>,
        val jumpTrace: List<Boolean>,
        val lastJumpFromTrace: List<Int?>,
        val scheduleJumpCount: Int
    )

    private fun invokeAdvanceBeat(engine: JukeboxEngine, audioTime: Double) {
        val method: Method = JukeboxEngine::class.java.getDeclaredMethod(
            "advanceBeat",
            Double::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(engine, audioTime)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getPrivateField(engine: JukeboxEngine, fieldName: String): T {
        val field: Field = JukeboxEngine::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(engine) as T
    }

    private class TracePlayer : JukeboxPlayer {
        val scheduleJumpCalls = mutableListOf<Pair<Double, Double>>()

        override fun play() = Unit

        override fun pause() = Unit

        override fun stop() = Unit

        override fun seek(time: Double) = Unit

        override fun scheduleJump(targetTime: Double, audioStart: Double) {
            scheduleJumpCalls.add(targetTime to audioStart)
        }

        override fun getCurrentTime(): Double = 0.0

        override fun getAudioTime(): Double = 0.0

        override fun isPlaying(): Boolean = true
    }
}
