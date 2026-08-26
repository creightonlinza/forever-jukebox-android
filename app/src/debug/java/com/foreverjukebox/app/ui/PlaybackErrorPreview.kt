package com.foreverjukebox.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.data.ThemeMode
import com.foreverjukebox.app.engine.Edge
import com.foreverjukebox.app.engine.QuantumBase
import com.foreverjukebox.app.engine.VisualizationData
import com.foreverjukebox.app.visualization.visualizationLabels

/**
 * Renders the play panel's loaded-track layout with the playback-failure banner
 * stacked above the visualization, as the user sees it when the audio stream
 * cannot start after a successful load (e.g. output device disconnected).
 */
@Preview(showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun PlaybackFailedWithLoadedVizPreview() {
    ForeverJukeboxTheme(mode = ThemeMode.Dark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            LoadingFailedStatus(
                message = "Playback failed: the audio output isn't responding. " +
                    "If you're using a Bluetooth device, try reconnecting it or " +
                    "switching to another output."
            )
            LocalListenScreen(
                playback = previewLoadedPlaybackState(),
                appMode = AppMode.Local,
                adminKey = "",
                tuning = TuningState(),
                vizLabels = visualizationLabels,
                jumpLine = null,
                isFavorite = false,
                hasTuningDrift = false,
                onOpenTuning = {},
                onOpenInfo = {},
                onDeleteCurrentTrack = {},
                onShare = {},
                onToggleFavorite = {},
                onOpenExport = {},
                isExporting = false,
                favoriteToggleInFlight = false,
                onSetPlaybackMode = {},
                onSetVisualization = {},
                onSetCanonizerFinishOutSong = {},
                onSelectBeat = {},
                playlist = JukeboxPlaylistState(),
                onOpenPlaylist = {},
                onOpenFullscreen = {}
            )
        }
    }
}

private fun previewLoadedPlaybackState(): PlaybackState {
    return PlaybackState(
        audioLoaded = true,
        analysisLoaded = true,
        isRunning = false,
        isPaused = false,
        trackTitle = "Preview Track",
        trackArtist = "Preview Artist",
        trackDurationSeconds = 180.0,
        currentBeatIndex = 12,
        analysisErrorMessage = "set-but-rendered-by-banner-above",
        vizData = previewVisualizationData()
    )
}

private fun previewVisualizationData(): VisualizationData {
    val beats = List(48) { index ->
        QuantumBase(
            start = index * 0.45,
            duration = 0.45,
            confidence = 0.8,
            which = index
        )
    }
    val edgePairs = listOf(
        4 to 28, 9 to 33, 14 to 2, 20 to 41, 26 to 10, 31 to 45, 38 to 17, 44 to 6
    )
    val edges = edgePairs.mapIndexed { id, (from, to) ->
        Edge(
            id = id,
            src = beats[from],
            dest = beats[to],
            distance = 12.0,
            deleted = false
        )
    }.toMutableList()
    return VisualizationData(beats = beats, edges = edges)
}
