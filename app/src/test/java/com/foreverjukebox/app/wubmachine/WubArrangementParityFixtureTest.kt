package com.foreverjukebox.app.wubmachine

import com.foreverjukebox.app.engine.Segment
import com.foreverjukebox.app.engine.TrackMeta
import com.foreverjukebox.app.engine.loadEngineParityFixture
import com.foreverjukebox.app.engine.parseAnalysis
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

/**
 * Replays the shared Wub Machine arrangement cases (analysis + options -> plan) so the Kotlin
 * port and dubstepArrangement.ts keep producing the same remix. Mirrors
 * wubArrangementParityFixtures.test.ts in the web repo; regenerate the fixture there with
 * `npx vite-node scripts/generate-wub-arrangement-fixture.ts` in packages/shared.
 */
class WubArrangementParityFixtureTest {

    @Test
    fun matchesThePinnedPlanForEveryCase() {
        val root = loadEngineParityFixture(FIXTURE)
        val cases = root["cases"]!!.jsonArray
        assertTrue("fixture must hold cases", cases.isNotEmpty())
        for (element in cases) {
            val testCase = element.jsonObject
            val id = testCase["id"]!!.jsonPrimitive.content
            val analysis = analysisOf(testCase)
            val options = optionsOf(testCase["options"]?.jsonObject)
            val expected = testCase["expected"]!!.jsonObject

            val plan = planDubstepRemix(analysis, options)

            assertEquals("$id: tonic", expected["tonic"]!!.jsonPrimitive.int, plan.tonic)
            val expectedParts = expected["parts"]!!.jsonArray
            assertEquals("$id: part count", expectedParts.size, plan.parts.size)
            expectedParts.forEachIndexed { index, partElement ->
                val expectedPart = partElement.jsonObject
                val part = plan.parts[index]
                val label = "$id: part $index"
                assertEquals("$label kind", expectedPart["kind"]!!.jsonPrimitive.content, part.kind.wireName)
                assertEquals("$label label", expectedPart["label"]!!.jsonPrimitive.content, part.label)
                assertEquals(
                    "$label samples",
                    expectedPart["samples"]!!.jsonArray.map { it.jsonPrimitive.content },
                    part.samples
                )
                assertEquals("$label mix", expectedPart["mix"]!!.jsonPrimitive.double, part.mix, EPSILON)
                val expectedSlices = expectedPart["slices"]!!.jsonArray
                assertEquals("$label slice count", expectedSlices.size, part.slices.size)
                expectedSlices.forEachIndexed { sliceIndex, sliceElement ->
                    val values = sliceElement.jsonArray.map { it.jsonPrimitive.double }
                    val slice = part.slices[sliceIndex]
                    assertEquals("$label slice $sliceIndex start", values[0], slice.start, EPSILON)
                    assertEquals("$label slice $sliceIndex duration", values[1], slice.duration, EPSILON)
                    assertEquals("$label slice $sliceIndex beats", values[2], slice.beats, EPSILON)
                }
            }
        }
    }

    private fun analysisOf(testCase: JsonObject): DubstepAnalysis {
        val ref = testCase["analysis_ref"]?.jsonPrimitive?.content
        if (ref != null) {
            val source = loadEngineParityFixture(ref)
            return DubstepAnalysis.from(parseAnalysis(source["analysis"]!!))
        }
        val raw = testCase["analysis"]!!.jsonObject
        return DubstepAnalysis(
            sections = quanta(raw["sections"]),
            beats = quanta(raw["beats"]),
            segments = raw["segments"]!!.jsonArray.map { segmentOf(it.jsonObject) },
            bars = raw["bars"]?.takeUnless { it is JsonNull }?.let(::quanta),
            track = raw["track"]?.takeUnless { it is JsonNull }?.jsonObject?.let { track ->
                TrackMeta(
                    duration = track["duration"]?.jsonPrimitive?.doubleOrNull,
                    tempo = track["tempo"]?.jsonPrimitive?.doubleOrNull,
                    timeSignature = track["time_signature"]?.jsonPrimitive?.doubleOrNull
                )
            }
        )
    }

    private fun quanta(element: JsonElement?): List<Quantum> = element!!.jsonArray.map {
        val quantum = it.jsonObject
        Quantum(quantum["start"]!!.jsonPrimitive.double, quantum["duration"]!!.jsonPrimitive.double)
    }

    private fun segmentOf(raw: JsonObject): Segment = Segment(
        start = raw["start"]!!.jsonPrimitive.double,
        duration = raw["duration"]!!.jsonPrimitive.double,
        confidence = raw["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        loudnessStart = raw["loudness_start"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        loudnessMax = raw["loudness_max"]!!.jsonPrimitive.double,
        loudnessMaxTime = raw["loudness_max_time"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        pitches = raw["pitches"]!!.jsonArray.map { it.jsonPrimitive.double },
        timbre = raw["timbre"]?.jsonArray?.map { it.jsonPrimitive.double } ?: List(12) { 0.0 },
        which = raw["which"]?.jsonPrimitive?.intOrNull ?: 0
    )

    private fun optionsOf(raw: JsonObject?): DubstepPlanOptions {
        if (raw == null) return DubstepPlanOptions()
        fun flag(name: String) = raw[name]?.jsonPrimitive?.booleanOrNull == true
        return DubstepPlanOptions(
            contiguous = flag("contiguous"),
            tonic = raw["tonic"]?.jsonPrimitive?.intOrNull,
            sectionBudget = flag("sectionBudget"),
            skipQuiet = flag("skipQuiet"),
            contrast = flag("contrast"),
            fills = flag("fills")
        )
    }

    private companion object {
        const val FIXTURE = "wub-arrangement-cases.json"
        const val EPSILON = 1e-9
    }
}
