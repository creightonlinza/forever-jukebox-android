package com.foreverjukebox.app.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.foreverjukebox.app.ui.LocalThemeTokens
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.foreverjukebox.app.visualization.JukeboxVisualization
import com.foreverjukebox.app.visualization.positioners
import com.foreverjukebox.app.visualization.visualizationLabels
import kotlinx.coroutines.delay

@Composable
fun PlayPanel(state: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val playback = state.playback
    val tuning = state.tuning
    var showTuning by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showVizMenu by remember { mutableStateOf(false) }
    val vizLabels = visualizationLabels
    var jumpLine by remember { mutableStateOf(playback.jumpLine) }
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
        viewModel.setActiveVisualization(nextIndex)
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!playback.analysisErrorMessage.isNullOrBlank()) {
            ErrorStatus(message = playback.analysisErrorMessage)
        } else if (playback.analysisInFlight || playback.analysisCalculating || playback.audioLoading) {
            LoadingStatus(
                progress = playback.analysisProgress,
                label = when {
                    playback.analysisCalculating -> "Calculating pathways"
                    playback.analysisInFlight -> playback.analysisMessage ?: "Fetching audio"
                    playback.audioLoading -> "Fetching audio"
                    else -> null
                }
            )
        }

        if (playback.audioLoaded && playback.analysisLoaded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (playback.playTitle.isNotBlank()) {
                    Text(
                        text = playback.playTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                val isFavorite = playback.lastYouTubeId?.let { id ->
                    state.favorites.any { it.uniqueSongId == id }
                } == true
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.togglePlayback() },
                        colors = pillButtonColors(),
                        border = pillButtonBorder(),
                        shape = PillShape,
                        contentPadding = SmallButtonPadding,
                        modifier = Modifier.height(SmallButtonHeight)
                    ) {
                        Text(if (playback.isRunning) "Stop" else "Play", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = { showTuning = true },
                        colors = pillOutlinedButtonColors(),
                        border = pillButtonBorder(),
                        shape = PillShape,
                        contentPadding = SmallButtonPadding,
                        modifier = Modifier.height(SmallButtonHeight)
                    ) {
                        Text("Tune", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(
                        onClick = { showInfo = true },
                        modifier = Modifier.size(SmallButtonHeight)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Info",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(
                        onClick = {
                            if (playback.lastYouTubeId == null) return@IconButton
                            val limitReached = viewModel.toggleFavoriteForCurrent()
                            val message = when {
                                limitReached -> "Maximum favorites reached (100)."
                                isFavorite -> "Removed from Favorites"
                                else -> "Added to Favorites"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(SmallButtonHeight)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (isFavorite) "Remove favorite" else "Add favorite",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(
                        onClick = {
                            val id = playback.lastYouTubeId ?: return@IconButton
                            val url = "foreverjukebox://listen/$id"
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Forever Jukebox link"))
                        },
                        modifier = Modifier.size(SmallButtonHeight)
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                val themeTokens = LocalThemeTokens.current
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(themeTokens.vizBackground)
                ) {
                    JukeboxVisualization(
                        data = playback.vizData,
                        currentIndex = playback.currentBeatIndex,
                        jumpLine = jumpLine,
                        positioner = positioners.getOrNull(playback.activeVizIndex) ?: positioners.first(),
                        onSelectBeat = viewModel::selectBeat
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showVizMenu = true },
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
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = showVizMenu,
                            onDismissRequest = { showVizMenu = false }
                        ) {
                            vizLabels.forEachIndexed { index, label ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        viewModel.setActiveVisualization(index)
                                        showVizMenu = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            val intent = Intent(context, FullscreenActivity::class.java)
                                .putExtra(FullscreenActivity.EXTRA_VIZ_INDEX, playback.activeVizIndex)
                            fullscreenLauncher.launch(intent)
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(SmallButtonHeight)
                    ) {
                        Icon(
                            Icons.Default.Fullscreen,
                            contentDescription = "Fullscreen",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Listen Time: ${playback.listenTime}", color = MaterialTheme.colorScheme.onBackground)
                    Text("Total Beats: ${playback.beatsPlayed}", color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }

    if (showInfo) {
        val totalBeats = playback.vizData?.beats?.size ?: 0
        val totalBranches = playback.vizData?.edges?.size ?: 0
        TrackInfoDialog(
            durationSeconds = playback.trackDurationSeconds,
            totalBeats = totalBeats,
            totalBranches = totalBranches,
            onClose = { showInfo = false }
        )
    }

    if (showTuning) {
        TuningDialog(
            initialThreshold = tuning.threshold,
            initialMinProb = tuning.minProb,
            initialMaxProb = tuning.maxProb,
            initialRamp = tuning.ramp,
            initialAddLastEdge = tuning.addLastEdge,
            initialJustBackwards = tuning.justBackwards,
            initialJustLong = tuning.justLong,
            initialRemoveSequential = tuning.removeSequential,
            onDismiss = { showTuning = false },
            onApply = viewModel::applyTuning
        )
    }
}

@Composable
private fun LoadingStatus(progress: Int?, label: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
        if (progress != null && progress > 0) {
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
}

@Composable
private fun ErrorStatus(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
