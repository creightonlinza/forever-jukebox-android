package com.foreverjukebox.app.data

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
    Upload
}

@Serializable
data class FavoriteTrack(
    val uniqueSongId: String,
    val title: String,
    val artist: String,
    val duration: Double? = null,
    val sourceType: FavoriteSourceType? = null,
    val tuningParams: String? = null
)

fun favoriteSourceTypeFromProvider(provider: String?): FavoriteSourceType {
    return when (sourceProviderFromRaw(provider)) {
        SOURCE_PROVIDER_SOUNDCLOUD -> FavoriteSourceType.SoundCloud
        SOURCE_PROVIDER_BANDCAMP -> FavoriteSourceType.Bandcamp
        SOURCE_PROVIDER_UPLOAD -> FavoriteSourceType.Upload
        else -> FavoriteSourceType.Youtube
    }
}
