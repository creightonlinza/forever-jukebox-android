package com.foreverjukebox.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.foreverjukebox.app.visualization.AutocanonizerVisualization
import com.foreverjukebox.app.visualization.EdgeRouting
import com.foreverjukebox.app.visualization.JukeboxVisualization
import com.foreverjukebox.app.visualization.edgeRoutingForVisualization
import com.foreverjukebox.app.visualization.positioners
import com.foreverjukebox.app.visualization.prefersWideAspectForVisualization
import com.foreverjukebox.app.visualization.visualizationLabels

@Composable
internal fun FullscreenVisualizationScreen(
    state: UiState,
    onClose: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSetPlaybackMode: (PlaybackMode) -> Unit,
    onSetVisualization: (Int) -> Unit,
    onSelectBeat: (Int) -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit
) {
    ImmersiveFullscreenEffect(active = true)
    BackHandler(onBack = onClose)

    val playback = state.playback
    val tuning = state.tuning
    val inAutocanonizer = playback.playMode == PlaybackMode.Autocanonizer
    val vizLabels = visualizationLabels
    var showVizMenu by remember(playback.activeVizIndex) { mutableStateOf(false) }
    var showModeMenu by remember(playback.playMode) { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalThemeTokens.current.vizBackground)
    ) {
        val squareSize = minOf(maxWidth, maxHeight)
        val edgeRouting = edgeRoutingForVisualization(playback.activeVizIndex)
        val useWideLayout =
            !inAutocanonizer &&
                maxWidth > maxHeight &&
                prefersWideAspectForVisualization(playback.activeVizIndex)
        val jukeboxModifier = if (useWideLayout) {
            if (edgeRouting == EdgeRouting.ArcDiagram) {
                Modifier
                    .fillMaxSize()
                    .padding(vertical = 2.dp)
            } else {
                Modifier.fillMaxSize()
            }
        } else {
            Modifier.size(squareSize)
        }

        FullscreenVisualizationContent(
            playback = playback,
            tuning = tuning,
            inAutocanonizer = inAutocanonizer,
            edgeRouting = edgeRouting,
            modifier = Modifier.size(squareSize),
            jukeboxModifier = jukeboxModifier,
            onSelectBeat = onSelectBeat
        )

        FullscreenModeMenu(
            inAutocanonizer = inAutocanonizer,
            expanded = showModeMenu,
            onExpandedChange = { showModeMenu = it },
            onSetPlaybackMode = onSetPlaybackMode
        )

        if (!inAutocanonizer) {
            FullscreenVisualizationMenu(
                activeVizIndex = playback.activeVizIndex,
                vizLabels = vizLabels,
                expanded = showVizMenu,
                onExpandedChange = { showVizMenu = it },
                onSetVisualization = onSetVisualization
            )
        }

        FullscreenBottomControls(
            playback = playback,
            playlist = state.playlist,
            onTogglePlayback = onTogglePlayback,
            onSkipPrevious = onSkipPrevious,
            onSkipNext = onSkipNext,
            onClose = onClose
        )
    }
}

@Composable
internal fun ImmersiveFullscreenEffect(active: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(active, activity, lifecycleOwner, view) {
        if (!active || activity == null) {
            return@DisposableEffect onDispose { }
        }
        val window = activity.window
        fun hideSystemBars() {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.keepScreenOn = true
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        hideSystemBars()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hideSystemBars()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            restoreSystemBars(window)
            view.keepScreenOn = false
        }
    }
}

private fun restoreSystemBars(window: Window) {
    WindowCompat.setDecorFitsSystemWindows(window, true)
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    WindowCompat.getInsetsController(window, window.decorView)
        .show(WindowInsetsCompat.Type.systemBars())
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

@Composable
private fun FullscreenVisualizationContent(
    playback: PlaybackState,
    tuning: TuningState,
    inAutocanonizer: Boolean,
    edgeRouting: EdgeRouting,
    modifier: Modifier,
    jukeboxModifier: Modifier,
    onSelectBeat: (Int) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (inAutocanonizer) {
            AutocanonizerVisualization(
                data = playback.autocanonizerData,
                currentIndex = playback.currentBeatIndex,
                forcedOtherIndex = playback.canonizerOtherIndex,
                tileColorOverrides = playback.canonizerTileColorOverrides,
                onSelectBeat = onSelectBeat,
                modifier = modifier
            )
        } else {
            JukeboxVisualization(
                data = playback.vizData,
                currentIndex = playback.currentBeatIndex,
                jumpLine = playback.jumpLine,
                positioner = positioners.getOrNull(playback.activeVizIndex) ?: positioners.first(),
                edgeRouting = edgeRouting,
                highlightAnchorBranch = tuning.highlightAnchorBranch,
                onSelectBeat = onSelectBeat,
                modifier = jukeboxModifier
            )
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.FullscreenModeMenu(
    inAutocanonizer: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSetPlaybackMode: (PlaybackMode) -> Unit
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            OutlinedButton(
                onClick = { onExpandedChange(true) },
                colors = pillOutlinedButtonColors(),
                border = pillButtonBorder(),
                shape = PillShape,
                modifier = Modifier.height(36.dp)
            ) {
                Text(if (inAutocanonizer) "Autocanonizer" else "Jukebox")
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) }
            ) {
                DropdownMenuItem(
                    text = { Text("Autocanonizer") },
                    onClick = {
                        onSetPlaybackMode(PlaybackMode.Autocanonizer)
                        onExpandedChange(false)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Jukebox") },
                    onClick = {
                        onSetPlaybackMode(PlaybackMode.Jukebox)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.FullscreenVisualizationMenu(
    activeVizIndex: Int,
    vizLabels: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSetVisualization: (Int) -> Unit
) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(18.dp)
    ) {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            colors = pillOutlinedButtonColors(),
            border = pillButtonBorder(),
            shape = PillShape,
            modifier = Modifier.height(36.dp)
        ) {
            Text(vizLabels.getOrNull(activeVizIndex) ?: "Select")
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            vizLabels.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSetVisualization(index)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.FullscreenBottomControls(
    playback: PlaybackState,
    playlist: JukeboxPlaylistState,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SurfaceShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FullscreenTransportButtons(
                playback = playback,
                playlist = playlist,
                onTogglePlayback = onTogglePlayback,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                AutoMarqueeText(
                    text = nowPlayingLine(playback),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
                val summaryLine = playbackSummaryLine(playback)
                if (summaryLine != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = summaryLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            SquareIconButton(
                onClick = onClose,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.FullscreenExit,
                    contentDescription = "Exit fullscreen",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun FullscreenTransportButtons(
    playback: PlaybackState,
    playlist: JukeboxPlaylistState,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit
) {
    val showPlaylistControls = shouldShowPlaylistControls(playlist)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showPlaylistControls && playlist.canSkipPrevious()) {
            SquareIconButton(
                onClick = onSkipPrevious,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "Previous playlist track",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        OutlinedButton(
            onClick = onTogglePlayback,
            colors = pillOutlinedButtonColors(),
            border = pillButtonBorder(),
            shape = PillShape,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (playback.isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = playbackTransportContentDescription(playback),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
        if (showPlaylistControls && playlist.canSkipNext()) {
            SquareIconButton(
                onClick = onSkipNext,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "Next playlist track",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

