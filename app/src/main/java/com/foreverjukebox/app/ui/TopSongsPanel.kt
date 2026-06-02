package com.foreverjukebox.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.foreverjukebox.app.data.trackIdFromTopSong

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TopSongsPanel(
    items: List<TopSongItem>,
    trendingItems: List<TopSongItem>,
    recentItems: List<TopSongItem>,
    favorites: List<FavoriteTrack>,
    loading: Boolean,
    trendingLoading: Boolean,
    recentLoading: Boolean,
    favoritesLoading: Boolean,
    topSongsLimit: Int,
    activeTab: TopSongsTab,
    onTabSelected: (TopSongsTab) -> Unit,
    onRefreshTopSongs: () -> Unit,
    onRefreshTrendingSongs: () -> Unit,
    onRefreshRecentSongs: () -> Unit,
    onRefreshFavorites: () -> Unit,
    onSelect: (String, String?, String?, String?) -> Unit,
    onLongSelect: (String, String?, String?, String?) -> Unit,
    onRemoveFavorite: (String) -> Unit,
    favoritesSyncCode: String?,
    allowFavoritesSync: Boolean,
    onRefreshSync: () -> Unit,
    onCreateSync: () -> Unit,
    onFetchSync: suspend (String) -> List<FavoriteTrack>?,
    onApplySync: (String, List<FavoriteTrack>) -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val hasSyncCode = allowFavoritesSync && !favoritesSyncCode.isNullOrBlank()
    var showSyncMenu by remember { mutableStateOf(false) }
    var showEnterDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var hasAttemptedTopSongsLoad by remember { mutableStateOf(false) }
    var hasAttemptedTrendingLoad by remember { mutableStateOf(false) }
    var hasAttemptedRecentLoad by remember { mutableStateOf(false) }
    var syncInput by remember { mutableStateOf("") }
    var pendingFavorites by remember { mutableStateOf<List<FavoriteTrack>>(emptyList()) }
    var pendingCode by remember { mutableStateOf("") }
    var showCreateButton by remember { mutableStateOf(true) }
    var favoritesQuery by remember { mutableStateOf("") }
    var createHint by remember {
        mutableStateOf("Create a sync code to share your favorites between devices.")
    }

    LaunchedEffect(showCreateDialog) {
        if (showCreateDialog) {
            showCreateButton = true
            createHint = if (hasSyncCode) {
                "Enter this code on another device to sync."
            } else {
                "Create a sync code to share your favorites between devices."
            }
        }
    }
    LaunchedEffect(loading) {
        if (loading) hasAttemptedTopSongsLoad = true
    }
    LaunchedEffect(trendingLoading) {
        if (trendingLoading) hasAttemptedTrendingLoad = true
    }
    LaunchedEffect(recentLoading) {
        if (recentLoading) hasAttemptedRecentLoad = true
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SurfaceShape,
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
                Text("Top $topSongsLimit", style = MaterialTheme.typography.labelLarge)
                PullToRefreshBox(
                    isRefreshing = loading,
                    onRefresh = onRefreshTopSongs,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (items.isNotEmpty()) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(items) { index, item ->
                                val title = item.title
                                val artist = item.artist
                                val displayTitle = title ?: "Untitled"
                                val displayArtist = artist ?: ""
                                val trackId = trackIdFromTopSong(item) ?: return@itemsIndexed
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                onSelect(
                                                    trackId,
                                                    title,
                                                    artist,
                                                    null
                                                )
                                            },
                                            onLongClick = {
                                                onLongSelect(
                                                    trackId,
                                                    title,
                                                    artist,
                                                    null
                                                )
                                            }
                                        ),
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
                                        text = if (displayArtist.isNotBlank()) {
                                            "$displayTitle — $displayArtist"
                                        } else {
                                            displayTitle
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .alignByBaseline(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    } else if (loading || !hasAttemptedTopSongsLoad) {
                        ListLoadingPlaceholder()
                    } else {
                        ListStatusMessage(text = "No plays recorded yet.")
                    }
                }
            } else if (activeTab == TopSongsTab.Trending) {
                Text("Trending", style = MaterialTheme.typography.labelLarge)
                PullToRefreshBox(
                    isRefreshing = trendingLoading,
                    onRefresh = onRefreshTrendingSongs,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (trendingItems.isNotEmpty()) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(trendingItems) { index, item ->
                                val title = item.title
                                val artist = item.artist
                                val displayTitle = title ?: "Untitled"
                                val displayArtist = artist ?: ""
                                val trackId = trackIdFromTopSong(item) ?: return@itemsIndexed
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                onSelect(
                                                    trackId,
                                                    title,
                                                    artist,
                                                    null
                                                )
                                            },
                                            onLongClick = {
                                                onLongSelect(
                                                    trackId,
                                                    title,
                                                    artist,
                                                    null
                                                )
                                            }
                                        ),
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
                                        text = if (displayArtist.isNotBlank()) {
                                            "$displayTitle — $displayArtist"
                                        } else {
                                            displayTitle
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .alignByBaseline(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    } else if (trendingLoading || !hasAttemptedTrendingLoad) {
                        ListLoadingPlaceholder()
                    } else {
                        ListStatusMessage(text = "No trending songs yet.")
                    }
                }
            } else if (activeTab == TopSongsTab.Recent) {
                Text("Last $topSongsLimit Played", style = MaterialTheme.typography.labelLarge)
                PullToRefreshBox(
                    isRefreshing = recentLoading,
                    onRefresh = onRefreshRecentSongs,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (recentItems.isNotEmpty()) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(recentItems) { index, item ->
                                val title = item.title
                                val artist = item.artist
                                val displayTitle = title ?: "Untitled"
                                val displayArtist = artist ?: ""
                                val trackId = trackIdFromTopSong(item) ?: return@itemsIndexed
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                onSelect(
                                                    trackId,
                                                    title,
                                                    artist,
                                                    null
                                                )
                                            },
                                            onLongClick = {
                                                onLongSelect(
                                                    trackId,
                                                    title,
                                                    artist,
                                                    null
                                                )
                                            }
                                        ),
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
                                        text = if (displayArtist.isNotBlank()) {
                                            "$displayTitle — $displayArtist"
                                        } else {
                                            displayTitle
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .alignByBaseline(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    } else if (recentLoading || !hasAttemptedRecentLoad) {
                        ListLoadingPlaceholder()
                    } else {
                        ListStatusMessage(text = "No recent plays yet.")
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Favorites", style = MaterialTheme.typography.labelLarge)
                    if (allowFavoritesSync) {
                        SquareIconButton(onClick = { showSyncMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = if (hasSyncCode) Icons.Outlined.Cloud else Icons.Outlined.CloudOff,
                                contentDescription = "Favorites sync",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showSyncMenu,
                            onDismissRequest = { showSyncMenu = false }
                        ) {
                            if (hasSyncCode) {
                                DropdownMenuItem(
                                    text = { Text("Refresh favorites") },
                                    onClick = {
                                        showSyncMenu = false
                                        onRefreshSync()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (hasSyncCode) "View sync code" else "Create sync code") },
                                onClick = {
                                    showSyncMenu = false
                                    showCreateDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Enter sync code") },
                                onClick = {
                                    showSyncMenu = false
                                    showEnterDialog = true
                                }
                            )
                        }
                    }
                }
                val favoritesPullRefreshEnabled = allowFavoritesSync && hasSyncCode
                if (favoritesPullRefreshEnabled) {
                    PullToRefreshBox(
                        isRefreshing = favoritesLoading,
                        onRefresh = onRefreshFavorites,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FavoritesListContent(
                            favorites = favorites,
                            rawQuery = favoritesQuery,
                            onQueryChange = { favoritesQuery = it },
                            loading = favoritesLoading,
                            showLoadingSpinner = false,
                            onSelect = onSelect,
                            onLongSelect = onLongSelect,
                            onRemoveFavorite = onRemoveFavorite
                        )
                    }
                } else {
                    FavoritesListContent(
                        favorites = favorites,
                        rawQuery = favoritesQuery,
                        onQueryChange = { favoritesQuery = it },
                        loading = favoritesLoading,
                        showLoadingSpinner = true,
                        onSelect = onSelect,
                        onLongSelect = onLongSelect,
                        onRemoveFavorite = onRemoveFavorite
                    )
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
            title = { Text("Favorites Sync") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter the 3-word sync code to pull down your favorites.")
                    OutlinedTextField(
                        value = syncInput,
                        onValueChange = { syncInput = it },
                        placeholder = { Text("the-forever-jukebox") },
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
                    Text("Sync favorites")
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
            title = { Text("Favorites Sync") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(createHint)
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
                if (showCreateButton) {
                    Button(
                        onClick = {
                            showCreateButton = false
                            createHint = "Enter this code on another device to sync."
                            onCreateSync()
                        }
                    ) {
                        Text(if (hasSyncCode) "Create new sync code" else "Create sync code")
                    }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoritesListContent(
    favorites: List<FavoriteTrack>,
    rawQuery: String,
    onQueryChange: (String) -> Unit,
    loading: Boolean,
    showLoadingSpinner: Boolean,
    onSelect: (String, String?, String?, String?) -> Unit,
    onLongSelect: (String, String?, String?, String?) -> Unit,
    onRemoveFavorite: (String) -> Unit
) {
    val trimmedQuery = rawQuery.trim()
    val filteredFavorites = filterFavorites(favorites, rawQuery)
    var sortKey by remember { mutableStateOf(FavoriteSortKey.Title) }
    var sortDirection by remember { mutableStateOf(FavoriteSortDirection.Ascending) }
    val sortedFavorites = sortFavoritesForDisplay(filteredFavorites, sortKey, sortDirection)
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val onSortSelected: (FavoriteSortKey) -> Unit = { selectedKey ->
        if (sortKey == selectedKey) {
            sortDirection = sortDirection.toggled()
        } else {
            sortKey = selectedKey
            sortDirection = FavoriteSortDirection.Ascending
        }
    }

    if (loading) {
        if (showLoadingSpinner) {
            ListStatusMessage(
                text = "Loading favorites…",
                loading = true
            )
        } else {
            ListLoadingPlaceholder()
        }
    } else if (favorites.isEmpty()) {
        ListStatusMessage(text = "No favorites yet.")
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = rawQuery,
                onValueChange = onQueryChange,
                label = { Text("Search favorites") },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = {
                    if (trimmedQuery.isNotEmpty()) {
                        SquareIconButton(
                            onClick = {
                                onQueryChange("")
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            }
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear favorites search"
                            )
                        }
                    }
                },
                shape = SurfaceShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
            if (filteredFavorites.isEmpty()) {
                ListStatusMessage(text = "No favorites match \"$trimmedQuery\".")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FavoritesTableHeader(
                            sortKey = sortKey,
                            sortDirection = sortDirection,
                            onSortSelected = onSortSelected
                        )
                    }
                    items(sortedFavorites) { item ->
                        val title = item.title
                        val artist = item.artist
                        val displayTitle = title.ifBlank { "Untitled" }
                        val displayArtist = favoriteDisplayArtist(artist)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        onSelect(
                                            item.uniqueSongId,
                                            title,
                                            artist,
                                            item.tuningParams
                                        )
                                    },
                                    onLongClick = {
                                        onLongSelect(
                                            item.uniqueSongId,
                                            title,
                                            artist,
                                            item.tuningParams
                                        )
                                    }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f, fill = true)
                                    .alignByBaseline(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = displayTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (!item.tuningParams.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        Icons.Outlined.Tune,
                                        contentDescription = "Custom tuning",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Text(
                                text = displayArtist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(0.8f, fill = true)
                                    .alignByBaseline()
                            )
                            SquareIconButton(
                                onClick = { onRemoveFavorite(item.uniqueSongId) },
                                modifier = Modifier.size(32.dp)
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

@Composable
private fun FavoritesTableHeader(
    sortKey: FavoriteSortKey,
    sortDirection: FavoriteSortDirection,
    onSortSelected: (FavoriteSortKey) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FavoritesSortHeaderCell(
            text = "Title",
            column = FavoriteSortKey.Title,
            sortKey = sortKey,
            sortDirection = sortDirection,
            onSortSelected = onSortSelected,
            modifier = Modifier.weight(1f, fill = true)
        )
        FavoritesSortHeaderCell(
            text = "Artist",
            column = FavoriteSortKey.Artist,
            sortKey = sortKey,
            sortDirection = sortDirection,
            onSortSelected = onSortSelected,
            modifier = Modifier.weight(0.8f, fill = true)
        )
        Spacer(modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun FavoritesSortHeaderCell(
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

internal fun filterFavorites(favorites: List<FavoriteTrack>, rawQuery: String): List<FavoriteTrack> {
    val query = rawQuery.trim().lowercase()
    if (query.isEmpty()) return favorites

    return favorites.filter { favorite ->
        listOf(
            favorite.title,
            favorite.artist,
            favorite.uniqueSongId,
            favorite.sourceType?.name
        ).any { value ->
            value.orEmpty().lowercase().contains(query)
        }
    }
}

internal enum class FavoriteSortKey {
    Title,
    Artist
}

internal enum class FavoriteSortDirection {
    Ascending,
    Descending;

    fun toggled(): FavoriteSortDirection {
        return when (this) {
            Ascending -> Descending
            Descending -> Ascending
        }
    }
}

internal fun sortFavoritesForDisplay(
    favorites: List<FavoriteTrack>,
    sortKey: FavoriteSortKey,
    sortDirection: FavoriteSortDirection
): List<FavoriteTrack> {
    if (favorites.size < 2) return favorites

    return favorites.sortedWith { left, right ->
        compareFavoritesForDisplay(left, right, sortKey, sortDirection)
    }
}

internal fun favoriteDisplayArtist(rawArtist: String?): String {
    val trimmed = rawArtist.orEmpty().trim()
    return if (trimmed.equals("Unknown", ignoreCase = true)) "" else trimmed
}

private fun compareFavoritesForDisplay(
    left: FavoriteTrack,
    right: FavoriteTrack,
    sortKey: FavoriteSortKey,
    sortDirection: FavoriteSortDirection
): Int {
    val secondarySortKey = when (sortKey) {
        FavoriteSortKey.Title -> FavoriteSortKey.Artist
        FavoriteSortKey.Artist -> FavoriteSortKey.Title
    }
    return compareFavoriteField(left, right, sortKey, sortDirection)
        .takeIf { it != 0 }
        ?: compareFavoriteField(left, right, secondarySortKey, sortDirection).takeIf { it != 0 }
        ?: compareFavoriteIds(left.uniqueSongId, right.uniqueSongId)
}

private fun compareFavoriteField(
    left: FavoriteTrack,
    right: FavoriteTrack,
    sortKey: FavoriteSortKey,
    sortDirection: FavoriteSortDirection
): Int {
    val result = String.CASE_INSENSITIVE_ORDER.compare(
        favoriteDisplayValue(left, sortKey),
        favoriteDisplayValue(right, sortKey)
    )
    return when (sortDirection) {
        FavoriteSortDirection.Ascending -> result
        FavoriteSortDirection.Descending -> -result
    }
}

private fun favoriteDisplayValue(favorite: FavoriteTrack, sortKey: FavoriteSortKey): String {
    return when (sortKey) {
        FavoriteSortKey.Title -> favorite.title.ifBlank { "Untitled" }
        FavoriteSortKey.Artist -> favoriteDisplayArtist(favorite.artist)
    }
}

private fun compareFavoriteIds(leftId: String, rightId: String): Int {
    return String.CASE_INSENSITIVE_ORDER.compare(leftId, rightId)
        .takeIf { it != 0 }
        ?: leftId.compareTo(rightId)
}

@Composable
private fun ListLoadingPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
    )
}

@Composable
private fun ListStatusMessage(
    text: String,
    loading: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun TopSongsTabs(activeTab: TopSongsTab, onTabSelected: (TopSongsTab) -> Unit) {
    val tokens = LocalThemeTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        SubTabButton(
            text = "All Time",
            active = activeTab == TopSongsTab.TopSongs,
            onClick = { onTabSelected(TopSongsTab.TopSongs) }
        )
        Spacer(modifier = Modifier.width(8.dp))
        SubTabButton(
            text = "Trending",
            active = activeTab == TopSongsTab.Trending,
            onClick = { onTabSelected(TopSongsTab.Trending) }
        )
        Spacer(modifier = Modifier.width(8.dp))
        SubTabButton(
            text = "Recents",
            active = activeTab == TopSongsTab.Recent,
            onClick = { onTabSelected(TopSongsTab.Recent) }
        )
        Spacer(modifier = Modifier.weight(1f))
        SubTabButton(
            text = "Favorites",
            active = activeTab == TopSongsTab.Favorites,
            icon = Icons.Filled.Star,
            iconTint = tokens.beatFill,
            onClick = { onTabSelected(TopSongsTab.Favorites) }
        )
    }
}

@Composable
private fun SubTabButton(
    text: String,
    active: Boolean,
    icon: ImageVector? = null,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onBackground,
    onClick: () -> Unit
) {
    val tokens = LocalThemeTokens.current
    val containerColor by animateColorAsState(
        targetValue = if (active) tokens.controlSurface else tokens.panelSurface,
        label = "subTabContainer"
    )
    OutlinedButton(
        onClick = onClick,
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = tokens.onBackground
        ),
        border = pillButtonBorder(),
        contentPadding = SmallButtonPadding,
        shape = PillShape,
        modifier = Modifier.height(SmallButtonHeight)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.width(2.dp))
    }
}
