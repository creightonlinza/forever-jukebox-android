package com.foreverjukebox.app.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class RebuildParityFixtureTest {

    @Test
    fun matchesSharedRebuildDeleteExpectations() {
        val root = loadFixtureRoot()
        val cases = root["cases"]!!.jsonArray

        for (testCaseElement in cases) {
            val testCase = testCaseElement.jsonObject
            val id = testCase["id"]!!.jsonPrimitive.content
            val beatsCount = testCase["beats"]!!.jsonPrimitive.int
            val initialLastBranchPoint = testCase["initialLastBranchPoint"]!!.jsonPrimitive.int
            val deleteEdgeIds = testCase["deleteEdgeIds"]!!.jsonArray
            val expected = testCase["expected"]!!.jsonObject
            val edgeSpecs = testCase["edges"]!!.jsonArray.map { tuple ->
                val arr = tuple.jsonArray
                EdgeSpec(
                    id = arr[0].jsonPrimitive.int,
                    src = arr[1].jsonPrimitive.int,
                    dest = arr[2].jsonPrimitive.int,
                    distance = arr[3].jsonPrimitive.int.toDouble()
                )
            }

            val engine = JukeboxEngine(
                player = FixturePlayer(),
                graphBuilder = { analysis, _ ->
                    buildFixtureGraph(
                        analysis = analysis,
                        edgeSpecs = edgeSpecs,
                        lastBranchPoint = initialLastBranchPoint
                    )
                },
                options = JukeboxEngineOptions(
                    config = JukeboxConfigUpdate(
                        currentThreshold = 10,
                        maxBranchThreshold = 80
                    )
                )
            )
            engine.loadAnalysis(makeAnalysisPayload(beatsCount))

            for (deleteIdElement in deleteEdgeIds) {
                val edgeId = deleteIdElement.jsonPrimitive.int
                val edge = engine
                    .getGraphState()
                    ?.allEdges
                    ?.find { candidate -> candidate.id == edgeId }
                requireNotNull(edge) { "$id: expected edge $edgeId missing" }
                engine.deleteEdge(edge)
            }
            engine.rebuildGraph()

            val graph = engine.getGraphState()
            assertNotNull("$id: graph exists", graph)
            assertEquals(
                "$id: lastBranchPoint",
                expected["lastBranchPoint"]!!.jsonPrimitive.int,
                graph?.lastBranchPoint
            )
            val viz = engine.getVisualizationData()
            assertNotNull("$id: visualization exists", viz)
            assertEquals(
                "$id: anchorEdgeId",
                expected["anchorEdgeId"]!!.jsonPrimitive.intOrNull,
                viz?.anchorEdgeId
            )
        }
    }

    private fun buildFixtureGraph(
        analysis: TrackAnalysis,
        edgeSpecs: List<EdgeSpec>,
        lastBranchPoint: Int
    ): JukeboxGraphState {
        for (beat in analysis.beats) {
            beat.neighbors = mutableListOf()
            beat.allNeighbors = mutableListOf()
        }
        val allEdges = edgeSpecs.map { spec ->
            makeEdge(
                id = spec.id,
                src = analysis.beats[spec.src],
                dest = analysis.beats[spec.dest],
                distance = spec.distance
            )
        }.toMutableList()
        for (edge in allEdges) {
            edge.src.neighbors.add(edge)
            edge.src.allNeighbors.add(edge)
        }
        return JukeboxGraphState(
            computedThreshold = 10,
            currentThreshold = 10,
            lastBranchPoint = lastBranchPoint,
            totalBeats = analysis.beats.size,
            longestReach = 0.0,
            allEdges = allEdges
        )
    }

    private fun loadFixtureRoot() = run {
        val userDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val candidates = mutableListOf<File>()
        var cursor: File? = userDir
        while (cursor != null) {
            candidates.add(File(cursor, "test-fixtures/engine-parity/rebuild-cases.json"))
            cursor = cursor.parentFile
        }
        val fixtureFile = candidates.firstOrNull { it.exists() } ?: error(
            "Could not find rebuild-cases.json. Looked in: " +
                candidates.joinToString(", ") { it.absolutePath }
        )
        Json.parseToJsonElement(fixtureFile.readText()).jsonObject
    }

    private fun makeAnalysisPayload(count: Int): JsonElement {
        val beats = JsonArray((0 until count).map { i ->
            JsonObject(
                mapOf(
                    "start" to JsonPrimitive(i.toDouble()),
                    "duration" to JsonPrimitive(1.0),
                    "confidence" to JsonPrimitive(1.0)
                )
            )
        })
        val segments = JsonArray((0 until count).map { i ->
            JsonObject(
                mapOf(
                    "start" to JsonPrimitive(i.toDouble()),
                    "duration" to JsonPrimitive(1.0),
                    "confidence" to JsonPrimitive(1.0),
                    "loudness_start" to JsonPrimitive(0.0),
                    "loudness_max" to JsonPrimitive(0.0),
                    "loudness_max_time" to JsonPrimitive(0.0),
                    "pitches" to JsonArray(List(12) { JsonPrimitive(0.0) }),
                    "timbre" to JsonArray(List(12) { JsonPrimitive(0.0) })
                )
            )
        })
        return JsonObject(
            mapOf(
                "sections" to beats,
                "bars" to beats,
                "beats" to beats,
                "tatums" to beats,
                "segments" to segments,
                "track" to JsonObject(mapOf("duration" to JsonPrimitive(count.toDouble())))
            )
        )
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
        val id: Int,
        val src: Int,
        val dest: Int,
        val distance: Double
    )

    private class FixturePlayer : JukeboxPlayer {
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun seek(time: Double) = Unit
        override fun scheduleJump(targetTime: Double, audioStart: Double) = Unit
        override fun getCurrentTime(): Double = 0.0
        override fun getAudioTime(): Double = 0.0
        override fun isPlaying(): Boolean = true
    }
}
