package com.foreverjukebox.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.foreverjukebox.app.data.SpotifySearchItem
import com.foreverjukebox.app.data.YoutubeSearchItem

@Composable
fun SearchPanel(
    state: UiState,
    onSearch: (String) -> Unit,
    onSpotifySelect: (SpotifySearchItem) -> Unit,
    onYoutubeSelect: (String) -> Unit
) {
    val searchState = state.search
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
            var query by remember { mutableStateOf("") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search by artist or track") },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = SmallFieldMinHeight)
                )
                Button(
                    onClick = { onSearch(query) },
                    colors = pillButtonColors(),
                    border = pillButtonBorder(),
                    shape = PillShape,
                    contentPadding = SmallButtonPadding,
                    modifier = Modifier.heightIn(min = SmallFieldMinHeight)
                ) {
                    Text("Search", style = MaterialTheme.typography.bodySmall)
                }
            }

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
                        YoutubeRow(item = item, onSelect = onYoutubeSelect)
                    }
                }
            }
        }
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
            text = duration?.let { formatDurationShort(it) } ?: "--:--",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun YoutubeRow(item: YoutubeSearchItem, onSelect: (String) -> Unit) {
    val title = item.title ?: "Untitled"
    val id = item.id ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(id) },
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
            text = item.duration?.let { formatDurationShort(it) } ?: "--:--",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
