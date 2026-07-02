package com.foreverjukebox.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

@Composable
fun ForeverJukeboxApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val fullscreenVisualizationAllowed = shouldShowFullscreenVisualization(state.playback)
    var showSleepTimer by remember { mutableStateOf(false) }
    ForeverJukeboxTheme(mode = state.themeMode) {
        LaunchedEffect(state.fullscreenVisualizationVisible, fullscreenVisualizationAllowed) {
            if (state.fullscreenVisualizationVisible && !fullscreenVisualizationAllowed) {
                viewModel.closeFullscreenVisualization()
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                if (state.showAppModeGate) {
                    TitleOnlyHeaderBar()
                } else {
                    HeaderBar(
                        state = state,
                        onEditBaseUrl = { viewModel.setBaseUrl(it) },
                        onEditAdminKey = { viewModel.setAdminKey(it) },
                        onThemeChange = viewModel::setThemeMode,
                        onLoadingAudioFeedbackChange = viewModel::setLoadingAudioFeedbackEnabled,
                        onAppModeChange = viewModel::setAppMode,
                        onRefreshCacheSize = viewModel::refreshCacheSize,
                        onClearCache = viewModel::clearCache,
                        onTabSelected = viewModel::setActiveTab,
                        onCastSessionStarted = {},
                        onOpenSleepTimer = { showSleepTimer = true },
                        onOpenWhatsNew = viewModel::showWhatsNewFromSettings
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    when (state.activeTab) {
                        TabId.Input -> InputPanel(
                            state = state,
                            onOpenFile = viewModel::startLocalAnalysis,
                            onOpenCachedTrack = viewModel::selectLocalCachedPlaylistTrack,
                            onAddCachedTrackToPlaylist = viewModel::addLocalCachedTrackToPlaylist,
                            onDeleteCachedTrack = viewModel::deleteCachedLocalTrack,
                            onSortChange = viewModel::setLocalAnalysisSort
                        )
                        TabId.Top,
                        TabId.Search -> ServerTabContent(
                            tabId = state.activeTab,
                            state = state,
                            viewModel = viewModel
                        )
                        TabId.Play -> PlayPanel(state = state, viewModel = viewModel)
                        TabId.Faq -> FaqPanel()
                    }
                }
            }

            if (state.showAppModeGate) {
                AppModeDialog(
                    initialMode = defaultOnboardingMode,
                    initialValue = state.baseUrl,
                    onConfirm = viewModel::completeAppModeOnboarding
                )
            }

            if (!state.showAppModeGate) {
                state.versionUpdatePrompt?.let { prompt ->
                    VersionUpdateDialog(
                        latestVersion = prompt.latestVersion,
                        onDownload = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, prompt.downloadUrl.toUri())
                                context.startActivity(intent)
                            }
                        },
                        onClose = viewModel::dismissVersionUpdatePrompt
                    )
                }
                state.whatsNewPrompt?.let { prompt ->
                    WhatsNewDialog(
                        prompt = prompt,
                        onClose = viewModel::dismissWhatsNew
                    )
                }
                state.trackLengthLimitErrorMessage?.let { message ->
                    ErrorMessageDialog(
                        message = message,
                        onClose = viewModel::dismissTrackLengthLimitErrorDialog
                    )
                }
                state.localCachedTrackErrorMessage?.let { message ->
                    ErrorMessageDialog(
                        message = message,
                        onClose = viewModel::dismissLocalCachedTrackErrorDialog
                    )
                }
                if (showSleepTimer) {
                    SleepTimerDialog(
                        selectedOption = state.sleepTimer.selectedOption,
                        remainingMs = state.sleepTimer.remainingMs,
                        onDismiss = { showSleepTimer = false },
                        onSelectOption = viewModel::setSleepTimer
                    )
                }
            }

            if (
                state.fullscreenVisualizationVisible &&
                fullscreenVisualizationAllowed
            ) {
                FullscreenVisualizationScreen(
                    state = state,
                    onClose = viewModel::closeFullscreenVisualization,
                    onTogglePlayback = viewModel::togglePlayback,
                    onSetPlaybackMode = viewModel::setPlaybackMode,
                    onSetVisualization = viewModel::setActiveVisualization,
                    onSelectBeat = viewModel::selectBeat,
                    onSkipPrevious = viewModel::skipToPreviousPlaylistTrack,
                    onSkipNext = viewModel::skipToNextPlaylistTrack
                )
            }
        }
    }
}
