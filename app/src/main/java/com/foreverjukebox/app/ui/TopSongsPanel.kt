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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onRemoveFavorite: (String) -> Unit
) {
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
                Text("Favorites", style = MaterialTheme.typography.labelLarge)
                if (favorites.isEmpty()) {
                    Text("No favorites yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(favorites) { item ->
                            val title = item.title.ifBlank { "Untitled" }
                            val artist = item.artist.ifBlank { "Unknown" }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(item.uniqueSongId) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$title — $artist",
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
                                        modifier = Modifier.size(14.dp)
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
