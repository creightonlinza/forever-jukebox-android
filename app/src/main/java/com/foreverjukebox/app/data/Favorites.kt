package com.foreverjukebox.app.data

import kotlinx.serialization.Serializable

@Serializable
enum class FavoriteSourceType {
    Youtube
}

@Serializable
data class FavoriteTrack(
    val uniqueSongId: String,
    val title: String,
    val artist: String,
    val duration: Double? = null,
    val artworkUrl: String? = null,
    val sourceType: FavoriteSourceType = FavoriteSourceType.Youtube
)
