package com.foreverjukebox.app.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.visualization.visualizationLabels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlayPanel(state: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val playback = state.playback
    val tuning = state.tuning
    val headerTitle = resolvePlaybackHeaderTitle(playback)
    val isFavorite = playback.lastYouTubeId?.let { id ->
        state.favorites.any { it.uniqueSongId == id }
    } == true
    val favoriteToggleInFlight = shouldShowListenFavoriteSpinner(state)
    var showTuning by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val vizLabels = visualizationLabels
    var jumpLine by remember { mutableStateOf(playback.jumpLine) }
    val onDeleteCurrentTrack: () -> Unit = {
        coroutineScope.launch {
            val deleted = viewModel.deleteCurrentJob()
            if (!deleted && viewModel.state.value.playback.deleteInFlight) {
                return@launch
            }
            val deletedText = if (!deleted) "Song can no longer be deleted" else "Song deleted"
            Toast.makeText(context, deletedText, Toast.LENGTH_SHORT).show()
        }
    }
    val onShare: () -> Unit = {
        val url = viewModel.buildShareUrl()
        if (url != null) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Forever Jukebox link"))
        }
    }
    val onToggleFavorite: () -> Unit = {
        if (playback.lastYouTubeId != null) {
            val result = viewModel.toggleFavoriteForCurrent()
            val message = when (result) {
                FavoriteToggleResult.LimitReached -> "Maximum favorites reached (100)."
                FavoriteToggleResult.Removed -> "Removed from Favorites"
                FavoriteToggleResult.Added -> "Added to Favorites"
                FavoriteToggleResult.BlockedInFlight,
                FavoriteToggleResult.NoTrack -> null
            }
            if (message != null) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val fullscreenLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@rememberLauncherForActivityResult
        }
        val nextIndex = result.data?.getIntExtra(
            FullscreenActivity.EXTRA_RESULT_VIZ_INDEX,
            playback.activeVizIndex
        ) ?: return@rememberLauncherForActivityResult
        val nextModeRaw = result.data?.getStringExtra(FullscreenActivity.EXTRA_RESULT_MODE)
        val nextMode = nextModeRaw?.let { raw ->
            runCatching { PlaybackMode.valueOf(raw) }.getOrNull()
        }
        if (nextMode != null) {
            viewModel.setPlaybackMode(nextMode)
        }
        viewModel.setActiveVisualization(nextIndex)
        viewModel.refreshPlaybackFromController()
    }

    LaunchedEffect(playback.jumpLine) {
        if (playback.jumpLine != null) {
            jumpLine = playback.jumpLine
        }
    }

    LaunchedEffect(jumpLine) {
        val current = jumpLine ?: return@LaunchedEffect
        delay(1100)
        if (jumpLine?.startedAt == current.startedAt) {
            jumpLine = null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!playback.isCasting && !playback.analysisErrorMessage.isNullOrBlank()) {
            ErrorStatus(
                message = playback.analysisErrorMessage,
                showRetry = state.appMode == AppMode.Server,
                onRetry = { viewModel.retryFailedLoad() }
            )
        } else if (!playback.isCasting && (playback.analysisInFlight || playback.analysisCalculating || playback.audioLoading)) {
            LoadingStatus(
                progress = playback.analysisProgress,
                label = when {
                    playback.analysisCalculating -> "Calculating pathways"
                    playback.analysisInFlight -> playback.analysisMessage ?: "Fetching audio"
                    playback.audioLoading -> "Fetching audio"
                    else -> null
                },
                showCancel = shouldShowLocalLoadingCancel(state.appMode, playback),
                onCancel = viewModel::cancelLocalAnalysis
            )
        }

        when (resolveListenContentMode(playback)) {
            ListenContentMode.Cast -> {
            CastListenScreen(
                playback = playback,
                appMode = state.appMode,
                headerTitle = headerTitle,
                vizLabels = vizLabels,
                isFavorite = isFavorite,
                onTogglePlayback = viewModel::togglePlayback,
                onOpenTuning = { showTuning = true },
                onOpenInfo = { showInfo = true },
                onDeleteCurrentTrack = onDeleteCurrentTrack,
                onShare = onShare,
                onToggleFavorite = onToggleFavorite,
                favoriteToggleInFlight = favoriteToggleInFlight,
                onSelectVisualization = viewModel::setActiveVisualization
            )
            }
            ListenContentMode.LocalReady -> {
            LocalListenScreen(
                playback = playback,
                appMode = state.appMode,
                tuning = tuning,
                headerTitle = headerTitle,
                vizLabels = vizLabels,
                jumpLine = jumpLine,
                isFavorite = isFavorite,
                onTogglePlayback = viewModel::togglePlayback,
                onOpenTuning = { showTuning = true },
                onOpenInfo = { showInfo = true },
                onDeleteCurrentTrack = onDeleteCurrentTrack,
                onShare = onShare,
                onToggleFavorite = onToggleFavorite,
                favoriteToggleInFlight = favoriteToggleInFlight,
                onSetPlaybackMode = viewModel::setPlaybackMode,
                onSetVisualization = viewModel::setActiveVisualization,
                onSetCanonizerFinishOutSong = viewModel::setCanonizerFinishOutSong,
                onSelectBeat = viewModel::selectBeat,
                onOpenFullscreen = {
                    val intent = Intent(context, FullscreenActivity::class.java)
                        .putExtra(FullscreenActivity.EXTRA_VIZ_INDEX, playback.activeVizIndex)
                        .putExtra(FullscreenActivity.EXTRA_MODE, playback.playMode.name)
                    fullscreenLauncher.launch(intent)
                }
            )
            }
            ListenContentMode.Empty -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(SurfaceShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "No song selected.",
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            ListenContentMode.None -> Unit
        }
    }

    if (showInfo && playback.playMode != PlaybackMode.Autocanonizer) {
        val totalBeats = if (playback.isCasting) {
            playback.castTotalBeats ?: 0
        } else {
            playback.vizData?.beats?.size ?: playback.castTotalBeats ?: 0
        }
        val totalBranches = if (playback.isCasting) {
            playback.castTotalBranches ?: 0
        } else {
            playback.vizData?.edges?.size ?: playback.castTotalBranches ?: 0
        }
        TrackInfoDialog(
            durationSeconds = playback.trackDurationSeconds,
            totalBeats = totalBeats,
            totalBranches = totalBranches,
            onClose = { showInfo = false }
        )
    }

    if (showTuning && playback.playMode != PlaybackMode.Autocanonizer) {
        TuningDialog(
            initialThreshold = tuning.threshold,
            initialMinProb = tuning.minProb,
            initialMaxProb = tuning.maxProb,
            initialRamp = tuning.ramp,
            initialHighlightAnchorBranch = tuning.highlightAnchorBranch,
            initialJustBackwards = tuning.justBackwards,
            initialJustLong = tuning.justLong,
            initialRemoveSequential = tuning.removeSequential,
            onDismiss = { showTuning = false },
            onReset = viewModel::resetTuningDefaults,
            onApply = viewModel::applyTuning
        )
    }
}
