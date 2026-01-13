package com.foreverjukebox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ForeverJukeboxApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    ForeverJukeboxTheme(mode = state.themeMode) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(16.dp)
        ) {
            HeaderBar(
                state = state,
                onEditBaseUrl = { viewModel.setBaseUrl(it) },
                onThemeChange = viewModel::setThemeMode,
                onRefreshCacheSize = viewModel::refreshCacheSize,
                onClearCache = viewModel::clearCache,
                onTabSelected = viewModel::setActiveTab
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (state.activeTab) {
                TabId.Top -> TopSongsPanel(
                    items = state.search.topSongs,
                    favorites = state.favorites,
                    loading = state.search.topSongsLoading,
                    activeTab = state.topSongsTab,
                    onTabSelected = viewModel::setTopSongsTab,
                    onSelect = viewModel::loadTrackByYoutubeId,
                    onRemoveFavorite = viewModel::removeFavorite
                )
                TabId.Search -> SearchPanel(
                    state = state,
                    onSearch = viewModel::runSpotifySearch,
                    onSpotifySelect = viewModel::selectSpotifyTrack,
                    onYoutubeSelect = viewModel::startYoutubeAnalysis
                )
                TabId.Play -> PlayPanel(state = state, viewModel = viewModel)
                TabId.Faq -> FaqPanel()
            }
        }

        if (state.showBaseUrlPrompt) {
            BaseUrlDialog(
                initialValue = state.baseUrl,
                onSave = viewModel::setBaseUrl
            )
        }
    }
}
