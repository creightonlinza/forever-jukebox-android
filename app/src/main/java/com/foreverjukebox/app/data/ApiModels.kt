@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.foreverjukebox.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SpotifySearchItem(
    val id: String? = null,
    val name: String? = null,
    val artist: String? = null,
    val duration: Double? = null
)

@Serializable
data class YoutubeSearchItem(
    val id: String? = null,
    val title: String? = null,
    val duration: Double? = null
)

@Serializable
data class SearchResponse<T>(
    val items: List<T> = emptyList()
)

@Serializable
data class AnalysisStartRequest(
    @SerialName("youtube_id") val youtubeId: String,
    val title: String? = null,
    val artist: String? = null
)

@Serializable
data class AnalysisStartResponse(
    val id: String? = null,
    val status: String? = null,
    val progress: Double? = null
)

@Serializable
data class AnalysisResponse(
    val id: String? = null,
    val status: String? = null,
    val progress: Double? = null,
    @SerialName("youtube_id") val youtubeId: String? = null,
    val result: JsonElement? = null,
    val error: String? = null
)

@Serializable
data class TopSongItem(
    val id: String? = null,
    @SerialName("youtube_id") val youtubeId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    @SerialName("play_count") val playCount: Int? = null
)

@Serializable
data class TopSongsResponse(
    val items: List<TopSongItem> = emptyList()
)
