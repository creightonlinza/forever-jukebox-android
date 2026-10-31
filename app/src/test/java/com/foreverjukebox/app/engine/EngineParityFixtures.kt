package com.foreverjukebox.app.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal fun loadEngineParityFixture(name: String): JsonObject {
    val resourcePath = "engine-parity/$name"
    val stream = Thread.currentThread().contextClassLoader
        ?.getResourceAsStream(resourcePath)
        ?: EngineParityFixtures::class.java.classLoader?.getResourceAsStream(resourcePath)
        ?: error("Could not find test resource /$resourcePath")
    return stream.bufferedReader().use { reader ->
        Json.parseToJsonElement(reader.readText()).jsonObject
    }
}

/** One-second beats starting at [leadIn], with one flat segment per beat. */
internal fun makeAnalysisPayload(count: Int, leadIn: Double = 0.0): JsonElement {
    val beats = JsonArray((0 until count).map { i ->
        JsonObject(
            mapOf(
                "start" to JsonPrimitive(i + leadIn),
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

private object EngineParityFixtures
