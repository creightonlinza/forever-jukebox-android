package com.foreverjukebox.app.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

private object EngineParityFixtures
