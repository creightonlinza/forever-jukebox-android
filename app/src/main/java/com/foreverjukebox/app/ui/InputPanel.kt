package com.foreverjukebox.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InputPanel(
    state: UiState,
    onOpenFile: (Uri, String?) -> Unit,
    onOpenCachedTrack: (String) -> Unit,
    onAddCachedTrackToPlaylist: (String) -> Unit,
    onDeleteCachedTrack: (String) -> Unit,
    onSortChange: (FavoriteSortKey, FavoriteSortDirection) -> Unit
) {
    val context = LocalContext.current
    var pendingDeleteLocalId by remember { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val title = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
        onOpenFile(uri, title)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SubTabCard(
                    label = "Add Audio",
                    icon = Icons.Outlined.AudioFile,
                    iconTint = LocalThemeTokens.current.titleAccent,
                    onClick = { filePicker.launch(arrayOf("audio/*")) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Completed Analysis", style = MaterialTheme.typography.labelLarge)
                if (state.localCachedTracks.isEmpty()) {
                    Text(
                        text = "No completed analyses yet.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    var query by remember { mutableStateOf("") }
                    val sortKey = state.localAnalysisSortKey
                    val sortDirection = state.localAnalysisSortDirection
                    val trimmedQuery = query.trim()
                    val filtered = filterLocalCachedTracks(state.localCachedTracks, query)
                    val sorted = sortLocalCachedTracksForDisplay(filtered, sortKey, sortDirection)
                    val focusRequester = remember { FocusRequester() }
                    val keyboardController = LocalSoftwareKeyboardController.current
                    val onSortSelected: (FavoriteSortKey) -> Unit = { selectedKey ->
                        if (sortKey == selectedKey) {
                            onSortChange(sortKey, sortDirection.toggled())
                        } else {
                            onSortChange(selectedKey, FavoriteSortDirection.Ascending)
                        }
                    }

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search completed analysis") },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        trailingIcon = {
                            if (trimmedQuery.isNotEmpty()) {
                                SquareIconButton(
                                    onClick = {
                                        query = ""
                                        focusRequester.requestFocus()
                                        keyboardController?.show()
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Clear completed analysis search"
                                    )
                                }
                            }
                        },
                        shape = SurfaceShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                    if (sorted.isEmpty()) {
                        Text(
                            text = "No completed analyses match \"$trimmedQuery\".",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CompletedAnalysisSortHeaderCell(
                                text = "Title",
                                column = FavoriteSortKey.Title,
                                sortKey = sortKey,
                                sortDirection = sortDirection,
                                onSortSelected = onSortSelected,
                                modifier = Modifier.weight(1f, fill = true)
                            )
                            CompletedAnalysisSortHeaderCell(
                                text = "Artist",
                                column = FavoriteSortKey.Artist,
                                sortKey = sortKey,
                                sortDirection = sortDirection,
                                onSortSelected = onSortSelected,
                                modifier = Modifier.weight(0.8f, fill = true)
                            )
                            Spacer(modifier = Modifier.width(24.dp))
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = true),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(sorted, key = { it.localId }) { track ->
                                val displayTitle = track.title.ifBlank { "Untitled" }
                                val displayArtist = favoriteDisplayArtist(track.artist)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { onOpenCachedTrack(track.localId) },
                                            onLongClick = { onAddCachedTrackToPlaylist(track.localId) }
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f, fill = true)) {
                                        Text(
                                            text = displayTitle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        track.durationSeconds?.let { seconds ->
                                            Text(
                                                text = formatTrackDuration(seconds),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (track.sourceUri.isNullOrBlank()) {
                                            Text(
                                                text = "Not linked to a source file. Use Add Audio to re-link.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Text(
                                        text = displayArtist,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(0.8f, fill = true)
                                    )
                                    SquareIconButton(
                                        onClick = { pendingDeleteLocalId = track.localId },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Delete completed analysis",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(12.dp)
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
    pendingDeleteLocalId?.let { localId ->
        DeleteTrackDialog(
            onDismiss = { pendingDeleteLocalId = null },
            onConfirm = {
                pendingDeleteLocalId = null
                onDeleteCachedTrack(localId)
            }
        )
    }
}

@Composable
private fun CompletedAnalysisSortHeaderCell(
    text: String,
    column: FavoriteSortKey,
    sortKey: FavoriteSortKey,
    sortDirection: FavoriteSortDirection,
    onSortSelected: (FavoriteSortKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val active = sortKey == column
    Row(
        modifier = modifier
            .clickable { onSortSelected(column) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (active) {
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = if (sortDirection == FavoriteSortDirection.Ascending) {
                    "Sorted ascending"
                } else {
                    "Sorted descending"
                },
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer {
                        rotationZ = if (sortDirection == FavoriteSortDirection.Ascending) {
                            180f
                        } else {
                            0f
                        }
                    }
            )
        }
    }
}
