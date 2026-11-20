package com.foreverjukebox.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.ui.onboarding.OnboardingHost

@Composable
fun ForeverJukeboxApp(viewModel: MainViewModel) {
    // The measured window picks the layout, so the first frame after a resize already has the right one.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        AppContent(
            viewModel = viewModel,
            twoPane = isTwoPaneWindow(maxWidth.value, maxHeight.value),
            leftPaneWidth = leftPaneWidthDp(maxWidth.value).dp
        )
    }
}

@Composable
private fun AppContent(viewModel: MainViewModel, twoPane: Boolean, leftPaneWidth: Dp) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val fullscreenVisualizationAllowed = shouldShowFullscreenVisualization(state.playback)
    var showSleepTimer by rememberSaveable { mutableStateOf(false) }
    // Held above the panes so an open playlist stays through a layout change that hides the Listen pane.
    var showPlaylist by rememberSaveable { mutableStateOf(false) }
    // Saveable so an open feedback dialog and its typed text survive activity recreation.
    var showFeedback by rememberSaveable { mutableStateOf(false) }
    val tabStateHolder = rememberSaveableStateHolder()
    ForeverJukeboxTheme(mode = state.themeMode) {
        LaunchedEffect(state.fullscreenVisualizationVisible, fullscreenVisualizationAllowed) {
            if (state.fullscreenVisualizationVisible && !fullscreenVisualizationAllowed) {
                viewModel.closeFullscreenVisualization()
            }
        }
        LaunchedEffect(twoPane) { viewModel.setTwoPane(twoPane) }
        LaunchedEffect(showPlaylist, state.playlist) {
            if (showPlaylist && !shouldShowPlaylistControls(state.playlist)) showPlaylist = false
        }
        OnboardingHost(
            activeTab = state.activeTab,
            twoPane = twoPane,
            suppressed = state.fullscreenVisualizationVisible
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .windowInsetsPadding(
                            // Two panes span a landscape window, so a side cutout would cover a pane edge.
                            if (twoPane) {
                                WindowInsets.navigationBars
                                    .union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                            } else {
                                WindowInsets.navigationBars
                            }
                        )
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
                            onCastDebugOverlayChange = viewModel::setCastDebugOverlayEnabled,
                            onAppModeChange = viewModel::setAppMode,
                            onRefreshCacheSize = viewModel::refreshCacheSize,
                            onClearCache = viewModel::clearCache,
                            onCastSessionStarted = {},
                            onOpenSleepTimer = { showSleepTimer = true },
                            onOpenWhatsNew = viewModel::showWhatsNewFromSettings,
                            onOpenFeedback = { showFeedback = true }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TabArea(
                            state = state,
                            viewModel = viewModel,
                            tabStateHolder = tabStateHolder,
                            twoPane = twoPane,
                            leftPaneWidth = leftPaneWidth,
                            onOpenPlaylist = { showPlaylist = true }
                        )
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
                    if (showPlaylist && shouldShowPlaylistControls(state.playlist)) {
                        PlaylistDialog(
                            playlist = state.playlist,
                            onSelect = { index ->
                                showPlaylist = false
                                viewModel.selectPlaylistDialogTrack(index)
                            },
                            onRemove = viewModel::removePlaylistTrack,
                            onMove = viewModel::movePlaylistTrack,
                            onClear = viewModel::clearPlaylist,
                            onClose = { showPlaylist = false }
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
                    if (showFeedback) {
                        FeedbackDialog(
                            serverMode = state.appMode == AppMode.Server,
                            onSubmit = viewModel::submitFeedback,
                            onDismiss = { showFeedback = false }
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
                        onSetPlaybackMode = viewModel::selectPlaybackMode,
                        onSetVisualization = viewModel::setActiveVisualization,
                        onSelectBeat = viewModel::selectBeat,
                        onSelectWubMachinePosition = viewModel::selectWubMachinePosition,
                        onSetWubMachineLoop = viewModel::setWubMachineLoop,
                        onSkipPrevious = viewModel::skipToPreviousPlaylistTrack,
                        onSkipNext = viewModel::skipToNextPlaylistTrack
                    )
                }
            }
        }
    }
}

// One tree for both layouts, so the tab body and PlayPanel keep their saveable state across a layout change.
@Composable
private fun ColumnScope.TabArea(
    state: UiState,
    viewModel: MainViewModel,
    tabStateHolder: SaveableStateHolder,
    twoPane: Boolean,
    leftPaneWidth: Dp,
    onOpenPlaylist: () -> Unit
) {
    val bodyTab = layoutBodyTab(
        activeTab = state.activeTab,
        twoPane = twoPane,
        reportedTwoPane = state.twoPane,
        hasListenContent = !isListenScreenIdle(state)
    )
    Box(modifier = Modifier.weight(1f, fill = true)) {
        Column(
            modifier = if (twoPane) Modifier.width(leftPaneWidth).fillMaxHeight() else Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.weight(1f, fill = true)) {
                if (bodyTab != null && bodyTab != TabId.Play) {
                    // Per-tab saveable state (list scroll positions, search queries) is kept
                    // while another tab is shown, so returning to a tab restores where it was.
                    tabStateHolder.SaveableStateProvider(bodyTab) {
                        BrowseTabBody(tabId = bodyTab, state = state, viewModel = viewModel)
                    }
                }
            }
            if (twoPane) {
                Spacer(modifier = Modifier.height(8.dp))
                BottomNavBar(state = state, onTabSelected = viewModel::setActiveTab, twoPane = true)
            }
        }
        if (twoPane || bodyTab == TabId.Play) {
            val paneStart = if (twoPane) leftPaneWidth + TwoPaneGap else 0.dp
            Column(modifier = Modifier.fillMaxSize().padding(start = paneStart)) {
                Box(modifier = Modifier.weight(1f, fill = true)) {
                    tabStateHolder.SaveableStateProvider(TabId.Play) {
                        // Dialog flags saved before process death would open over
                        // the next track; the fresh view model has none loaded.
                        key(viewModel.instanceId) {
                            PlayPanel(state = state, viewModel = viewModel, onOpenPlaylist = onOpenPlaylist)
                        }
                    }
                }
                // The bar holds the transport controls, so two-pane mode keeps it under the Listen pane.
                if (twoPane) TransportBar(state, viewModel)
            }
        }
    }
    if (!twoPane) {
        TransportBar(state, viewModel)
        Spacer(modifier = Modifier.height(8.dp))
        BottomNavBar(
            state = state,
            onTabSelected = viewModel::setActiveTab
        )
    }
}

@Composable
private fun TransportBar(state: UiState, viewModel: MainViewModel) {
    if (!shouldShowPlaybackBar(state.playback)) return
    Spacer(modifier = Modifier.height(8.dp))
    PlaybackBar(
        state = state,
        onTogglePlayback = viewModel::togglePlayback,
        onSkipPrevious = viewModel::skipToPreviousPlaylistTrack,
        onSkipNext = viewModel::skipToNextPlaylistTrack,
        onOpenPlayTab = { viewModel.setActiveTab(TabId.Play) }
    )
}

@Composable
private fun BrowseTabBody(tabId: TabId, state: UiState, viewModel: MainViewModel) {
    when (tabId) {
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
            tabId = tabId,
            state = state,
            viewModel = viewModel
        )
        TabId.Faq -> FaqPanel()
        TabId.Play -> Unit
    }
}

// Tab for the one-column body or left pane (null: empty); anticipates the view model's pick until it takes the layout.
internal fun layoutBodyTab(
    activeTab: TabId,
    twoPane: Boolean,
    reportedTwoPane: Boolean,
    hasListenContent: Boolean
): TabId? = when {
    twoPane -> activeTab.takeIf { it != TabId.Play }
    reportedTwoPane && hasListenContent -> TabId.Play
    else -> activeTab
}

private val TwoPaneGap = 12.dp
