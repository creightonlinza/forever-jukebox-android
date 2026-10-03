package com.foreverjukebox.app.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
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
        return array.mapNotNull { item ->
            try {
                jsonDecoder.json.decodeFromJsonElement(element, item)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

object LenientFavoriteTrackListSerializer : LenientListSerializer<FavoriteTrack>(FavoriteTrack.serializer())

object LenientSavedPlaylistTrackListSerializer :
    LenientListSerializer<SavedPlaylistTrack>(SavedPlaylistTrack.serializer())
