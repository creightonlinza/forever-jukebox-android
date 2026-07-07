package com.foreverjukebox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Persistent now-playing bar shown above the bottom navigation whenever a
 * track is loaded. Mirrors the fullscreen bottom controls' data (title/artist
 * marquee plus listen-time summary) with a borderless transport on the right.
 */
@Composable
fun PlaybackBar(
    state: UiState,
    onTogglePlayback: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenPlayTab: () -> Unit
) {
    val playback = state.playback
    val playlist = state.playlist
    val showTransport = shouldShowPlaybackTransport(playback)
    val showPlaylistControls = shouldShowPlaylistControls(playlist)
    val textModifier = if (state.activeTab != TabId.Play) {
        Modifier.clickable(onClick = onOpenPlayTab)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SurfaceShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = textModifier.weight(1f, fill = true),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            AutoMarqueeText(
                text = nowPlayingLine(playback),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
            val summaryLine = playbackSummaryLine(playback)
            if (summaryLine != null) {
                Text(
                    text = summaryLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showTransport) {
            Spacer(modifier = Modifier.width(8.dp))
            if (showPlaylistControls && playlist.canSkipPrevious()) {
                SquareIconButton(
                    onClick = onSkipPrevious,
                    modifier = Modifier.size(BarTransportButtonSize)
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Previous playlist track",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            SquareIconButton(
                onClick = onTogglePlayback,
                modifier = Modifier.size(BarTransportButtonSize)
            ) {
                Icon(
                    imageVector = if (playback.isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = playbackTransportContentDescription(playback),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            if (showPlaylistControls && playlist.canSkipNext()) {
                SquareIconButton(
                    onClick = onSkipNext,
                    modifier = Modifier.size(BarTransportButtonSize)
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
}

private val BarTransportButtonSize = 40.dp
