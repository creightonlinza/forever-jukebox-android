package com.foreverjukebox.app.data

import kotlinx.serialization.Serializable

@Serializable
data class ServerAppConfig(
    val allowFavoritesSync: Boolean = false,
    val maxFavorites: Int = DEFAULT_MAX_FAVORITES,
    val maxTrackLength: Double? = null
)
