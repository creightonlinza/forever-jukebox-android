package com.foreverjukebox.app.ui

internal fun filterLocalCachedTracks(
    tracks: List<LocalCachedTrack>,
    rawQuery: String
): List<LocalCachedTrack> {
    val query = rawQuery.trim().lowercase()
    if (query.isEmpty()) return tracks

    return tracks.filter { track ->
        listOf(track.title, track.artist).any { value ->
            value.orEmpty().lowercase().contains(query)
        }
    }
}

internal fun sortLocalCachedTracksForDisplay(
    tracks: List<LocalCachedTrack>,
    sortKey: FavoriteSortKey,
    sortDirection: FavoriteSortDirection
): List<LocalCachedTrack> {
    if (tracks.size < 2) return tracks

    return tracks.sortedWith { left, right ->
        compareLocalCachedForDisplay(left, right, sortKey, sortDirection)
    }
}

private fun compareLocalCachedForDisplay(
    left: LocalCachedTrack,
    right: LocalCachedTrack,
    sortKey: FavoriteSortKey,
    sortDirection: FavoriteSortDirection
): Int {
    val secondarySortKey = when (sortKey) {
        FavoriteSortKey.Title -> FavoriteSortKey.Artist
        FavoriteSortKey.Artist -> FavoriteSortKey.Title
    }
    return compareLocalCachedField(left, right, sortKey, sortDirection)
        .takeIf { it != 0 }
        ?: compareLocalCachedField(left, right, secondarySortKey, sortDirection).takeIf { it != 0 }
        ?: String.CASE_INSENSITIVE_ORDER.compare(left.localId, right.localId)
}

private fun compareLocalCachedField(
    left: LocalCachedTrack,
    right: LocalCachedTrack,
    sortKey: FavoriteSortKey,
    sortDirection: FavoriteSortDirection
): Int {
    val result = String.CASE_INSENSITIVE_ORDER.compare(
        localCachedDisplayValue(left, sortKey),
        localCachedDisplayValue(right, sortKey)
    )
    return when (sortDirection) {
        FavoriteSortDirection.Ascending -> result
        FavoriteSortDirection.Descending -> -result
    }
}

private fun localCachedDisplayValue(track: LocalCachedTrack, sortKey: FavoriteSortKey): String {
    return when (sortKey) {
        FavoriteSortKey.Title -> track.title.ifBlank { "Untitled" }
        FavoriteSortKey.Artist -> favoriteDisplayArtist(track.artist)
    }
}
