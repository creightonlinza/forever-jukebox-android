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

internal fun sortLocalCachedTracksByTitle(
    tracks: List<LocalCachedTrack>,
    ascending: Boolean
): List<LocalCachedTrack> {
    if (tracks.size < 2) return tracks

    val byTitle = tracks.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER) { it.title.ifBlank { "Untitled" } }
    )
    return if (ascending) byTitle else byTitle.asReversed()
}
