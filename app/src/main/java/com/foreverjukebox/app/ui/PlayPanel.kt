package com.foreverjukebox.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.data.canonicalTrackId
import com.foreverjukebox.app.visualization.visualizationLabels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
@Suppress("AssignedValueIsNeverRead")
fun PlayPanel(state: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val playback = state.playback
    val tuning = state.tuning
    val headerTitle = resolvePlaybackHeaderTitle(playback)
    val loadingTrackMetadata = resolveLoadingTrackMetadata(
        playback = playback,
        localSelectedFileName = state.localSelectedFileName.takeIf { state.appMode == AppMode.Local }
    )
    val favoriteTargetIds = playback.reusableTrackIdsForMatching()
    val isFavorite = favoriteTargetIds.isNotEmpty() && state.favorites.any { favorite ->
        canonicalTrackId(favorite.uniqueSongId) in favoriteTargetIds
    }
    val favoriteToggleInFlight = shouldShowListenFavoriteSpinner(state)
    var showTuning by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var showDeleteTrackConfirm by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val vizLabels = visualizationLabels
    var jumpLine by remember { mutableStateOf(playback.jumpLine) }
    val confirmDeleteCurrentTrack: () -> Unit = {
        coroutineScope.launch {
            val deleted = viewModel.deleteCurrentJob()
            if (!deleted && viewModel.state.value.playback.deleteInFlight) {
                return@launch
            }
            val deletedText = if (!deleted) "Track can no longer be deleted" else "Track deleted"
            Toast.makeText(context, deletedText, Toast.LENGTH_SHORT).show()
        }
    }
    val onDeleteCurrentTrack: () -> Unit = {
        showDeleteTrackConfirm = true
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
        if (playback.shareTrackIdOrNull() != null) {
            val result = viewModel.toggleFavoriteForCurrent()
            val message = when (result) {
                FavoriteToggleResult.LimitReached -> "Maximum favorites reached (${state.maxFavorites})."
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

    LaunchedEffect(showPlaylist, state.playlist) {
        if (showPlaylist && !shouldShowPlaylistControls(state.playlist)) {
            showPlaylist = false
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
                    else -> "Fetching audio"
                },
                trackTitle = loadingTrackMetadata?.title,
                trackArtist = loadingTrackMetadata?.artist,
                playAfterLoaded = playback.playAfterLoaded,
                showPlayAfterLoaded = shouldShowPlayAfterLoadedOption(state.appMode, playback),
                onPlayAfterLoadedChange = viewModel::setPlayAfterLoaded,
                showCancel = shouldShowLocalLoadingCancel(state.appMode, playback),
                onCancel = viewModel::cancelLocalAnalysis
            )
        }

        when (resolveListenContentMode(playback)) {
            ListenContentMode.Cast -> {
            CastListenScreen(
                playback = playback,
                appMode = state.appMode,
                adminKey = state.adminKey,
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
                playlist = state.playlist,
                onOpenPlaylist = { showPlaylist = true },
                onSkipPrevious = viewModel::skipToPreviousPlaylistTrack,
                onSkipNext = viewModel::skipToNextPlaylistTrack,
                onSelectVisualization = viewModel::setActiveVisualization
            )
            }
            ListenContentMode.LocalReady -> {
            LocalListenScreen(
                playback = playback,
                appMode = state.appMode,
                adminKey = state.adminKey,
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
                playlist = state.playlist,
                onOpenPlaylist = { showPlaylist = true },
                onSkipPrevious = viewModel::skipToPreviousPlaylistTrack,
                onSkipNext = viewModel::skipToNextPlaylistTrack,
                onOpenFullscreen = viewModel::openFullscreenVisualization
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
                        "No track selected.",
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (shouldShowSavedPlaylistButton(state)) {
                        Button(
                            onClick = { showPlaylist = true },
                            colors = pillButtonColors(),
                            border = pillButtonBorder(),
                            shape = PillShape,
                            contentPadding = SmallButtonPadding,
                            modifier = Modifier.height(SmallButtonHeight)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.QueueMusic,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Saved Playlist", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            ListenContentMode.None -> Unit
        }
    }

    val canOpenCastReceiverDetails = playback.castReceiverDetailsReady()
    if (showInfo && playback.playMode != PlaybackMode.Autocanonizer && canOpenCastReceiverDetails) {
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

    if (showTuning && playback.playMode != PlaybackMode.Autocanonizer && canOpenCastReceiverDetails) {
        val audioModeOptions = if (playback.isCasting) {
            playback.castSupportedAudioModes
        } else {
            localAudioModeOptions
        }
        TuningDialog(
            initialThreshold = tuning.threshold,
            initialMinProb = tuning.minProb,
            initialMaxProb = tuning.maxProb,
            initialRamp = tuning.ramp,
            initialHighlightAnchorBranch = tuning.highlightAnchorBranch,
            initialJustBackwards = tuning.justBackwards,
            initialJustLong = tuning.justLong,
            initialRemoveSequential = tuning.removeSequential,
            initialAudioModeWireValue = if (playback.isCasting) {
                playback.castAudioModeWireValue
            } else {
                playback.jukeboxAudioMode.wireValue
            },
            audioModeOptions = audioModeOptions,
            isAudioModePickerEnabled = !playback.isCasting || audioModeOptions.isNotEmpty(),
            onDismiss = { showTuning = false },
            onReset = viewModel::resetTuningDefaults,
            onApply = viewModel::applyTuning
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
            onClear = viewModel::clearPlaylist,
            onClose = { showPlaylist = false }
        )
    }

    if (showDeleteTrackConfirm) {
        DeleteTrackDialog(
            onDismiss = { showDeleteTrackConfirm = false },
            onConfirm = {
                showDeleteTrackConfirm = false
                confirmDeleteCurrentTrack()
            }
        )
    }
}

@Composable
private fun PlaylistDialog(
    playlist: JukeboxPlaylistState,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Playlist") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(playlist.tracks) { index, track ->
                    val selected = index == playlist.currentIndex
                    val displayTitle = track.title?.takeIf { it.isNotBlank() } ?: "Untitled"
                    val displayArtist = track.artist?.takeIf { it.isNotBlank() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(SurfaceShape)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            )
                            .clickable(enabled = playlist.canSelectTrackAt(index)) {
                                onSelect(index)
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (displayArtist != null) {
                                Text(
                                    text = displayArtist,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (playlist.canRemoveTrackAt(index)) {
                            Spacer(modifier = Modifier.width(8.dp))
                            SquareIconButton(
                                onClick = { onRemove(index) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove from playlist",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onClear,
                    colors = pillButtonColors(),
                    border = pillButtonBorder(),
                    shape = PillShape,
                    contentPadding = SmallButtonPadding,
                    modifier = Modifier.height(SmallButtonHeight)
                ) {
                    Text("Clear", style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = onClose,
                    colors = pillButtonColors(),
                    border = pillButtonBorder(),
                    shape = PillShape,
                    contentPadding = SmallButtonPadding,
                    modifier = Modifier.height(SmallButtonHeight)
                ) {
                    Text("Close", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    )
}
