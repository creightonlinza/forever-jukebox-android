package com.foreverjukebox.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foreverjukebox.app.data.TopSongItem

@Composable
fun TopSongsPanel(items: List<TopSongItem>, loading: Boolean, onSelect: (String) -> Unit) {
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
        }
    }
}
