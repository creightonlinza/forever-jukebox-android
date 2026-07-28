package com.foreverjukebox.app.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GraphCoreParityTest {

    @Test
    fun buildsNeighborsAndLastBranchPoint() {
        val analysis = normalizeAnalysis(makeAnalysisPayload())
        val graph = buildJumpGraph(analysis, config(currentThreshold = 60, minLongBranch = 1))

        assertEquals(4, graph.totalBeats)
        assertTrue(graph.lastBranchPoint >= 0)
        assertTrue(analysis.beats.any { it.neighbors.isNotEmpty() })
    }

    @Test
    fun respectsJustBackwardsAndJustLongBranchesFilters() {
        val analysis = normalizeAnalysis(makeAnalysisPayload())
        val graph = buildJumpGraph(
            analysis,
            config(
                currentThreshold = 80,
                justBackwards = true,
                justLongBranches = true,
                minLongBranch = 2
            )
        )

        assertEquals(4, graph.totalBeats)
        for (beat in analysis.beats) {
            for (neighbor in beat.neighbors) {
                assertTrue(neighbor.dest.which < beat.which)
                assertTrue(abs(neighbor.dest.which - beat.which) >= 2)
            }
        }
    }

    @Test
    fun longBranchFilterUsesAbsoluteBeatDistanceAndIncludesThreshold() {
        val analysis = normalizeAnalysis(makeAnalysisPayload(count = 6))
        val beats = analysis.beats
        beats.forEach {
            it.allNeighbors = mutableListOf()
            it.neighbors = mutableListOf()
        }
        var edgeId = 0
        fun addEdge(src: Int, dest: Int) {
            beats[src].allNeighbors += Edge(
                id = edgeId++,
                src = beats[src],
                dest = beats[dest],
                distance = 10.0,
                deleted = false
            )
        }
        addEdge(0, 2)
        addEdge(1, 2)
        addEdge(1, 3)
        addEdge(5, 4)
        addEdge(5, 3)
        addEdge(5, 0)

        buildJumpGraph(
            analysis,
            config(
                currentThreshold = 20,
                justLongBranches = true,
                minLongBranch = 2
            )
        )

        val retained = beats.flatMap { beat ->
            beat.neighbors.map { edge -> edge.src.which to edge.dest.which }
        }.toSet()
        assertTrue((0 to 2) in retained)
        assertTrue((1 to 3) in retained)
        assertTrue((5 to 3) in retained)
        assertFalse((1 to 2) in retained)
        assertFalse((5 to 4) in retained)
    }

    @Test
    fun filtersSequentialBranchesWhenEnabled() {
        val analysis = normalizeAnalysis(makeAnalysisPayload())
        val graph = buildJumpGraph(
            analysis,
            config(
                currentThreshold = 80,
                justBackwards = true,
                removeSequentialBranches = true,
                minLongBranch = 1
            )
        )
        val lastBranchPoint = graph.lastBranchPoint
        for (i in 1 until analysis.beats.size) {
            if (i == lastBranchPoint) {
                continue
            }
            val prev = analysis.beats[i - 1]
            val current = analysis.beats[i]
            val prevDistances = prev.neighbors.map { prev.which - it.dest.which }.toSet()
            for (edge in current.neighbors) {
                val distance = current.which - edge.dest.which
                assertFalse(prevDistances.contains(distance))
            }
        }
    }

    @Test
    fun usesComputedThresholdWhenCurrentThresholdIsZero() {
        val analysis = normalizeAnalysis(makeAnalysisPayload())
        val graph = buildJumpGraph(analysis, config(currentThreshold = 0, minLongBranch = 1))

        assertEquals(graph.computedThreshold, graph.currentThreshold)
        assertTrue(graph.currentThreshold > 0)
    }

    @Test
    fun keepsCurrentThresholdWhenProvided() {
        val analysis = normalizeAnalysis(makeAnalysisPayload())
        val graph = buildJumpGraph(analysis, config(currentThreshold = 60, minLongBranch = 1))

        assertEquals(60, graph.currentThreshold)
        assertTrue(graph.computedThreshold > 0)
    }

    @Test
    fun keepsReversePlusLongFiltersASubsetOfLongFiltersWithAutoThreshold() {
        val longOnlyAnalysis = normalizeAnalysis(makeHappyPathAnalysisPayload())
        val reverseLongAnalysis = normalizeAnalysis(makeHappyPathAnalysisPayload())
        val longOnlyGraph = buildJumpGraph(
            longOnlyAnalysis,
            autoThresholdConfig(justLongBranches = true)
        )
        val reverseLongGraph = buildJumpGraph(
            reverseLongAnalysis,
            autoThresholdConfig(justBackwards = true, justLongBranches = true)
        )
        val longOnlyEdges = collectEdgeKeys(longOnlyAnalysis).toSet()
        val reverseLongEdges = collectEdgeKeys(reverseLongAnalysis)

        assertEquals(longOnlyGraph.currentThreshold, reverseLongGraph.currentThreshold)
        assertTrue(reverseLongEdges.size <= longOnlyEdges.size)
        assertTrue(longOnlyEdges.containsAll(reverseLongEdges))
    }

    @Test
    fun longBranchFilterDoesNotEscalateAutoThresholdOrAddBranches() {
        val unfilteredAnalysis = normalizeAnalysis(makeThresholdEscalationPayload())
        val filteredAnalysis = normalizeAnalysis(makeThresholdEscalationPayload())
        val unfilteredGraph = buildJumpGraph(
            unfilteredAnalysis,
            autoThresholdConfig(minLongBranch = 8)
        )
        val filteredGraph = buildJumpGraph(
            filteredAnalysis,
            autoThresholdConfig(justLongBranches = true, minLongBranch = 8)
        )

        assertEquals(unfilteredGraph.computedThreshold, filteredGraph.computedThreshold)
        assertTrue(
            collectEdgeKeys(filteredAnalysis).size <= collectEdgeKeys(unfilteredAnalysis).size
        )
    }

    @Test
    fun reusesCachedNeighborsAndReturnsStableAllEdges() {
        val analysis = normalizeAnalysis(makeAnalysisPayload())
        val cfg = config(currentThreshold = 60, minLongBranch = 1)

        val first = buildJumpGraph(analysis, cfg)
        val firstCount = first.allEdges.size
        assertTrue(firstCount > 0)

        val second = buildJumpGraph(analysis, cfg)
        assertEquals(firstCount, second.allEdges.size)
    }

    private fun config(
        currentThreshold: Int,
        justBackwards: Boolean = false,
        justLongBranches: Boolean = false,
        removeSequentialBranches: Boolean = false,
        minLongBranch: Int
    ): JukeboxConfig {
        return JukeboxConfig(
            maxBranches = 3,
            maxBranchThreshold = 80,
            currentThreshold = currentThreshold,
            justBackwards = justBackwards,
            justLongBranches = justLongBranches,
            removeSequentialBranches = removeSequentialBranches,
            minRandomBranchChance = 0.18,
            maxRandomBranchChance = 0.5,
            randomBranchChanceDelta = 0.018,
            minLongBranch = minLongBranch
        )
    }

    private fun autoThresholdConfig(
        justBackwards: Boolean = false,
        justLongBranches: Boolean = false,
        minLongBranch: Int = 4
    ): JukeboxConfig {
        return JukeboxConfig(
            maxBranches = 4,
            maxBranchThreshold = 80,
            currentThreshold = 0,
            justBackwards = justBackwards,
            justLongBranches = justLongBranches,
            removeSequentialBranches = false,
            minRandomBranchChance = 0.18,
            maxRandomBranchChance = 0.5,
            randomBranchChanceDelta = 0.018,
            minLongBranch = minLongBranch
        )
    }

    private fun collectEdgeKeys(analysis: TrackAnalysis): List<Pair<Int, Int>> {
        return analysis.beats.flatMap { beat ->
            beat.neighbors.map { edge -> edge.src.which to edge.dest.which }
        }
    }

    private fun vector(seed: Double): JsonArray {
        return JsonArray(List(12) { index -> JsonPrimitive(seed + index * 0.01) })
    }

    private fun quantaArray(count: Int, duration: Double, confidence: Double): JsonArray {
        return JsonArray((0 until count).map { i ->
            JsonObject(
                mapOf(
                    "start" to JsonPrimitive(i * duration),
                    "duration" to JsonPrimitive(duration),
                    "confidence" to JsonPrimitive(confidence)
                )
            )
        })
    }

    // Mirrors the web engine's happyPathAnalysis fixture: 12 beats whose segments repeat
    // a 4-beat phrase, so matching phrase positions produce similar branch candidates.
    private fun makeHappyPathAnalysisPayload(): JsonElement {
        val sections = JsonArray(
            listOf(1.0, 0.92, 0.88).mapIndexed { i, confidence ->
                JsonObject(
                    mapOf(
                        "start" to JsonPrimitive(i * 4.0),
                        "duration" to JsonPrimitive(4.0),
                        "confidence" to JsonPrimitive(confidence)
                    )
                )
            }
        )
        val bars = JsonArray(
            listOf(0.86, 0.82, 0.79).mapIndexed { i, confidence ->
                JsonObject(
                    mapOf(
                        "start" to JsonPrimitive(i * 4.0),
                        "duration" to JsonPrimitive(4.0),
                        "confidence" to JsonPrimitive(confidence)
                    )
                )
            }
        )
        val segments = JsonArray((0 until 12).map { i ->
            val phrase = i % 4
            JsonObject(
                mapOf(
                    "start" to JsonPrimitive(i.toDouble()),
                    "duration" to JsonPrimitive(1.0),
                    "confidence" to JsonPrimitive(0.62 + phrase * 0.02),
                    "loudness_start" to JsonPrimitive(-22.0 + phrase),
                    "loudness_max" to JsonPrimitive(-8.0 + phrase * 0.4),
                    "loudness_max_time" to JsonPrimitive(0.12),
                    "pitches" to vector(0.2 + phrase * 0.03),
                    "timbre" to vector(1.0 + phrase * 0.05)
                )
            )
        })

        return JsonObject(
            mapOf(
                "sections" to sections,
                "bars" to bars,
                "beats" to quantaArray(12, 1.0, 0.72),
                "tatums" to quantaArray(24, 0.5, 0.63),
                "segments" to segments,
                "track" to JsonObject(
                    mapOf(
                        "duration" to JsonPrimitive(12.0),
                        "tempo" to JsonPrimitive(120.0),
                        "time_signature" to JsonPrimitive(4.0)
                    )
                )
            )
        )
    }

    // 24 beats in 6 four-beat bars. Timbre seeds pair up adjacent bars, so every beat has
    // a near-identical partner 4 beats away (a short branch at distance ~0) while all
    // longer same-position matches sit near distance 40. With minLongBranch = 8 the long
    // filter discards every cheap branch, so a density sweep that honors the filter would
    // escalate the auto threshold until the ~40-distance edges qualify — flooding the
    // graph with branches the moment the filter turns on.
    private fun makeThresholdEscalationPayload(): JsonElement {
        val beatCount = 24
        val barTimbreSeeds = listOf(0.0, 0.1, 12.0, 12.1, 24.0, 24.1)
        val sections = JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "start" to JsonPrimitive(0.0),
                        "duration" to JsonPrimitive(beatCount.toDouble()),
                        "confidence" to JsonPrimitive(1.0)
                    )
                )
            )
        )
        val bars = JsonArray((0 until beatCount / 4).map { b ->
            JsonObject(
                mapOf(
                    "start" to JsonPrimitive(b * 4.0),
                    "duration" to JsonPrimitive(4.0),
                    "confidence" to JsonPrimitive(0.8)
                )
            )
        })
        val segments = JsonArray((0 until beatCount).map { i ->
            val bar = i / 4
            JsonObject(
                mapOf(
                    "start" to JsonPrimitive(i.toDouble()),
                    "duration" to JsonPrimitive(1.0),
                    "confidence" to JsonPrimitive(0.6),
                    "loudness_start" to JsonPrimitive(-20.0),
                    "loudness_max" to JsonPrimitive(-6.0),
                    "loudness_max_time" to JsonPrimitive(0.12),
                    "pitches" to vector(0.2),
                    "timbre" to vector(1.0 + barTimbreSeeds[bar])
                )
            )
        })

        return JsonObject(
            mapOf(
                "sections" to sections,
                "bars" to bars,
                "beats" to quantaArray(beatCount, 1.0, 0.72),
                "tatums" to quantaArray(beatCount * 2, 0.5, 0.63),
                "segments" to segments,
                "track" to JsonObject(
                    mapOf(
                        "duration" to JsonPrimitive(beatCount.toDouble()),
                        "tempo" to JsonPrimitive(120.0),
                        "time_signature" to JsonPrimitive(4.0)
                    )
                )
            )
        )
    }

    private fun makeAnalysisPayload(count: Int = 4): JsonElement {
        val sections = JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "start" to JsonPrimitive(0.0),
                        "duration" to JsonPrimitive(count.toDouble()),
                        "confidence" to JsonPrimitive(1.0)
                    )
                )
            )
        )
        val bars = JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "start" to JsonPrimitive(0.0),
                        "duration" to JsonPrimitive(2.0),
                        "confidence" to JsonPrimitive(0.8)
                    )
                ),
                JsonObject(
                    mapOf(
                        "start" to JsonPrimitive(2.0),
                        "duration" to JsonPrimitive(2.0),
                        "confidence" to JsonPrimitive(0.8)
                    )
                )
            )
        )
        val beats = JsonArray((0 until count).map { i ->
            JsonObject(
                mapOf(
                    "start" to JsonPrimitive(i.toDouble()),
                    "duration" to JsonPrimitive(1.0),
                    "confidence" to JsonPrimitive(0.6)
                )
            )
        })
        val tatums = JsonArray((0 until count).map { i ->
            JsonObject(
                mapOf(
                    "start" to JsonPrimitive(i * 0.5),
                    "duration" to JsonPrimitive(0.5),
                    "confidence" to JsonPrimitive(0.5)
                )
            )
        })
        val segments = JsonArray((0 until count).map { i ->
            JsonObject(
                mapOf(
                    "start" to JsonPrimitive(i.toDouble()),
                    "duration" to JsonPrimitive(1.0),
                    "confidence" to JsonPrimitive(0.4),
                    "loudness_start" to JsonPrimitive(-20 + i),
                    "loudness_max" to JsonPrimitive(-5 + i * 0.2),
                    "loudness_max_time" to JsonPrimitive(0.2),
                    "pitches" to JsonArray(List(12) { JsonPrimitive(0.5 + i * 0.01) }),
                    "timbre" to JsonArray(List(12) { JsonPrimitive(1.0 + i * 0.1) })
                )
            )
        })

        return JsonObject(
            mapOf(
                "sections" to sections,
                "bars" to bars,
                "beats" to beats,
                "tatums" to tatums,
                "segments" to segments,
                "track" to JsonObject(mapOf("duration" to JsonPrimitive(count.toDouble())))
            )
        )
    }
}
