package com.foreverjukebox.app.ui

/**
 * Usage analytics events shared with the web app's GA4 event dictionary: play,
 * search, select_track, favorite, share, upload. Event names, parameter names,
 * and string values must stay identical to the web app so both platforms
 * aggregate under the same GA4 property. All parameter values are strings;
 * null/blank parameters are omitted (e.g. Spotify search picks have no track
 * id yet).
 */
interface AnalyticsGateway {
    fun logPlay(mode: String, trackId: String, trackTitle: String?)
    fun logSearch(searchTerm: String)
    fun logSelectTrack(source: String, trackId: String?, trackTitle: String?)
    fun logFavorite(trackId: String, trackTitle: String?)
    fun logShare(trackId: String)
    fun logUpload(method: String)
}

fun analyticsPlayMode(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.Jukebox -> "jukebox"
    PlaybackMode.Autocanonizer -> "autocanonizer"
}

fun analyticsSelectSource(tab: TopSongsTab): String = when (tab) {
    TopSongsTab.TopSongs -> "top"
    TopSongsTab.Trending -> "trending"
    TopSongsTab.Recent -> "recent"
    TopSongsTab.Favorites -> "favorites"
}

// Search results are titled "Name — Artist" on web; list picks use the plain title.
fun analyticsSearchResultTitle(name: String?, artist: String?): String? {
    val trimmedName = name?.trim().orEmpty()
    val trimmedArtist = artist?.trim().orEmpty()
    return when {
        trimmedName.isEmpty() -> null
        trimmedArtist.isEmpty() -> trimmedName
        else -> "$trimmedName — $trimmedArtist"
    }
}

fun PlaybackState.analyticsPlayTrackId(): String? =
    shareTrackIdOrNull() ?: lastYouTubeId?.trim()?.takeIf { it.isNotBlank() }
