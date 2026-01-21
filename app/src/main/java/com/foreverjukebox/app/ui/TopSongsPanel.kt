package com.foreverjukebox.app.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import android.widget.Toast
import kotlinx.coroutines.launch
import com.foreverjukebox.app.data.FavoriteTrack
import com.foreverjukebox.app.data.TopSongItem

@Composable
fun TopSongsPanel(
    items: List<TopSongItem>,
    favorites: List<FavoriteTrack>,
    loading: Boolean,
    activeTab: TopSongsTab,
    onTabSelected: (TopSongsTab) -> Unit,
    onSelect: (String) -> Unit,
    onRemoveFavorite: (String) -> Unit,
    favoritesSyncCode: String?,
    onRefreshSync: () -> Unit,
    onCreateSync: () -> Unit,
    onFetchSync: suspend (String) -> List<FavoriteTrack>?,
    onApplySync: (String, List<FavoriteTrack>) -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val hasSyncCode = !favoritesSyncCode.isNullOrBlank()
    var showSyncMenu by remember { mutableStateOf(false) }
    var showEnterDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var syncInput by remember { mutableStateOf("") }
    var pendingFavorites by remember { mutableStateOf<List<FavoriteTrack>>(emptyList()) }
    var pendingCode by remember { mutableStateOf("") }
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TopSongsTabs(activeTab = activeTab, onTabSelected = onTabSelected)
            if (activeTab == TopSongsTab.TopSongs) {
                Text("Top 20", style = MaterialTheme.typography.labelLarge)
                if (loading) {
                    Text("Loading top songs…", style = MaterialTheme.typography.bodySmall)
                } else if (items.isEmpty()) {
                    Text("No plays recorded yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(items) { index, item ->
                            val title = item.title ?: "Untitled"
                            val artist = item.artist ?: "Unknown"
                            val youtubeId = item.youtubeId ?: return@itemsIndexed
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(youtubeId) },
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    modifier = Modifier.alignByBaseline(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "$title — $artist",
                                    modifier = Modifier
                                        .weight(1f)
                                        .alignByBaseline(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Favorites", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { showSyncMenu = true }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = if (hasSyncCode) Icons.Filled.Cloud else Icons.Filled.CloudOff,
                            contentDescription = "Favorites sync",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (hasSyncCode) {
                        IconButton(onClick = onRefreshSync, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Sync,
                                contentDescription = "Refresh favorites",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showSyncMenu,
                        onDismissRequest = { showSyncMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Enter sync code") },
                            onClick = {
                                showSyncMenu = false
                                showEnterDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (hasSyncCode) "View sync code" else "Create sync code") },
                            onClick = {
                                showSyncMenu = false
                                showCreateDialog = true
                            }
                        )
                    }
                }
                if (favorites.isEmpty()) {
                    Text("No favorites yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(favorites) { item ->
                            val title = item.title.ifBlank { "Untitled" }
                            val artist = item.artist.ifBlank { "" }
                            val display = if (artist.isNotBlank() && artist != "Unknown") {
                                "$title — $artist"
                            } else {
                                title
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(item.uniqueSongId) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = display,
                                    modifier = Modifier
                                        .weight(1f)
                                        .alignByBaseline(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(
                                    onClick = { onRemoveFavorite(item.uniqueSongId) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove favorite",
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

    if (showEnterDialog) {
        AlertDialog(
            onDismissRequest = {
                showEnterDialog = false
                syncInput = ""
            },
            title = { Text("Sync Favorites") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter a 3-word sync code to replace your local favorites.")
                    OutlinedTextField(
                        value = syncInput,
                        onValueChange = { syncInput = it },
                        placeholder = { Text("bison-laser-sunset") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val code = syncInput.trim()
                        if (code.isBlank()) {
                            Toast.makeText(appContext, "Enter a sync code first.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        scope.launch {
                            val result = onFetchSync(code)
                            if (result == null) {
                                Toast.makeText(appContext, "Favorites sync failed.", Toast.LENGTH_SHORT).show()
                            } else {
                                pendingFavorites = result
                                pendingCode = code.lowercase()
                                showEnterDialog = false
                                syncInput = ""
                                showConfirmDialog = true
                            }
                        }
                    }
                ) {
                    Text("Fetch favorites")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showEnterDialog = false
                    syncInput = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreateDialog = false
                syncInput = ""
            },
            title = { Text("Sync Favorites") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Create a short sync code to pull these favorites on another device.")
                    if (hasSyncCode) {
                        OutlinedTextField(
                            value = favoritesSyncCode ?: "",
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = onCreateSync) {
                    Text(if (hasSyncCode) "Create new sync code" else "Create sync code")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showCreateDialog = false
                    syncInput = ""
                }) {
                    Text("Close")
                }
            }
        )
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showConfirmDialog = false
                syncInput = ""
            },
            title = { Text("Replace favorites?") },
            text = { Text("Replace your local favorites with the synced list?") },
            confirmButton = {
                Button(
                    onClick = {
                        onApplySync(pendingCode, pendingFavorites)
                        showConfirmDialog = false
                        Toast.makeText(appContext, "Favorites updated.", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showConfirmDialog = false
                    syncInput = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TopSongsTabs(activeTab: TopSongsTab, onTabSelected: (TopSongsTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        SubTabButton(
            text = "Top Songs",
            active = activeTab == TopSongsTab.TopSongs,
            onClick = { onTabSelected(TopSongsTab.TopSongs) }
        )
        Spacer(modifier = Modifier.weight(1f))
        SubTabButton(
            text = "Favorites",
            active = activeTab == TopSongsTab.Favorites,
            onClick = { onTabSelected(TopSongsTab.Favorites) }
        )
    }
}

@Composable
private fun SubTabButton(text: String, active: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = pillOutlinedButtonColors(active),
        border = pillButtonBorder(),
        contentPadding = SmallButtonPadding,
        shape = PillShape,
        modifier = Modifier.height(SmallButtonHeight)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.width(2.dp))
    }
}
