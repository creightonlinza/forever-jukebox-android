package com.foreverjukebox.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import coil3.compose.AsyncImage
import com.foreverjukebox.app.data.SpotifySearchItem
import com.foreverjukebox.app.data.YoutubeSearchItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchPanel(
    state: UiState,
    onSearch: (String) -> Unit,
    onSpotifySelect: (SpotifySearchItem) -> Unit,
    onYoutubeSelect: (YoutubeSearchItem) -> Unit,
    onOpenYoutube: (String) -> Unit
) {
    val searchState = state.search
    var query by remember(searchState.query) { mutableStateOf(searchState.query) }
    var previewVideoId by remember { mutableStateOf<String?>(null) }
    var thumbnailFailed by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Search", style = MaterialTheme.typography.labelLarge)
            val trimmedQuery = query.trim()
            val searchInFlight = searchState.spotifyLoading
            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search by artist or track") },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (searchInFlight || trimmedQuery.isBlank()) return@KeyboardActions
                    onSearch(trimmedQuery)
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }),
                trailingIcon = {
                    SquareIconButton(
                        onClick = {
                            if (searchInFlight || trimmedQuery.isBlank()) return@SquareIconButton
                            onSearch(trimmedQuery)
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        },
                        enabled = trimmedQuery.isNotBlank() && !searchInFlight
                    ) {
                        if (searchInFlight) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = "Search"
                            )
                        }
                    }
                },
                shape = SurfaceShape,
                modifier = Modifier.fillMaxWidth()
            )

            if (searchState.spotifyLoading) {
                Text("Searching Spotify…", style = MaterialTheme.typography.bodySmall)
            } else if (searchState.spotifyResults.isNotEmpty()) {
                Text("Step 1: Find a Spotify track.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(searchState.spotifyResults) { item ->
                        SpotifyRow(item = item, onSelect = onSpotifySelect)
                    }
                }
            }

            if (searchState.youtubeLoading) {
                Text("Searching YouTube…", style = MaterialTheme.typography.bodySmall)
            } else if (searchState.youtubeMatches.isNotEmpty()) {
                Text("Step 2: Choose the closest YouTube match.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(searchState.youtubeMatches) { item ->
                        YoutubeRow(
                            item = item,
                            onSelect = onYoutubeSelect,
                            onPreview = { videoId ->
                                thumbnailFailed = false
                                previewVideoId = videoId
                            }
                        )
                    }
                }
            }
        }
    }

    previewVideoId?.let { videoId ->
        YoutubePreviewDialog(
            videoId = videoId,
            thumbnailFailed = thumbnailFailed,
            onThumbnailFailed = { thumbnailFailed = true },
            onOpenYoutube = { onOpenYoutube(videoId) },
            onClose = { previewVideoId = null }
        )
    }
}

@Composable
private fun SpotifyRow(item: SpotifySearchItem, onSelect: (SpotifySearchItem) -> Unit) {
    val name = item.name ?: "Untitled"
    val artist = item.artist ?: ""
    val duration = item.duration
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(item) },
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = if (artist.isNotBlank()) "$name — $artist" else name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatTrackDuration(duration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun YoutubeRow(
    item: YoutubeSearchItem,
    onSelect: (YoutubeSearchItem) -> Unit,
    onPreview: (String) -> Unit
) {
    val title = item.title ?: "Untitled"
    val videoId = item.id?.trim().orEmpty()
    if (videoId.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onSelect(item) },
                onLongClick = { onPreview(videoId) }
            ),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatTrackDuration(item.duration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun YoutubePreviewDialog(
    videoId: String,
    thumbnailFailed: Boolean,
    onThumbnailFailed: () -> Unit,
    onOpenYoutube: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Button(
                onClick = onOpenYoutube,
                colors = pillButtonColors(),
                border = pillButtonBorder(),
                shape = PillShape,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("Open in YouTube", style = MaterialTheme.typography.labelSmall)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onClose,
                colors = pillOutlinedButtonColors(),
                border = pillButtonBorder(),
                shape = PillShape,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("Close", style = MaterialTheme.typography.labelSmall)
            }
        },
        title = { Text("YouTube Preview") },
        text = {
            if (!thumbnailFailed) {
                AsyncImage(
                    model = youtubeThumbnailUrl(videoId),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { onThumbnailFailed() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(SurfaceShape)
                )
            }
        }
    )
}
