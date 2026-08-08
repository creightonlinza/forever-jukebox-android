package com.foreverjukebox.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Upload
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

@Composable
fun SearchPanel(
    state: UiState,
    onSearch: (String) -> Unit,
    onSpotifySelect: (RemoteMusicSearchItem) -> Unit,
    onYoutubeSelect: (RemoteVideoSearchItem) -> Unit,
    onOpenYoutube: (String) -> Unit,
    onSelectPanelTab: (SearchPanelTab) -> Unit,
    onSubmitUrl: (String) -> Unit,
    onClearUrlError: () -> Unit,
    onUploadFile: (Uri) -> Unit
) {
    val searchState = state.search
    // Hoisted above the sub-tab switch so a typed query and an open preview survive a visit to
    // the Upload tab.
    var query by remember(searchState.query) { mutableStateOf(searchState.query) }
    var previewItem by remember { mutableStateOf<RemoteVideoSearchItem?>(null) }
    var thumbnailFailed by remember { mutableStateOf(false) }
    val uploadTabAvailable = state.allowUserUrl || state.allowUserUpload
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
            if (uploadTabAvailable) {
                SearchPanelTabs(
                    activeTab = state.searchPanelTab,
                    onTabSelected = onSelectPanelTab
                )
            }
            if (state.searchPanelTab == SearchPanelTab.Upload) {
                AddYourOwnSection(
                    state = state,
                    onSubmitUrl = onSubmitUrl,
                    onClearUrlError = onClearUrlError,
                    onUploadFile = onUploadFile
                )
            } else {
                SearchSection(
                    searchState = searchState,
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = onSearch,
                    onSpotifySelect = onSpotifySelect,
                    onYoutubeSelect = onYoutubeSelect,
                    onPreview = {
                        thumbnailFailed = false
                        previewItem = it
                    }
                )
            }
        }
    }

    previewItem?.let { item ->
        val videoId = item.id?.trim().orEmpty()
        YoutubePreviewDialog(
            videoId = videoId,
            thumbnailFailed = thumbnailFailed,
            onThumbnailFailed = { thumbnailFailed = true },
            onUseResult = {
                previewItem = null
                onYoutubeSelect(item)
            },
            onOpenYoutube = { onOpenYoutube(videoId) },
            onClose = { previewItem = null }
        )
    }
}

@Composable
private fun SearchPanelTabs(
    activeTab: SearchPanelTab,
    onTabSelected: (SearchPanelTab) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SubTabCard(
            label = "Search",
            icon = Icons.Outlined.Search,
            active = activeTab == SearchPanelTab.Search,
            onClick = { onTabSelected(SearchPanelTab.Search) },
            modifier = Modifier.weight(1f)
        )
        SubTabCard(
            label = "Upload",
            icon = Icons.Outlined.Upload,
            active = activeTab == SearchPanelTab.Upload,
            onClick = { onTabSelected(SearchPanelTab.Upload) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SearchSection(
    searchState: SearchState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSpotifySelect: (RemoteMusicSearchItem) -> Unit,
    onYoutubeSelect: (RemoteVideoSearchItem) -> Unit,
    onPreview: (RemoteVideoSearchItem) -> Unit
) {
    Text("Search", style = MaterialTheme.typography.labelLarge)
    val trimmedQuery = query.trim()
    val searchInFlight = searchState.spotifyLoading
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
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
    } else if (searchState.videoMatches.isNotEmpty()) {
        Text("Step 2: Choose the closest YouTube match.", style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(searchState.videoMatches) { item ->
                YoutubeRow(
                    item = item,
                    onSelect = onYoutubeSelect,
                    onPreview = onPreview
                )
            }
        }
    }
}

@Composable
private fun AddYourOwnSection(
    state: UiState,
    onSubmitUrl: (String) -> Unit,
    onClearUrlError: () -> Unit,
    onUploadFile: (Uri) -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    if (state.allowUserUrl) {
        var urlInput by remember { mutableStateOf("") }
        val trimmedUrl = urlInput.trim()
        val urlError = state.search.urlErrorMessage
        val submitUrl = {
            if (trimmedUrl.isNotBlank()) {
                onSubmitUrl(trimmedUrl)
                keyboardController?.hide()
                focusManager.clearFocus()
            }
        }
        Text("By URL", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = urlInput,
            onValueChange = {
                urlInput = it
                if (urlError != null) onClearUrlError()
            },
            label = { Text("YouTube, SoundCloud, or Bandcamp link") },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            isError = urlError != null,
            supportingText = urlError?.let { message ->
                { Text(message, style = MaterialTheme.typography.labelSmall) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submitUrl() }),
            trailingIcon = {
                SquareIconButton(
                    onClick = submitUrl,
                    enabled = trimmedUrl.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "Add by link"
                    )
                }
            },
            shape = SurfaceShape,
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (state.allowUserUpload) {
        val filePicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            onUploadFile(uri)
        }
        Text("By audio file", style = MaterialTheme.typography.labelLarge)
        SubTabCard(
            label = "Upload Audio",
            icon = Icons.Outlined.AudioFile,
            iconTint = LocalThemeTokens.current.titleAccent,
            onClick = { filePicker.launch(uploadMimeTypesForPicker(state.allowedUploadExts)) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SpotifyRow(item: RemoteMusicSearchItem, onSelect: (RemoteMusicSearchItem) -> Unit) {
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
    item: RemoteVideoSearchItem,
    onSelect: (RemoteVideoSearchItem) -> Unit,
    onPreview: (RemoteVideoSearchItem) -> Unit
) {
    val title = item.title ?: "Untitled"
    val videoId = item.id?.trim().orEmpty()
    if (videoId.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onSelect(item) },
                onLongClick = { onPreview(item) }
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
    onUseResult: () -> Unit,
    onOpenYoutube: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onUseResult,
                    colors = pillButtonColors(),
                    border = pillButtonBorder(),
                    shape = PillShape,
                    contentPadding = SmallButtonPadding,
                    modifier = Modifier.height(SmallButtonHeight)
                ) {
                    Text("Use this result", style = MaterialTheme.typography.labelSmall)
                }
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
