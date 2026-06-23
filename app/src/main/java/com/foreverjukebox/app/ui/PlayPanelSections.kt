package com.foreverjukebox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.foreverjukebox.app.autocanonizer.AutocanonizerMainColor
import com.foreverjukebox.app.autocanonizer.AutocanonizerOtherColor
import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.visualization.AutocanonizerVisualization
import com.foreverjukebox.app.visualization.EdgeRouting
import com.foreverjukebox.app.visualization.JukeboxVisualization
import com.foreverjukebox.app.visualization.JumpLine
import com.foreverjukebox.app.visualization.edgeRoutingForVisualization
import com.foreverjukebox.app.visualization.positioners
import com.foreverjukebox.app.visualization.prefersWideAspectForVisualization

@Composable
private fun PlaybackHeaderRow(
    playback: PlaybackState,
    headerTitle: String?,
    inAutocanonizer: Boolean,
    showServerActions: Boolean,
    showControls: Boolean,
    showTuningAndInfo: Boolean,
    showDeleteTrackAction: Boolean,
    isFavorite: Boolean,
    favoriteToggleInFlight: Boolean,
    onOpenTuning: () -> Unit,
    onOpenInfo: () -> Unit,
    onDeleteCurrentTrack: () -> Unit,
    onShare: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val themeTokens = LocalThemeTokens.current
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!headerTitle.isNullOrBlank()) {
            AutoMarqueeText(
                text = headerTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .weight(1f, fill = true)
                    .padding(end = 8.dp)
            )
        } else {
            Spacer(modifier = Modifier.weight(1f, fill = true))
        }
        if (showControls) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showDeleteTrackAction) {
                    SquareIconButton(
                        onClick = {
                            if (!playback.deleteInFlight) {
                                onDeleteCurrentTrack()
                            }
                        },
                        enabled = !playback.deleteInFlight,
                        modifier = Modifier.size(SmallButtonHeight)
                    ) {
                        if (playback.deleteInFlight) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = DeleteActionColor,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Delete within 30 minutes of creation",
                                tint = DeleteActionColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                if (!inAutocanonizer && showTuningAndInfo) {
                    SquareIconButton(
                        onClick = onOpenTuning,
                        modifier = Modifier.size(SmallButtonHeight)
                    ) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = "Tune",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    SquareIconButton(
                        onClick = onOpenInfo,
                        modifier = Modifier.size(SmallButtonHeight)
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "Info",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (showServerActions) {
                    SquareIconButton(
                        onClick = onShare,
                        modifier = Modifier.size(SmallButtonHeight)
                    ) {
                        Icon(
                            Icons.Outlined.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    SquareIconButton(
                        onClick = onToggleFavorite,
                        enabled = !favoriteToggleInFlight,
                        modifier = Modifier.size(SmallButtonHeight)
                    ) {
                        if (favoriteToggleInFlight) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = themeTokens.beatFill,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = if (isFavorite) "Remove favorite" else "Add favorite",
                                tint = themeTokens.beatFill,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.CastListenScreen(
    playback: PlaybackState,
    appMode: AppMode?,
    adminKey: String,
    headerTitle: String?,
    vizLabels: List<String>,
    isFavorite: Boolean,
    onTogglePlayback: () -> Unit,
    onOpenTuning: () -> Unit,
    onOpenInfo: () -> Unit,
    onDeleteCurrentTrack: () -> Unit,
    onShare: () -> Unit,
    onToggleFavorite: () -> Unit,
    favoriteToggleInFlight: Boolean,
    playlist: JukeboxPlaylistState,
    onOpenPlaylist: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSelectVisualization: (Int) -> Unit
) {
    val hasCastTrack = playback.hasCastTrack()
    val canShowTransport = shouldShowPlaybackTransport(playback)
    val canSelectVisualization = playback.castControlsReady()
    val canShowReceiverDetails = playback.castReceiverDetailsReady()
    val inAutocanonizer = playback.playMode == PlaybackMode.Autocanonizer
    val showPlaylistControls = !inAutocanonizer
    val playActionLabel = playbackTransportContentDescription(playback)
    val showServerActions = shouldShowServerListenActions(appMode)
    val showDeleteTrackAction = shouldShowDeleteTrackAction(appMode, playback, adminKey)
    val themeTokens = LocalThemeTokens.current
    var showVizMenu by remember(playback.activeVizIndex) { mutableStateOf(false) }
    val castLabel = playback.castDeviceName?.let { "Connected to $it" } ?: "Connected to cast device"

    Column(
        modifier = Modifier
            .weight(1f, fill = true)
            .fillMaxWidth()
            .clip(SurfaceShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (hasCastTrack) {
            PlaybackHeaderRow(
                playback = playback,
                headerTitle = headerTitle,
                inAutocanonizer = inAutocanonizer,
                showServerActions = showServerActions,
                showControls = canShowTransport,
                showTuningAndInfo = canShowReceiverDetails,
                showDeleteTrackAction = showDeleteTrackAction,
                isFavorite = isFavorite,
                favoriteToggleInFlight = favoriteToggleInFlight,
                onOpenTuning = onOpenTuning,
                onOpenInfo = onOpenInfo,
                onDeleteCurrentTrack = onDeleteCurrentTrack,
                onShare = onShare,
                onToggleFavorite = onToggleFavorite
            )
        }
        Box(
            modifier = Modifier
                .weight(1f, fill = true)
                .fillMaxWidth()
                .clip(SurfaceShape)
                .background(themeTokens.vizBackground),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Cast,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.16f),
                modifier = Modifier.size(170.dp)
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Text(
                    text = castLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (!hasCastTrack) {
                    Text(
                        text = "Choose a track to start casting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                    )
                } else {
                    if (canSelectVisualization) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Visualization:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Box {
                                OutlinedButton(
                                    onClick = { showVizMenu = true },
                                    colors = pillOutlinedButtonColors(),
                                    border = pillButtonBorder(),
                                    shape = PillShape,
                                    contentPadding = SmallButtonPadding,
                                    modifier = Modifier.height(SmallButtonHeight)
                                ) {
                                    Text(
                                        text = vizLabels.getOrNull(playback.activeVizIndex) ?: "Select",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showVizMenu,
                                    onDismissRequest = { showVizMenu = false }
                                ) {
                                    vizLabels.forEachIndexed { index, label ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                onSelectVisualization(index)
                                                showVizMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (canShowTransport) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (showPlaylistControls && playlist.canSkipPrevious()) {
                                SquareIconButton(
                                    onClick = onSkipPrevious,
                                    modifier = Modifier.size(SmallButtonHeight)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SkipPrevious,
                                        contentDescription = "Previous playlist track",
                                        tint = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Button(
                                onClick = onTogglePlayback,
                                colors = pillButtonColors(),
                                border = pillButtonBorder(),
                                shape = SurfaceShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(SmallButtonHeight)
                            ) {
                                Icon(
                                    imageVector = if (playback.isRunning) {
                                        Icons.Filled.Pause
                                    } else {
                                        Icons.Filled.PlayArrow
                                    },
                                    contentDescription = playActionLabel,
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (showPlaylistControls && playlist.canSkipNext()) {
                                SquareIconButton(
                                    onClick = onSkipNext,
                                    modifier = Modifier.size(SmallButtonHeight)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SkipNext,
                                        contentDescription = "Next playlist track",
                                        tint = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            if (showPlaylistControls && shouldShowActivePlaylistControls(playlist)) {
                                SquareIconButton(
                                    onClick = onOpenPlaylist,
                                    modifier = Modifier.size(SmallButtonHeight)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.QueueMusic,
                                        contentDescription = "Playlist",
                                        tint = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.LocalListenScreen(
    playback: PlaybackState,
    appMode: AppMode?,
    adminKey: String,
    tuning: TuningState,
    headerTitle: String?,
    vizLabels: List<String>,
    jumpLine: JumpLine?,
    isFavorite: Boolean,
    onTogglePlayback: () -> Unit,
    onOpenTuning: () -> Unit,
    onOpenInfo: () -> Unit,
    onDeleteCurrentTrack: () -> Unit,
    onShare: () -> Unit,
    onToggleFavorite: () -> Unit,
    favoriteToggleInFlight: Boolean,
    onSetPlaybackMode: (PlaybackMode) -> Unit,
    onSetVisualization: (Int) -> Unit,
    onSetCanonizerFinishOutSong: (Boolean) -> Unit,
    onSelectBeat: (Int) -> Unit,
    playlist: JukeboxPlaylistState,
    onOpenPlaylist: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenFullscreen: () -> Unit
) {
    val showServerActions = shouldShowServerListenActions(appMode)
    val showDeleteTrackAction = shouldShowDeleteTrackAction(appMode, playback, adminKey)
    val inAutocanonizer = playback.playMode == PlaybackMode.Autocanonizer
    val showInlineTitleWithControls = shouldShowPlaybackTransport(playback)

    Column(
        modifier = Modifier
            .weight(1f, fill = true)
            .fillMaxWidth()
            .clip(SurfaceShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (showInlineTitleWithControls) {
            PlaybackHeaderRow(
                playback = playback,
                headerTitle = headerTitle,
                inAutocanonizer = inAutocanonizer,
                showServerActions = showServerActions,
                showControls = true,
                showTuningAndInfo = true,
                showDeleteTrackAction = showDeleteTrackAction,
                isFavorite = isFavorite,
                favoriteToggleInFlight = favoriteToggleInFlight,
                onOpenTuning = onOpenTuning,
                onOpenInfo = onOpenInfo,
                onDeleteCurrentTrack = onDeleteCurrentTrack,
                onShare = onShare,
                onToggleFavorite = onToggleFavorite
            )
        }
        LocalVisualizationPanel(
            playback = playback,
            tuning = tuning,
            jumpLine = jumpLine,
            vizLabels = vizLabels,
            inAutocanonizer = inAutocanonizer,
            onSetPlaybackMode = onSetPlaybackMode,
            onSetVisualization = onSetVisualization,
            onSetCanonizerFinishOutSong = onSetCanonizerFinishOutSong,
            onSelectBeat = onSelectBeat,
            playlist = playlist,
            onOpenPlaylist = onOpenPlaylist,
            onSkipPrevious = onSkipPrevious,
            onSkipNext = onSkipNext,
            onOpenFullscreen = onOpenFullscreen,
            onTogglePlayback = onTogglePlayback
        )
        if (shouldShowAutocanonizerCursorTimes(playback)) {
            AutocanonizerCursorTimeRow(state = playback.autocanonizer)
        }
        LocalListenFooter(
            listenTime = playback.listenTime,
            beatsPlayed = playback.beatsPlayed,
            inAutocanonizer = inAutocanonizer
        )
    }
}

@Composable
private fun ColumnScope.LocalVisualizationPanel(
    playback: PlaybackState,
    tuning: TuningState,
    jumpLine: JumpLine?,
    vizLabels: List<String>,
    inAutocanonizer: Boolean,
    onSetPlaybackMode: (PlaybackMode) -> Unit,
    onSetVisualization: (Int) -> Unit,
    onSetCanonizerFinishOutSong: (Boolean) -> Unit,
    onSelectBeat: (Int) -> Unit,
    playlist: JukeboxPlaylistState,
    onOpenPlaylist: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenFullscreen: () -> Unit,
    onTogglePlayback: () -> Unit
) {
    val density = LocalDensity.current
    var vizContainerSize by remember { mutableStateOf(IntSize.Zero) }
    var showVizMenu by remember { mutableStateOf(false) }
    var showModeMenu by remember { mutableStateOf(false) }
    val themeTokens = LocalThemeTokens.current
    val playActionLabel = playbackTransportContentDescription(playback)
    val edgeRouting = edgeRoutingForVisualization(playback.activeVizIndex)
    val isLandscapeVizContainer = vizContainerSize.width > vizContainerSize.height
    val useWideLayout =
        !inAutocanonizer &&
            isLandscapeVizContainer &&
            prefersWideAspectForVisualization(playback.activeVizIndex)

    Box(
        modifier = Modifier
            .weight(1f, fill = true)
            .fillMaxWidth()
            .onSizeChanged { vizContainerSize = it }
            .clip(SurfaceShape)
            .background(themeTokens.vizBackground)
    ) {
        val vizSidePx = kotlin.math.min(vizContainerSize.width, vizContainerSize.height)
        val vizSide: Dp = with(density) { vizSidePx.toDp() }
        val jukeboxModifier = if (useWideLayout) {
            if (edgeRouting == EdgeRouting.ArcDiagram) {
                Modifier
                    .fillMaxSize()
                    .padding(vertical = 2.dp)
            } else {
                Modifier.fillMaxSize()
            }
        } else {
            Modifier.size(vizSide)
        }

        LocalVisualizationContent(
            playback = playback,
            tuning = tuning,
            jumpLine = jumpLine,
            inAutocanonizer = inAutocanonizer,
            vizSide = vizSide,
            edgeRouting = edgeRouting,
            modifier = jukeboxModifier,
            onSelectBeat = onSelectBeat
        )

        LocalPlaybackModeMenu(
            inAutocanonizer = inAutocanonizer,
            showModeMenu = showModeMenu,
            onShowModeMenu = { showModeMenu = it },
            onSetPlaybackMode = onSetPlaybackMode
        )
        LocalVisualizationTopEndControls(
            playback = playback,
            inAutocanonizer = inAutocanonizer,
            showVizMenu = showVizMenu,
            onShowVizMenu = { showVizMenu = it },
            vizLabels = vizLabels,
            onSetVisualization = onSetVisualization,
            onSetCanonizerFinishOutSong = onSetCanonizerFinishOutSong
        )
        LocalVisualizationBottomControls(
            playback = playback,
            playlist = playlist,
            playActionLabel = playActionLabel,
            onOpenPlaylist = onOpenPlaylist,
            onSkipPrevious = onSkipPrevious,
            onSkipNext = onSkipNext,
            onOpenFullscreen = onOpenFullscreen,
            onTogglePlayback = onTogglePlayback
        )
    }
}

@Composable
private fun LocalVisualizationContent(
    playback: PlaybackState,
    tuning: TuningState,
    jumpLine: JumpLine?,
    inAutocanonizer: Boolean,
    vizSide: Dp,
    edgeRouting: EdgeRouting,
    modifier: Modifier,
    onSelectBeat: (Int) -> Unit
) {
    if (inAutocanonizer) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AutocanonizerVisualization(
                data = playback.autocanonizerData,
                currentIndex = playback.currentBeatIndex,
                forcedOtherIndex = playback.canonizerOtherIndex,
                tileColorOverrides = playback.canonizerTileColorOverrides,
                onSelectBeat = onSelectBeat,
                modifier = Modifier.size(vizSide)
            )
        }
        return
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        JukeboxVisualization(
            data = playback.vizData,
            currentIndex = playback.currentBeatIndex,
            jumpLine = jumpLine,
            positioner = positioners.getOrNull(playback.activeVizIndex) ?: positioners.first(),
            edgeRouting = edgeRouting,
            highlightAnchorBranch = tuning.highlightAnchorBranch,
            onSelectBeat = onSelectBeat,
            modifier = modifier
        )
    }
}

@Composable
private fun BoxScope.LocalPlaybackModeMenu(
    inAutocanonizer: Boolean,
    showModeMenu: Boolean,
    onShowModeMenu: (Boolean) -> Unit,
    onSetPlaybackMode: (PlaybackMode) -> Unit
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box {
            OutlinedButton(
                onClick = { onShowModeMenu(true) },
                colors = pillOutlinedButtonColors(),
                border = pillButtonBorder(),
                shape = PillShape,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text(
                    text = if (inAutocanonizer) "Autocanonizer" else "Jukebox",
                    style = MaterialTheme.typography.labelSmall
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(
                expanded = showModeMenu,
                onDismissRequest = { onShowModeMenu(false) }
            ) {
                DropdownMenuItem(
                    text = { Text("Autocanonizer") },
                    onClick = {
                        onSetPlaybackMode(PlaybackMode.Autocanonizer)
                        onShowModeMenu(false)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Jukebox") },
                    onClick = {
                        onSetPlaybackMode(PlaybackMode.Jukebox)
                        onShowModeMenu(false)
                    }
                )
            }
        }
    }
}

@Composable
private fun BoxScope.LocalVisualizationTopEndControls(
    playback: PlaybackState,
    inAutocanonizer: Boolean,
    showVizMenu: Boolean,
    onShowVizMenu: (Boolean) -> Unit,
    vizLabels: List<String>,
    onSetVisualization: (Int) -> Unit,
    onSetCanonizerFinishOutSong: (Boolean) -> Unit
) {
    if (inAutocanonizer) {
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val themeTokens = LocalThemeTokens.current
            SquareIconButton(
                onClick = { onSetCanonizerFinishOutSong(!playback.canonizerFinishOutSong) },
                modifier = Modifier.size(SmallButtonHeight)
            ) {
                Icon(
                    imageVector = if (playback.canonizerFinishOutSong) {
                        Icons.Filled.CheckBox
                    } else {
                        Icons.Outlined.CheckBoxOutlineBlank
                    },
                    contentDescription = if (playback.canonizerFinishOutSong) {
                        "Disable finish out the track"
                    } else {
                        "Enable finish out the track"
                    },
                    tint = themeTokens.accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                "Finish out the track",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        return
    }
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(8.dp)
    ) {
        OutlinedButton(
            onClick = { onShowVizMenu(true) },
            colors = pillOutlinedButtonColors(),
            border = pillButtonBorder(),
            shape = PillShape,
            contentPadding = SmallButtonPadding,
            modifier = Modifier.height(SmallButtonHeight)
        ) {
            Text(
                vizLabels.getOrNull(playback.activeVizIndex) ?: "Select",
                style = MaterialTheme.typography.labelSmall
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(
            expanded = showVizMenu,
            onDismissRequest = { onShowVizMenu(false) }
        ) {
            vizLabels.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSetVisualization(index)
                        onShowVizMenu(false)
                    }
                )
            }
        }
    }
}

@Composable
private fun BoxScope.LocalVisualizationBottomControls(
    playback: PlaybackState,
    playlist: JukeboxPlaylistState,
    playActionLabel: String,
    onOpenPlaylist: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenFullscreen: () -> Unit,
    onTogglePlayback: () -> Unit
) {
    val showPlaylistControls = shouldShowPlaylistControls(playlist)
    Row(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showPlaylistControls && shouldShowActivePlaylistControls(playlist)) {
            SquareIconButton(
                onClick = onOpenPlaylist,
                modifier = Modifier.size(SmallButtonHeight)
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.QueueMusic,
                    contentDescription = "Playlist",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        SquareIconButton(
            onClick = onOpenFullscreen,
            modifier = Modifier.size(SmallButtonHeight)
        ) {
            Icon(
                Icons.Default.Fullscreen,
                contentDescription = "Fullscreen",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
    if (!shouldShowPlaybackTransport(playback)) {
        return
    }
    Row(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(6.dp)
            .wrapContentWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showPlaylistControls && playlist.canSkipPrevious()) {
            SquareIconButton(
                onClick = onSkipPrevious,
                modifier = Modifier.size(SmallButtonHeight)
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous playlist track",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Button(
            onClick = onTogglePlayback,
            colors = pillButtonColors(),
            border = pillButtonBorder(),
            shape = SurfaceShape,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(SmallButtonHeight)
        ) {
            Icon(
                imageVector = if (playback.isRunning) {
                    Icons.Filled.Pause
                } else {
                    Icons.Filled.PlayArrow
                },
                contentDescription = playActionLabel,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(20.dp)
            )
        }
        if (showPlaylistControls && playlist.canSkipNext()) {
            SquareIconButton(
                onClick = onSkipNext,
                modifier = Modifier.size(SmallButtonHeight)
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next playlist track",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

internal fun shouldShowAutocanonizerCursorTimes(playback: PlaybackState): Boolean {
    return playback.playMode == PlaybackMode.Autocanonizer && !playback.isCasting
}

@Composable
private fun AutocanonizerCursorTimeRow(state: AutocanonizerUiState) {
    val mainTime = formatCursorTime(state.mainSeconds)
    val otherTime = formatCursorTime(state.otherSeconds)
    val totalTime = formatCursorTime(state.trackDurationSeconds)
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics {
            contentDescription =
                "Blue position $mainTime, green position $otherTime, total $totalTime"
        }
    ) {
        Text(mainTime, color = AutocanonizerMainColor)
        Text("–", color = mutedColor)
        Text(otherTime, color = AutocanonizerOtherColor)
        Text("/", color = mutedColor)
        Text(totalTime, color = mutedColor)
    }
}

@Composable
private fun LocalListenFooter(
    listenTime: String,
    beatsPlayed: Int,
    inAutocanonizer: Boolean
) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("Listen Time: $listenTime", color = MaterialTheme.colorScheme.onBackground)
        if (!inAutocanonizer) {
            Text("Total Beats: $beatsPlayed", color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

internal enum class ListenContentMode {
    Cast,
    LocalReady,
    Empty,
    None
}

internal fun resolveListenContentMode(playback: PlaybackState): ListenContentMode {
    return when {
        playback.isCasting -> ListenContentMode.Cast
        playback.audioLoaded && playback.analysisLoaded -> ListenContentMode.LocalReady
        !playback.analysisInFlight &&
            !playback.analysisCalculating &&
            !playback.audioLoading &&
            playback.analysisErrorMessage.isNullOrBlank() -> ListenContentMode.Empty
        else -> ListenContentMode.None
    }
}

@Composable
internal fun LoadingStatus(
    progress: Int?,
    label: String?,
    trackTitle: String? = null,
    trackArtist: String? = null,
    playAfterLoaded: Boolean = false,
    showPlayAfterLoaded: Boolean = false,
    onPlayAfterLoadedChange: (Boolean) -> Unit = {},
    showCancel: Boolean = false,
    onCancel: () -> Unit = {}
) {
    val displayTitle = trackTitle?.trim()?.takeIf { it.isNotBlank() }
    val displayArtist = trackArtist?.trim()?.takeIf { it.isNotBlank() }
    val trackLine = when {
        displayTitle == null -> null
        displayArtist == null -> displayTitle
        else -> "$displayTitle - $displayArtist"
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center
            ) {
                val themeTokens = LocalThemeTokens.current
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = themeTokens.onBackground,
                    trackColor = themeTokens.onBackground.copy(alpha = 0.2f),
                    strokeWidth = 2.dp
                )
            }
            if (progress != null) {
                Text(
                    text = "${progress}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            if (!label.isNullOrBlank()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (trackLine != null) {
                Text(
                    text = trackLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
            if (showPlayAfterLoaded) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = playAfterLoaded,
                        onCheckedChange = onPlayAfterLoadedChange
                    )
                    Text(
                        text = "Play when ready",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (showCancel) {
                OutlinedButton(
                    onClick = onCancel,
                    colors = pillOutlinedButtonColors(),
                    border = pillButtonBorder(),
                    shape = PillShape,
                    contentPadding = SmallButtonPadding,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .height(SmallButtonHeight)
                ) {
                    Text("Cancel Analysis", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
