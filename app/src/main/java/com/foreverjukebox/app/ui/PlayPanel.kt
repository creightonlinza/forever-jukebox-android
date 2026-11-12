package com.foreverjukebox.app.ui

import com.foreverjukebox.app.ui.loading.LoadingTask
import com.foreverjukebox.app.ui.loading.rememberPresentedLoading
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.engine.isCustomAnchorEdge
import com.foreverjukebox.app.ui.onboarding.OnboardingPlaybackReadyEffect
import com.foreverjukebox.app.visualization.visualizationLabels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
@Suppress("AssignedValueIsNeverRead")
fun PlayPanel(state: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val playback = state.playback
    val tuning = state.tuning
    val loadingTrackMetadata = resolveLoadingTrackMetadata(
        playback = playback,
        localSelectedFileName = state.localSelectedFileName.takeIf { state.appMode == AppMode.Local }
    )
    // Parses a track id per favorite, so it tracks the ids and the list rather than every frame.
    val currentFavorite = remember(state.favorites, playback.lastJobId, playback.lastYouTubeId) {
        favoriteForCurrentTrack(state)
    }
    val isFavorite = currentFavorite != null
    // Drift builds a tuning query string and parses two more, while the beat tick republishes
    // playback state throughout a track. Keyed on everything the check reads, so it recomputes
    // when the tuning or the favorite moves and not once per frame.
    val hasTuningDrift = remember(
        currentFavorite,
        tuning,
        playback.playMode,
        playback.isCasting,
        playback.castTotalBeats,
        playback.castAudioModeWireValue,
        playback.castAudioModeIntensity,
        playback.jukeboxAudioMode,
        playback.jukeboxAudioModeIntensity
    ) {
        hasFavoriteTuningDrift(state, currentFavorite)
    }
    val favoriteToggleInFlight = shouldShowListenFavoriteSpinner(state)
    var showTuning by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var showDeleteTrackConfirm by remember { mutableStateOf(false) }
    var reportJobId by remember { mutableStateOf<String?>(null) }
    var reportSending by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
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
    val onReportCurrentTrack: () -> Unit = {
        reportJobId = playback.lastJobId
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
                FavoriteToggleResult.Updated -> "Favorite tuning updated"
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

    LaunchedEffect(state.export.completedFileName) {
        val fileName = state.export.completedFileName ?: return@LaunchedEffect
        Toast.makeText(context, "Saved to Music as $fileName", Toast.LENGTH_LONG).show()
        showExport = false
        viewModel.consumeExportResult()
    }

    LaunchedEffect(state.export.errorMessage) {
        val message = state.export.errorMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        viewModel.consumeExportResult()
    }

    val presented = rememberPresentedLoading(liveLoading(playback))
    val loadingShown = presented.shown != null
    LaunchedEffect(loadingShown) {
        viewModel.setLoadingShown(loadingShown)
    }
    // Leaving the Play tab drops the screen, so playback is not hidden on the other tabs.
    DisposableEffect(viewModel) {
        onDispose { viewModel.setLoadingShown(false) }
    }
    OnboardingPlaybackReadyEffect(
        ready = !loadingShown &&
            playback.analysisErrorMessage.isNullOrBlank() &&
            resolveListenContentMode(playback) == ListenContentMode.LocalReady
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!playback.isCasting && !playback.analysisErrorMessage.isNullOrBlank()) {
            LoadingFailedStatus(
                message = playback.analysisErrorMessage,
                onRetry = if (shouldOfferInAppLoadRetry(state)) {
                    { viewModel.retryFailedLoad() }
                } else {
                    null
                }
            )
        } else if (presented.shown != null) {
            val shown = presented.shown
            // Finished work plays out its last step with nothing left to cancel.
            val onCancel: (() -> Unit)? = if (shown.finished) null else when (shown.task) {
                LoadingTask.Swing -> viewModel::resetAudioModeDefaults
                // Cancelling the render leaves the mode, as the web app does when the remix is abandoned.
                LoadingTask.WubMachine, LoadingTask.WubMachineWithSamples -> {
                    { viewModel.selectPlaybackMode(PlaybackMode.Jukebox) }
                }
                else -> viewModel::cancelLocalAnalysis.takeIf { shouldShowLocalLoadingCancel(state.appMode, playback) }
            }
            // Auto-play may already have taken the play request while a finished load plays out.
            val playStarted = shown.finished && playback.isRunning
            key(presented.presentation) {
                LoadingStatus(
                    loading = shown,
                    onFinished = presented.onFinished,
                    trackTitle = loadingTrackMetadata?.title,
                    trackArtist = loadingTrackMetadata?.artist,
                    playAfterLoaded = playback.playAfterLoaded || playStarted,
                    // The option stays while a finished load plays out its steps. A loaded track decoding
                    // its audio again already has a play request waiting.
                    showPlayAfterLoaded = !shown.task.mode && (
                        shown.finished ||
                            (!playback.analysisLoaded && shouldShowPlayAfterLoadedOption(state.appMode, playback))
                        ),
                    onPlayAfterLoadedChange = viewModel::setPlayAfterLoaded.takeIf { !playStarted },
                    showCancel = onCancel != null,
                    onCancel = onCancel ?: {}
                )
            }
        }

        val showSavedPlaylist = shouldShowSavedPlaylistButton(state)
        val resumeTitle = if (shouldShowContinueListeningButton(state)) {
            state.playlist.resumeTrack()?.let { track ->
                track.title?.takeIf { it.isNotBlank() } ?: "Untitled"
            }
        } else {
            null
        }
        when (resolveListenContentMode(playback)) {
            ListenContentMode.Cast -> {
            CastListenScreen(
                playback = playback,
                appMode = state.appMode,
                adminKey = state.adminKey,
                allowTrackReports = state.allowTrackReports,
                vizLabels = vizLabels,
                isFavorite = isFavorite,
                hasTuningDrift = hasTuningDrift,
                onOpenTuning = { showTuning = true },
                onOpenInfo = { showInfo = true },
                onDeleteCurrentTrack = onDeleteCurrentTrack,
                onReportCurrentTrack = onReportCurrentTrack,
                onShare = onShare,
                onToggleFavorite = onToggleFavorite,
                onOpenExport = { showExport = true },
                isExporting = state.export.isExporting,
                favoriteToggleInFlight = favoriteToggleInFlight,
                playlist = state.playlist,
                onOpenPlaylist = { showPlaylist = true },
                showSavedPlaylist = showSavedPlaylist,
                resumeTitle = resumeTitle,
                onContinueListening = viewModel::continueListening,
                onSelectVisualization = viewModel::setActiveVisualization,
                onCancelAnalysis = viewModel::cancelLocalAnalysis,
                onRetryCastLoad = viewModel::retryCastLoad
            )
            }
            ListenContentMode.LocalReady -> {
            LocalListenScreen(
                playback = playback,
                appMode = state.appMode,
                adminKey = state.adminKey,
                allowTrackReports = state.allowTrackReports,
                tuning = tuning,
                vizLabels = vizLabels,
                jumpLine = jumpLine,
                isFavorite = isFavorite,
                hasTuningDrift = hasTuningDrift,
                onOpenTuning = { showTuning = true },
                onOpenInfo = { showInfo = true },
                onDeleteCurrentTrack = onDeleteCurrentTrack,
                onReportCurrentTrack = onReportCurrentTrack,
                onShare = onShare,
                onToggleFavorite = onToggleFavorite,
                onOpenExport = { showExport = true },
                isExporting = state.export.isExporting,
                favoriteToggleInFlight = favoriteToggleInFlight,
                onSetPlaybackMode = viewModel::selectPlaybackMode,
                onSetVisualization = viewModel::setActiveVisualization,
                onSetCanonizerFinishOutSong = viewModel::setCanonizerFinishOutSong,
                onSetWubMachineLoop = viewModel::setWubMachineLoop,
                onSelectBeat = viewModel::selectBeat,
                onSelectWubMachinePosition = viewModel::selectWubMachinePosition,
                playlist = state.playlist,
                onOpenPlaylist = { showPlaylist = true },
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
                    ResumeShortcutsRow(
                        showSavedPlaylist = showSavedPlaylist,
                        resumeTitle = resumeTitle,
                        onOpenPlaylist = { showPlaylist = true },
                        onContinueListening = viewModel::continueListening
                    )
                }
            }
            ListenContentMode.None -> Unit
        }
    }

    val canOpenCastReceiverDetails = playback.castReceiverDetailsReady()
    if (showInfo && playback.playMode == PlaybackMode.Jukebox && canOpenCastReceiverDetails) {
        val totalBeats = if (playback.isCasting) {
            playback.castTotalBeats ?: 0
        } else {
            playback.vizData?.beats?.size ?: playback.castTotalBeats ?: 0
        }
        val totalBranches = if (playback.isCasting) {
            playback.castTotalBranches ?: 0
        } else {
            // The custom anchor's synthetic edge is drawn with the branches but is not one.
            playback.vizData?.edges?.count { !isCustomAnchorEdge(it) } ?: playback.castTotalBranches ?: 0
        }
        TrackInfoDialog(
            durationSeconds = playback.trackDurationSeconds,
            totalBeats = totalBeats,
            totalBranches = totalBranches,
            onClose = { showInfo = false }
        )
    }

    if (showTuning && playback.playMode == PlaybackMode.Jukebox && canOpenCastReceiverDetails) {
        val audioModeOptions = if (playback.isCasting) {
            playback.castSupportedAudioModes
        } else {
            localAudioModeOptions
        }
        // Keyed on everything the check reads: a cast status or a track load can land while the
        // dialog is open, and the engine's deleted branches change only alongside vizData.
        val branchTuningNonDefault = remember(tuning, playback.isCasting, playback.vizData) {
            viewModel.canResetBranchTuning()
        }
        TuningDialog(
            initialThreshold = tuning.threshold,
            computedThreshold = tuning.computedThreshold,
            initialMinProb = tuning.minProb,
            initialMaxProb = tuning.maxProb,
            initialRamp = tuning.ramp,
            initialHighlightAnchorBranch = tuning.highlightAnchorBranch,
            initialJustBackwards = tuning.justBackwards,
            initialMinJumpDistancePercent = tuning.minJumpDistancePercent,
            initialRemoveSequential = tuning.removeSequential,
            initialAudioModeWireValue = if (playback.isCasting) {
                playback.castAudioModeWireValue
            } else {
                playback.jukeboxAudioMode.wireValue
            },
            initialAudioModeIntensity = if (playback.isCasting) {
                playback.castAudioModeIntensity
            } else {
                playback.jukeboxAudioModeIntensity
            },
            audioModeOptions = audioModeOptions,
            isAudioModePickerEnabled = !playback.isCasting || audioModeOptions.isNotEmpty(),
            branchTuningNonDefault = branchTuningNonDefault,
            onDismiss = { showTuning = false },
            onResetBranchTuning = viewModel::resetBranchTuningDefaults,
            onResetAudioMode = viewModel::resetAudioModeDefaults,
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
            onMove = viewModel::movePlaylistTrack,
            onClear = viewModel::clearPlaylist,
            onClose = { showPlaylist = false }
        )
    }

    if (showExport &&
        shouldShowExportControls(
            state.appMode,
            playback,
            state.export.isExporting,
            Build.VERSION.SDK_INT
        )
    ) {
        ExportDialog(
            export = state.export,
            trackDurationSeconds = if (playback.playMode == PlaybackMode.WubMachine) {
                playback.wubMachine.durationSeconds
            } else {
                playback.trackDurationSeconds
            },
            audioMode = playback.jukeboxAudioMode,
            audioModeIntensity = playback.jukeboxAudioModeIntensity,
            onStart = viewModel::startExport,
            onCancelExport = viewModel::cancelExport,
            onDismiss = { showExport = false },
            playMode = playback.playMode
        )
    }

    reportJobId?.let { jobId ->
        ReportTrackDialog(
            sending = reportSending,
            onDismiss = { reportJobId = null },
            onReport = { reason ->
                reportSending = true
                viewModel.reportTrack(jobId, reason) {
                    reportSending = false
                    reportJobId = null
                }
            }
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

/**
 * Press-to-drag that reports how a drag ended, as it happens: [onDrop] when the finger lifts,
 * [onCancel] when the gesture is cancelled. The handle's onDragStopped cannot tell these
 * apart, and also fires when the dialog closes mid-drag, which reports neither.
 */
private fun playlistDropDetector(onDrop: () -> Unit, onCancel: () -> Unit) =
    object : DragGestureDetector {
        override suspend fun PointerInputScope.detect(
            onDragStart: (Offset) -> Unit,
            onDragEnd: () -> Unit,
            onDragCancel: () -> Unit,
            onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
        ) {
            detectDragGestures(
                onDragStart = onDragStart,
                onDragEnd = {
                    onDragEnd()
                    onDrop()
                },
                onDragCancel = {
                    onDragCancel()
                    onCancel()
                },
                onDrag = onDrag
            )
        }
    }

/** TalkBack stand-ins for dragging, which accessibility services cannot perform. */
private fun playlistMoveActions(
    index: Int,
    lastIndex: Int,
    onMove: (from: Int, to: Int) -> Unit
): List<CustomAccessibilityAction> = buildList {
    if (index > 0) {
        add(CustomAccessibilityAction("Move up") { onMove(index, index - 1); true })
    }
    if (index < lastIndex) {
        add(CustomAccessibilityAction("Move down") { onMove(index, index + 1); true })
    }
}

@Composable
private fun PlaylistDialog(
    playlist: JukeboxPlaylistState,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    val playlistKeys = playlist.tracks.map { it.playlistKey }
    val playlistIndexByKey = playlistKeys.withIndex().associate { (index, key) -> key to index }
    // Drags reorder this local copy of the order every frame; the view model only sees the
    // finished move, so a drag persists once instead of on every slot it passes. Keyed on the
    // order alone, so a metadata update landing mid-drag leaves the drag in place.
    val orderedKeysState = remember(playlistKeys) { mutableStateOf(playlistKeys) }
    var orderedKeys by orderedKeysState
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        orderedKeys = orderedKeys.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    // The drag handle keeps its gesture callbacks from its first composition, so they read
    // through updated state rather than capturing these values. The local order is read from
    // its state object, which a swap updates before the next recomposition does.
    val latestPlaylistKeys by rememberUpdatedState(playlistKeys)
    val latestOrderedKeysState by rememberUpdatedState(orderedKeysState)
    val latestOnMove by rememberUpdatedState(onMove)
    val commitMove: (String) -> Unit = { key ->
        playlistMoveFor(latestPlaylistKeys, latestOrderedKeysState.value, key)?.let { (from, to) ->
            latestOnMove(from, to)
        }
    }
    val revertMove: () -> Unit = { latestOrderedKeysState.value = latestPlaylistKeys }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Playlist") },
        text = {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(orderedKeys, key = { _, key -> key }) { position, key ->
                    // Actions address the view model's order, which differs from the
                    // on-screen position while a drag is in flight.
                    val index = playlistIndexByKey[key] ?: return@itemsIndexed
                    val track = playlist.tracks[index]
                    val dropDetector = remember {
                        playlistDropDetector(onDrop = { commitMove(key) }, onCancel = revertMove)
                    }
                    ReorderableItem(reorderState, key = key) { isDragging ->
                        val selected = index == playlist.currentIndex
                        val displayTitle = track.title?.takeIf { it.isNotBlank() } ?: "Untitled"
                        val displayArtist = track.artist?.takeIf { it.isNotBlank() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(SurfaceShape)
                                .background(
                                    when {
                                        isDragging -> MaterialTheme.colorScheme.surfaceVariant
                                        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                        else -> MaterialTheme.colorScheme.surface
                                    }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // The handle sits outside the clickable area: a drag gesture
                            // leaves taps unconsumed, so a short press would select the track.
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = "Reorder",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(24.dp)
                                    .draggableHandle(dragGestureDetector = dropDetector)
                                    .semantics {
                                        customActions = playlistMoveActions(
                                            index = index,
                                            lastIndex = playlist.tracks.lastIndex,
                                            onMove = onMove
                                        )
                                    }
                            )
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = playlist.canSelectTrackAt(index)) {
                                        onSelect(index)
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${position + 1}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
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
                            }
                            if (playlist.canRemoveTrackAt(index)) {
                                SquareIconButton(
                                    onClick = { onRemove(index) },
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(28.dp)
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
