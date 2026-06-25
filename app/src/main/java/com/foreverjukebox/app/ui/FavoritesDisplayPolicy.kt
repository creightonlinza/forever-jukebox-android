package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.FavoriteTrack

enum class FavoriteSortKey {
    Title,
    Artist
}

enum class FavoriteSortDirection {
    Ascending,
    Descending;

    fun toggled(): FavoriteSortDirection {
        return when (this) {
            Ascending -> Descending
            Descending -> Ascending
        }
    }
}

internal fun favoriteSortKeyFromString(raw: String?): FavoriteSortKey {
    return FavoriteSortKey.entries.firstOrNull { it.name == raw } ?: FavoriteSortKey.Title
}

internal fun favoriteSortDirectionFromString(raw: String?): FavoriteSortDirection {
    return FavoriteSortDirection.entries.firstOrNull { it.name == raw }
        ?: FavoriteSortDirection.Ascending
}

internal fun filterFavorites(favorites: List<FavoriteTrack>, rawQuery: String): List<FavoriteTrack> {
    val query = rawQuery.trim().lowercase()
    if (query.isEmpty()) return favorites

    return favorites.filter { favorite ->
        listOf(
            favorite.title,
            favorite.artist,
            favorite.uniqueSongId,
            favorite.sourceType?.name
        ).any { value ->
            value.orEmpty().lowercase().contains(query)
        }
    }
}

internal fun sortFavoritesForDisplay(
    favorites: List<FavoriteTrack>,
    sortKey: FavoriteSortKey,
    sortDirection: FavoriteSortDirection
): List<FavoriteTrack> {
    if (favorites.size < 2) return favorites

    return favorites.sortedWith { left, right ->
        compareFavoritesForDisplay(left, right, sortKey, sortDirection)
    }
}

internal fun favoriteDisplayArtist(rawArtist: String?): String {
    val trimmed = rawArtist.orEmpty().trim()
    return if (trimmed.equals("Unknown", ignoreCase = true)) "" else trimmed
}

private fun compareFavoritesForDisplay(
    left: FavoriteTrack,
    right: FavoriteTrack,
    sortKey: FavoriteSortKey,
    sortDirection: FavoriteSortDirection
): Int {
    val secondarySortKey = when (sortKey) {
        FavoriteSortKey.Title -> FavoriteSortKey.Artist
        FavoriteSortKey.Artist -> FavoriteSortKey.Title
    }
    return compareFavoriteField(left, right, sortKey, sortDirection)
        .takeIf { it != 0 }
        ?: compareFavoriteField(left, right, secondarySortKey, sortDirection).takeIf { it != 0 }
        ?: compareFavoriteIds(left.uniqueSongId, right.uniqueSongId)
}

private fun compareFavoriteField(
    left: FavoriteTrack,
    right: FavoriteTrack,
    sortKey: FavoriteSortKey,
    sortDirection: FavoriteSortDirection
): Int {
    val result = String.CASE_INSENSITIVE_ORDER.compare(
        favoriteDisplayValue(left, sortKey),
        favoriteDisplayValue(right, sortKey)
    )
    return when (sortDirection) {
        FavoriteSortDirection.Ascending -> result
        FavoriteSortDirection.Descending -> -result
    }
}

private fun favoriteDisplayValue(favorite: FavoriteTrack, sortKey: FavoriteSortKey): String {
    return when (sortKey) {
        FavoriteSortKey.Title -> favorite.title.ifBlank { "Untitled" }
        FavoriteSortKey.Artist -> favoriteDisplayArtist(favorite.artist)
    }
}

private fun compareFavoriteIds(leftId: String, rightId: String): Int {
    return String.CASE_INSENSITIVE_ORDER.compare(leftId, rightId)
        .takeIf { it != 0 }
        ?: leftId.compareTo(rightId)
}
