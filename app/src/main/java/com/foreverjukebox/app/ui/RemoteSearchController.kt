package com.foreverjukebox.app.ui

interface RemoteSearchController {
    fun resetRuntimeState()

    fun onTopTabActivated()

    fun onTopSongsTabSelected(tab: TopSongsTab)

    fun maybeRefreshForState(currentState: UiState)

    fun refreshTopSongs()

    fun refreshRecentSongs()

    fun refreshTrendingSongs()

    fun runSpotifySearch(query: String)

    fun fetchYoutubeMatches(name: String, artist: String, duration: Double)
}
