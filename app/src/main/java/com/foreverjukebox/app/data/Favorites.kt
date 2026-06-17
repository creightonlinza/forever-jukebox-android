package com.foreverjukebox.app.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FavoriteSourceType {
    @SerialName("youtube")
    Youtube,
    @SerialName("soundcloud")
    SoundCloud,
    @SerialName("bandcamp")
    Bandcamp,
    @SerialName("upload")
    @Suppress("unused")
    Upload
}

@Serializable
enum class FavoritePlayMode {
    @SerialName("jukebox")
    Jukebox,
    @SerialName("autocanonizer")
    Autocanonizer
}

@Serializable
data class FavoriteTrack(
    val uniqueSongId: String,
    val title: String,
    val artist: String,
    val duration: Double? = null,
    val sourceType: FavoriteSourceType? = null,
    val tuningParams: String? = null,
    // Play mode the track was favorited in; absent/null on legacy favorites,
    // which predate autocanonizer favorites and are treated as jukebox.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val playMode: FavoritePlayMode? = null
)
