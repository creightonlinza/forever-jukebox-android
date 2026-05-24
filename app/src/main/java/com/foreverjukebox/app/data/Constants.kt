package com.foreverjukebox.app.data

const val TOP_SONGS_LIMIT = 25
const val DEFAULT_MAX_FAVORITES = 150

fun sanitizeMaxFavorites(value: Int?): Int {
    return value?.takeIf { it > 0 } ?: DEFAULT_MAX_FAVORITES
}
