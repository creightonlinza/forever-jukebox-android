package com.foreverjukebox.app.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonBuilder
import kotlinx.serialization.json.JsonDecoder

/**
 * Json configuration for user data that must survive content written by newer app or web versions:
 * unknown keys are skipped, and unknown enum values or nulls fall back to the property default
 * instead of failing the whole payload.
 */
fun tolerantJson(configure: JsonBuilder.() -> Unit = {}): Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    configure()
}

/**
 * List serializer that drops elements which fail to decode instead of rejecting the whole list.
 * Encoding is unchanged. Non-JSON formats fall back to the plain list serializer.
 */
open class LenientListSerializer<T>(private val element: KSerializer<T>) : KSerializer<List<T>> {
    private val delegate = ListSerializer(element)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<T>) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<T> {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val array = jsonDecoder.decodeJsonElement() as? JsonArray ?: return emptyList()
        return decodeLenientList(jsonDecoder.json, element, array).items
    }
}

/** The elements of a leniently decoded array and, for each, its index in the original array. */
class LenientListResult<T>(val items: List<T>, val keptIndices: List<Int>) {
    /**
     * Where an index into the original array points after the drops: the same entry when it
     * survived, otherwise the surviving entry that followed it. Negative indices (sentinels)
     * pass through; null when nothing survived.
     */
    fun remapIndex(index: Int): Int? {
        if (index < 0) return index
        if (items.isEmpty()) return null
        val kept = keptIndices.indexOf(index)
        if (kept >= 0) return kept
        return keptIndices.count { it < index }.coerceAtMost(items.lastIndex)
    }
}

/** Decodes each element of [array] as [element], dropping the ones that fail. */
fun <T> decodeLenientList(json: Json, element: KSerializer<T>, array: JsonArray): LenientListResult<T> {
    val items = mutableListOf<T>()
    val keptIndices = mutableListOf<Int>()
    array.forEachIndexed { index, item ->
        val decoded = try {
            json.decodeFromJsonElement(element, item)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException; both mean the entry is unusable.
            return@forEachIndexed
        }
        items += decoded
        keptIndices += index
    }
    return LenientListResult(items, keptIndices)
}

object LenientFavoriteTrackListSerializer : LenientListSerializer<FavoriteTrack>(FavoriteTrack.serializer())

object LenientSavedPlaylistTrackListSerializer :
    LenientListSerializer<SavedPlaylistTrack>(SavedPlaylistTrack.serializer())
