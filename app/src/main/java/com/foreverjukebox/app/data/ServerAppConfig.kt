package com.foreverjukebox.app.data

import kotlinx.serialization.Serializable

@Serializable
data class ServerAppConfig(
    val allowFavoritesSync: Boolean = false,
    val maxFavorites: Int = DEFAULT_MAX_FAVORITES,
    val maxTrackLength: Double? = null,
    val allowUserUrl: Boolean = false,
    val allowUserUpload: Boolean = false,
    val maxUploadSize: Long? = null,
    val allowedUploadExts: List<String> = emptyList()
)
