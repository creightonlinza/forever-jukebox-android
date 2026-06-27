package com.foreverjukebox.app.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.foreverjukebox.app.data.TOP_SONGS_LIMIT

@Composable
fun ServerTabContent(
    tabId: TabId,
    state: UiState,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    when (tabId) {
        TabId.Top -> TopSongsPanel(
            items = state.search.topSongs,
            trendingItems = state.search.trendingSongs,
            recentItems = state.search.recentSongs,
            favorites = state.favorites,
            loading = state.search.topSongsLoading,
            topSongsErrorMessage = state.search.topSongsErrorMessage,
            trendingLoading = state.search.trendingSongsLoading,
            trendingErrorMessage = state.search.trendingSongsErrorMessage,
            recentLoading = state.search.recentSongsLoading,
            recentErrorMessage = state.search.recentSongsErrorMessage,
            favoritesLoading = state.favoritesSyncLoading,
            topSongsLimit = TOP_SONGS_LIMIT,
            activeTab = state.topSongsTab,
            onTabSelected = viewModel::setTopSongsTab,
            onRefreshTopSongs = viewModel::refreshTopSongs,
            onRefreshTrendingSongs = viewModel::refreshTrendingSongs,
            onRefreshRecentSongs = viewModel::refreshRecentSongs,
            onRefreshFavorites = viewModel::refreshFavoritesFromSync,
            onSelect = { id, title, artist, tuningParams ->
                viewModel.selectServerPlaylistTrack(id, title, artist, tuningParams)
            },
            onLongSelect = { id, title, artist, tuningParams ->
                viewModel.addServerTrackToPlaylist(id, title, artist, tuningParams)
            },
            onFavoriteSelect = { id, title, artist, tuningParams, playMode ->
                viewModel.selectFavoriteTrack(id, title, artist, tuningParams, playMode)
            },
            onFavoriteLongSelect = { id, title, artist, tuningParams, playMode ->
                viewModel.addFavoriteTrackToPlaylist(id, title, artist, tuningParams, playMode)
            },
            onRemoveFavorite = viewModel::removeFavorite,
            favoritesSortKey = state.favoritesSortKey,
            favoritesSortDirection = state.favoritesSortDirection,
            onFavoritesSortChange = viewModel::setFavoritesSort,
            favoritesSyncCode = state.favoritesSyncCode,
            allowFavoritesSync = state.allowFavoritesSync,
            onRefreshSync = viewModel::refreshFavoritesFromSync,
            onCreateSync = viewModel::createFavoritesSyncCode,
            onFetchSync = viewModel::fetchFavoritesPreview,
            onApplySync = viewModel::applyFavoritesSync
        )
        TabId.Search -> SearchPanel(
            state = state,
            onSearch = viewModel::runSpotifySearch,
            onSpotifySelect = viewModel::selectSpotifyTrack,
            onYoutubeSelect = viewModel::selectYoutubeTrack,
            onOpenYoutube = { videoId ->
                runCatching {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        youtubeWatchUrl(videoId).toUri()
                    )
                    context.startActivity(intent)
                }
            }
        )
        TabId.Input,
        TabId.Play,
        TabId.Faq -> Unit
    }
}
