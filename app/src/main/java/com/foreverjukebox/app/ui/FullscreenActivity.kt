package com.foreverjukebox.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.foreverjukebox.app.data.AppPreferences
import com.foreverjukebox.app.data.ThemeMode
import com.foreverjukebox.app.engine.Edge
import com.foreverjukebox.app.playback.PlaybackControllerHolder
import com.foreverjukebox.app.visualization.JukeboxVisualization
import com.foreverjukebox.app.visualization.JumpLine
import com.foreverjukebox.app.visualization.positioners

class FullscreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val initialVizIndex = intent.getIntExtra(EXTRA_VIZ_INDEX, 0)
        setContent {
            val prefs = remember { AppPreferences(this) }
            val themeMode by prefs.themeMode.collectAsState(initial = ThemeMode.System)
            ForeverJukeboxTheme(mode = themeMode) {
                FullscreenScreen(
                    initialVizIndex = initialVizIndex,
                    onExit = { selectedIndex ->
                        val result = Intent().putExtra(EXTRA_RESULT_VIZ_INDEX, selectedIndex)
                        setResult(RESULT_OK, result)
                        finish()
                    }
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    companion object {
        const val EXTRA_VIZ_INDEX = "com.foreverjukebox.app.viz_index"
        const val EXTRA_RESULT_VIZ_INDEX = "com.foreverjukebox.app.viz_index_result"
    }
}

@Composable
private fun FullscreenScreen(
    initialVizIndex: Int,
    onExit: (Int) -> Unit
) {
    val context = LocalContext.current
    val controller = remember { PlaybackControllerHolder.get(context) }
    val engine = controller.engine
    val view = LocalView.current
    val vizLabels = listOf("Orbit", "Spiral", "Grid", "Wave", "Infinity", "Bloom")
    var activeVizIndex by rememberSaveable { mutableStateOf(initialVizIndex) }
    var vizData by remember { mutableStateOf(engine.getVisualizationData()) }
    var currentBeatIndex by remember { mutableStateOf(-1) }
    var selectedEdge by remember { mutableStateOf<Edge?>(null) }
    var jumpLine by remember { mutableStateOf<JumpLine?>(null) }
    var showVizMenu by remember { mutableStateOf(false) }

    BackHandler {
        onExit(activeVizIndex)
    }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(engine) {
        val listener: (com.foreverjukebox.app.engine.JukeboxState) -> Unit = { state ->
            currentBeatIndex = state.currentBeatIndex
            val lastJumpFrom = state.lastJumpFromIndex
            if (state.lastJumped && lastJumpFrom != null) {
                jumpLine = JumpLine(lastJumpFrom, state.currentBeatIndex, SystemClock.elapsedRealtime())
            }
        }
        engine.onUpdate(listener)
        onDispose { engine.removeUpdateListener(listener) }
    }

    LaunchedEffect(Unit) {
        vizData = engine.getVisualizationData()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val squareSize = minOf(this.maxWidth, this.maxHeight)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            JukeboxVisualization(
                data = vizData,
                currentIndex = currentBeatIndex,
                selectedEdge = selectedEdge,
                jumpLine = jumpLine,
                positioner = positioners.getOrNull(activeVizIndex) ?: positioners.first(),
                onSelectBeat = { index ->
                    val data = vizData ?: return@JukeboxVisualization
                    if (index < 0 || index >= data.beats.size) return@JukeboxVisualization
                    val beat = data.beats[index]
                    controller.player.seek(beat.start)
                    currentBeatIndex = index
                },
                onSelectEdge = { edge ->
                    selectedEdge = edge
                },
                modifier = Modifier.size(squareSize)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(18.dp)
        ) {
            OutlinedButton(
                onClick = { showVizMenu = true },
                modifier = Modifier.height(36.dp)
            ) {
                Text(vizLabels.getOrNull(activeVizIndex) ?: "Select")
            }
            DropdownMenu(
                expanded = showVizMenu,
                onDismissRequest = { showVizMenu = false }
            ) {
                vizLabels.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            activeVizIndex = index
                            showVizMenu = false
                        }
                    )
                }
            }
        }
        IconButton(
            onClick = { onExit(activeVizIndex) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(18.dp)
                .size(36.dp)
        ) {
            Icon(
                Icons.Default.FullscreenExit,
                contentDescription = "Exit fullscreen",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
