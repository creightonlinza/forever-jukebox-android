package com.foreverjukebox.app.ui

import kotlinx.coroutines.CoroutineScope

fun createRemoteSearchController(
    scope: CoroutineScope,
    serverGateway: ServerGateway,
    getState: () -> UiState,
    updateSearchState: ((SearchState) -> SearchState) -> Unit,
    setSearchQuery: (String) -> Unit,
    logError: (String, Throwable) -> Unit
): RemoteSearchController = PlayRemoteSearchController

private object PlayRemoteSearchController : RemoteSearchController {
    override fun resetRuntimeState() = Unit
    override fun onTopTabActivated() = Unit
    override fun onTopSongsTabSelected(tab: TopSongsTab) = Unit
    override fun maybeRefreshForState(currentState: UiState) = Unit
    override fun refreshTopSongs() = Unit
    override fun refreshRecentSongs() = Unit
    override fun refreshTrendingSongs() = Unit
    override fun runSpotifySearch(query: String) = Unit
    override fun fetchYoutubeMatches(name: String, artist: String, duration: Double) = Unit
}
