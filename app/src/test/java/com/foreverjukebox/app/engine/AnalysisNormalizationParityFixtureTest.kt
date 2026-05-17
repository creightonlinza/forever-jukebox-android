package com.foreverjukebox.app.engine

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnalysisNormalizationParityFixtureTest {

    @Test
    fun matchesSharedValidNormalizationCases() {
        val root = loadEngineParityFixture("analysis-normalization-cases.json")
        val cases = root["valid_cases"]!!.jsonArray

        for (testCaseElement in cases) {
            val testCase = testCaseElement.jsonObject
            val id = testCase["id"]!!.jsonPrimitive.content
            val actual = summarize(normalizeAnalysis(testCase["input"]!!))
            val expected = parseSummary(testCase["expected"]!!.jsonObject)

            assertEquals("$id: normalized analysis summary", expected, actual)
        }
    }

    @Test
    fun matchesSharedInvalidNormalizationCases() {
        val root = loadEngineParityFixture("analysis-normalization-cases.json")
        val cases = root["invalid_cases"]!!.jsonArray

        for (testCaseElement in cases) {
            val testCase = testCaseElement.jsonObject
            val id = testCase["id"]!!.jsonPrimitive.content
            val expectedError = testCase["expected_error"]!!.jsonPrimitive.content

            try {
                normalizeAnalysis(testCase["input"]!!)
                fail("$id: expected normalization to throw")
            } catch (error: IllegalArgumentException) {
                assertTrue(
                    "$id: expected error message to contain <$expectedError> but was <${error.message}>",
                    error.message?.contains(expectedError) == true
                )
            }
        }
    }

    private fun summarize(analysis: TrackAnalysis): NormalizationSummary {
        return NormalizationSummary(
            track = analysis.track?.let { track ->
                TrackSummary(
                    title = track.title,
                    artist = track.artist,
                    duration = track.duration,
                    tempo = track.tempo,
                    timeSignature = track.timeSignature
                )
            },
            beatStarts = analysis.beats.map { it.start },
            beatDurations = analysis.beats.map { it.duration },
            beatConfidences = analysis.beats.map { it.confidence },
            beatPrev = analysis.beats.map { it.prev?.which },
            beatNext = analysis.beats.map { it.next?.which },
            beatParents = analysis.beats.map { it.parent?.which },
            beatIndexInParent = analysis.beats.map { it.indexInParent },
            barChildren = analysis.bars.map { bar -> bar.children.map { child -> child.which } },
            beatOseg = analysis.beats.map { it.oseg?.which },
            beatOverlaps = analysis.beats.map { beat ->
                beat.overlappingSegments.map { segment -> segment.which }
            }
        )
    }

    private fun parseSummary(expected: JsonObject): NormalizationSummary {
        return NormalizationSummary(
            track = expected["track"]?.jsonObject?.let(::parseTrackSummary),
            beatStarts = expected.doubleList("beat_starts"),
            beatDurations = expected.doubleList("beat_durations"),
            beatConfidences = expected.nullableDoubleList("beat_confidences"),
            beatPrev = expected.nullableIntList("beat_prev"),
            beatNext = expected.nullableIntList("beat_next"),
            beatParents = expected.nullableIntList("beat_parents"),
            beatIndexInParent = expected.nullableIntList("beat_index_in_parent"),
            barChildren = expected.intListList("bar_children"),
            beatOseg = expected.nullableIntList("beat_oseg"),
            beatOverlaps = expected.intListList("beat_overlaps")
        )
    }

    private fun parseTrackSummary(track: JsonObject): TrackSummary {
        return TrackSummary(
            title = track["title"]?.jsonPrimitive?.contentOrNull,
            artist = track["artist"]?.jsonPrimitive?.contentOrNull,
            duration = track["duration"]?.jsonPrimitive?.double,
            tempo = track["tempo"]?.jsonPrimitive?.double,
            timeSignature = track["time_signature"]?.jsonPrimitive?.double
        )
    }

    private fun JsonObject.doubleList(key: String): List<Double> {
        return requireNotNull(this[key]) { "Missing $key" }.jsonArray.map { it.jsonPrimitive.double }
    }

    private fun JsonObject.nullableDoubleList(key: String): List<Double?> {
        return requireNotNull(this[key]) { "Missing $key" }.jsonArray.map { element ->
            if (element is JsonNull) null else element.jsonPrimitive.double
        }
    }

    private fun JsonObject.nullableIntList(key: String): List<Int?> {
        return requireNotNull(this[key]) { "Missing $key" }.jsonArray.map { element ->
            if (element is JsonNull) null else element.jsonPrimitive.content.toInt()
        }
    }

    private fun JsonObject.intListList(key: String): List<List<Int>> {
        return requireNotNull(this[key]) { "Missing $key" }.jsonArray.map { list ->
            list.jsonArray.map { it.jsonPrimitive.content.toInt() }
        }
    }

    private data class NormalizationSummary(
        val track: TrackSummary?,
        val beatStarts: List<Double>,
        val beatDurations: List<Double>,
        val beatConfidences: List<Double?>,
        val beatPrev: List<Int?>,
        val beatNext: List<Int?>,
        val beatParents: List<Int?>,
        val beatIndexInParent: List<Int?>,
        val barChildren: List<List<Int>>,
        val beatOseg: List<Int?>,
        val beatOverlaps: List<List<Int>>
    )

    private data class TrackSummary(
        val title: String? = null,
        val artist: String? = null,
        val duration: Double? = null,
        val tempo: Double? = null,
        val timeSignature: Double? = null
    )
}
