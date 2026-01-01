package com.foreverjukebox.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foreverjukebox.app.data.SpotifySearchItem
import com.foreverjukebox.app.data.ThemeMode
import com.foreverjukebox.app.data.TopSongItem
import com.foreverjukebox.app.data.YoutubeSearchItem
import com.foreverjukebox.app.visualization.JukeboxVisualization
import com.foreverjukebox.app.visualization.positioners
import kotlinx.coroutines.delay

@Composable
fun ForeverJukeboxApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    ForeverJukeboxTheme(mode = state.themeMode) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(16.dp)
        ) {
            HeaderBar(
                state = state,
                onEditBaseUrl = { viewModel.setBaseUrl(it) },
                onThemeChange = viewModel::setThemeMode
            )
            Spacer(modifier = Modifier.height(12.dp))
            TabBar(
                state = state,
                onTabSelected = viewModel::setActiveTab
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (state.activeTab) {
                TabId.Top -> TopSongsPanel(
                    items = state.topSongs,
                    loading = state.topSongsLoading,
                    onSelect = {
                        viewModel.loadTrackByYoutubeId(it)
                    }
                )
                TabId.Search -> SearchPanel(
                    state = state,
                    onSearch = viewModel::runSpotifySearch,
                    onSpotifySelect = viewModel::fetchYoutubeMatches,
                    onYoutubeSelect = viewModel::startYoutubeAnalysis
                )
                TabId.Play -> PlayPanel(state = state, viewModel = viewModel)
                TabId.Faq -> FaqPanel()
            }
        }

        if (state.showBaseUrlPrompt) {
            BaseUrlDialog(
                initialValue = state.baseUrl,
                onSave = viewModel::setBaseUrl
            )
        }
    }
}

private val SmallButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
private val SmallButtonHeight = 32.dp
private val SmallFieldMinHeight = 40.dp
private val neonFontFamily = FontFamily(Font(resId = com.foreverjukebox.app.R.font.tilt_neon_regular))

@Composable
private fun HeaderBar(
    state: UiState,
    onEditBaseUrl: (String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        val transition = rememberInfiniteTransition(label = "neonFlicker")
        val flicker = transition.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 6000
                    1f at 0
                    0.95f at 120
                    0.75f at 180
                    1f at 240
                    0.85f at 360
                    1f at 420
                    0.92f at 720
                    1f at 780
                    0.88f at 1680
                    1f at 1740
                    0.7f at 2640
                    1f at 2760
                    0.9f at 3480
                    1f at 3540
                    0.8f at 4560
                    1f at 4620
                    0.86f at 5340
                    1f at 5400
                    1f at 6000
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "flicker"
        ).value
        val glow = 0.45f + (0.55f * flicker)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "THE FOREVER JUKEBOX",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = neonFontFamily,
                    letterSpacing = 2.sp,
                    shadow = Shadow(
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = glow),
                        offset = Offset(0f, 0f),
                        blurRadius = 18f * glow
                    )
                ),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.9f * flicker),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { showSettings = true },
                modifier = Modifier.size(SmallButtonHeight)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onThemeChange = onThemeChange,
            onEditBaseUrl = onEditBaseUrl
        )
    }
}

@Composable
private fun SettingsDialog(
    state: UiState,
    onDismiss: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onEditBaseUrl: (String) -> Unit
) {
    var urlInput by remember(state.baseUrl) { mutableStateOf(state.baseUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    onEditBaseUrl(urlInput)
                    onDismiss()
                },
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("Save", style = MaterialTheme.typography.labelSmall)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("Cancel", style = MaterialTheme.typography.labelSmall)
            }
        },
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("API Base URL") },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    modifier = Modifier.heightIn(min = SmallFieldMinHeight)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Theme:")
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { onThemeChange(ThemeMode.Light) },
                        contentPadding = SmallButtonPadding,
                        modifier = Modifier.height(SmallButtonHeight)
                    ) {
                        Text("Light", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { onThemeChange(ThemeMode.Dark) },
                        contentPadding = SmallButtonPadding,
                        modifier = Modifier.height(SmallButtonHeight)
                    ) {
                        Text("Dark", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { onThemeChange(ThemeMode.System) },
                        contentPadding = SmallButtonPadding,
                        modifier = Modifier.height(SmallButtonHeight)
                    ) {
                        Text("System", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    )
}

@Composable
private fun TabBar(state: UiState, onTabSelected: (TabId) -> Unit) {
    val activeTab = state.activeTab
    val playEnabled = state.lastYouTubeId != null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TabButton("Top Songs", activeTab == TabId.Top) { onTabSelected(TabId.Top) }
        TabButton("Search", activeTab == TabId.Search) { onTabSelected(TabId.Search) }
        TabButton("Listen", activeTab == TabId.Play, enabled = playEnabled) { onTabSelected(TabId.Play) }
        TabButton("FAQ", activeTab == TabId.Faq) { onTabSelected(TabId.Faq) }
    }
}

@Composable
private fun TabButton(text: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = if (active) {
        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    } else {
        ButtonDefaults.outlinedButtonColors()
    }
    if (active) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = colors,
            contentPadding = SmallButtonPadding,
            modifier = Modifier.height(SmallButtonHeight)
        ) { Text(text, style = MaterialTheme.typography.labelSmall) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            contentPadding = SmallButtonPadding,
            modifier = Modifier.height(SmallButtonHeight)
        ) { Text(text, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun TopSongsPanel(items: List<TopSongItem>, loading: Boolean, onSelect: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Top 20", style = MaterialTheme.typography.labelLarge)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            } else if (items.isEmpty()) {
                Text("No plays recorded yet.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(items) { index, item ->
                        val title = item.title ?: "Untitled"
                        val artist = item.artist ?: "Unknown"
                        val youtubeId = item.youtubeId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = youtubeId != null) {
                                    if (youtubeId != null) {
                                        onSelect(youtubeId)
                                    }
                                },
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "$title — $artist",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPanel(
    state: UiState,
    onSearch: (String) -> Unit,
    onSpotifySelect: (String, String, Double) -> Unit,
    onYoutubeSelect: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Search", style = MaterialTheme.typography.labelLarge)
            val stepLabel = if (state.youtubeMatches.isNotEmpty()) {
                "Step 2: Choose the closest YouTube match."
            } else {
                "Step 1: Find a Spotify track."
            }
            Text(stepLabel)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = SmallFieldMinHeight),
                    placeholder = { Text("Search by artist or track") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        focusedBorderColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f),
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.tertiary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onSearch(query) },
                    contentPadding = SmallButtonPadding,
                    modifier = Modifier.height(SmallButtonHeight)
                ) {
                    Text("Search", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (state.searchLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Text("Searching Spotify…")
                }
            } else if (state.searchResults.isNotEmpty()) {
                Text("Spotify matches", fontWeight = FontWeight.SemiBold)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.searchResults) { item ->
                        SpotifyRow(item = item, onSelect = onSpotifySelect)
                    }
                }
            }

            if (state.youtubeLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Text("Searching YouTube…")
                }
            } else if (state.youtubeMatches.isNotEmpty()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.youtubeMatches) { item ->
                        YoutubeRow(item = item, onSelect = onYoutubeSelect)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifyRow(item: SpotifySearchItem, onSelect: (String, String, Double) -> Unit) {
    val name = item.name ?: "Untitled"
    val artist = item.artist ?: ""
    val duration = item.duration
    val label = if (artist.isNotBlank()) "$name — $artist" else name
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = duration != null) {
                if (duration != null) {
                    onSelect(name, artist, duration)
                }
            },
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
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

@Composable
private fun PlayPanel(state: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val view = LocalView.current
    var showTuning by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showVizMenu by remember { mutableStateOf(false) }
    val vizLabels = listOf("Orbit", "Spiral", "Grid", "Wave", "Infinity", "Bloom")
    var jumpLine by remember { mutableStateOf(state.jumpLine) }
    val fullscreenLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@rememberLauncherForActivityResult
        }
        val nextIndex = result.data?.getIntExtra(
            FullscreenActivity.EXTRA_RESULT_VIZ_INDEX,
            state.activeVizIndex
        ) ?: return@rememberLauncherForActivityResult
        viewModel.setActiveVisualization(nextIndex)
    }

    LaunchedEffect(state.jumpLine) {
        if (state.jumpLine != null) {
            jumpLine = state.jumpLine
        }
    }

    LaunchedEffect(jumpLine) {
        val current = jumpLine ?: return@LaunchedEffect
        delay(1100)
        if (jumpLine?.startedAt == current.startedAt) {
            jumpLine = null
        }
    }

    LaunchedEffect(state.isRunning) {
        view.keepScreenOn = state.isRunning
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.analysisProgress != null || state.analysisInFlight) {
            val progress = state.analysisProgress
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(72.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = progress?.let { "${it}%" } ?: "…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        if (state.playTitle.isNotBlank()) {
            Text(
                text = state.playTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        if (state.audioLoaded && state.analysisLoaded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.togglePlayback() },
                    contentPadding = SmallButtonPadding,
                    modifier = Modifier.height(SmallButtonHeight)
                ) {
                    Text(if (state.isRunning) "Stop" else "Play", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = { showTuning = true },
                    contentPadding = SmallButtonPadding,
                    modifier = Modifier.height(SmallButtonHeight)
                ) {
                    Text("Tune", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(
                    onClick = { showInfo = true },
                    modifier = Modifier.size(SmallButtonHeight)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Info",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                IconButton(
                    onClick = {
                        val id = state.lastYouTubeId ?: return@IconButton
                        val url = "foreverjukebox://listen/$id"
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Forever Jukebox link"))
                    },
                    modifier = Modifier.size(SmallButtonHeight)
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Share",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    JukeboxVisualization(
                        data = state.vizData,
                        currentIndex = state.currentBeatIndex,
                        selectedEdge = state.selectedEdge,
                        jumpLine = jumpLine,
                        positioner = positioners.getOrNull(state.activeVizIndex) ?: positioners.first(),
                        onSelectBeat = viewModel::selectBeat,
                        onSelectEdge = viewModel::selectEdge
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showVizMenu = true },
                            contentPadding = SmallButtonPadding,
                            modifier = Modifier.height(SmallButtonHeight)
                        ) {
                            Text(
                                vizLabels.getOrNull(state.activeVizIndex) ?: "Select",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = showVizMenu,
                            onDismissRequest = { showVizMenu = false }
                        ) {
                            vizLabels.forEachIndexed { index, label ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        viewModel.setActiveVisualization(index)
                                        showVizMenu = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            val intent = Intent(context, FullscreenActivity::class.java)
                                .putExtra(FullscreenActivity.EXTRA_VIZ_INDEX, state.activeVizIndex)
                            fullscreenLauncher.launch(intent)
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(SmallButtonHeight)
                    ) {
                        Icon(
                            Icons.Default.Fullscreen,
                            contentDescription = "Fullscreen",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Listen Time: ${state.listenTime}", color = MaterialTheme.colorScheme.onBackground)
                    Text("Total Beats: ${state.beatsPlayed}", color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }

    if (showInfo) {
        val totalBeats = state.vizData?.beats?.size ?: 0
        val totalBranches = state.vizData?.edges?.size ?: 0
        val durationText = state.trackDurationSeconds?.let { formatDuration(it) } ?: "00:00:00"
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = {
                Button(
                    onClick = { showInfo = false },
                    contentPadding = SmallButtonPadding,
                    modifier = Modifier.height(SmallButtonHeight)
                ) {
                    Text("Close", style = MaterialTheme.typography.labelSmall)
                }
            },
            title = { Text("Track Info") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Song Length: $durationText")
                    Text("Total Beats: $totalBeats")
                    Text("Total Branches: $totalBranches")
                }
            }
        )
    }

    if (showTuning) {
        TuningDialog(
            initialThreshold = state.tuningThreshold,
            initialMinProb = state.tuningMinProb,
            initialMaxProb = state.tuningMaxProb,
            initialRamp = state.tuningRamp,
            initialAddLastEdge = state.tuningAddLastEdge,
            initialJustBackwards = state.tuningJustBackwards,
            initialJustLong = state.tuningJustLong,
            initialRemoveSequential = state.tuningRemoveSequential,
            onDismiss = { showTuning = false },
            onApply = viewModel::applyTuning
        )
    }
}

@Composable
private fun TuningDialog(
    initialThreshold: Int,
    initialMinProb: Int,
    initialMaxProb: Int,
    initialRamp: Int,
    initialAddLastEdge: Boolean,
    initialJustBackwards: Boolean,
    initialJustLong: Boolean,
    initialRemoveSequential: Boolean,
    onDismiss: () -> Unit,
    onApply: (
        threshold: Int,
        minProb: Double,
        maxProb: Double,
        ramp: Double,
        addLastEdge: Boolean,
        justBackwards: Boolean,
        justLongBranches: Boolean,
        removeSequentialBranches: Boolean
    ) -> Unit
) {
    var threshold by remember(initialThreshold) { mutableStateOf(initialThreshold.toFloat()) }
    var minProb by remember(initialMinProb) { mutableStateOf(initialMinProb.toFloat()) }
    var maxProb by remember(initialMaxProb) { mutableStateOf(initialMaxProb.toFloat()) }
    var ramp by remember(initialRamp) { mutableStateOf(initialRamp.toFloat()) }
    var addLastEdge by remember(initialAddLastEdge) { mutableStateOf(initialAddLastEdge) }
    var justBackwards by remember(initialJustBackwards) { mutableStateOf(initialJustBackwards) }
    var justLong by remember(initialJustLong) { mutableStateOf(initialJustLong) }
    var removeSequential by remember(initialRemoveSequential) { mutableStateOf(initialRemoveSequential) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val minVal = minProb.coerceAtMost(maxProb) / 100.0
                    val maxVal = maxProb.coerceAtLeast(minProb) / 100.0
                    val rampVal = ramp / 100.0
                    onApply(
                        threshold.toInt(),
                        minVal,
                        maxVal,
                        rampVal,
                        addLastEdge,
                        justBackwards,
                        justLong,
                        removeSequential
                    )
                    onDismiss()
                },
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("Apply", style = MaterialTheme.typography.labelSmall)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("Close", style = MaterialTheme.typography.labelSmall)
            }
        },
        title = { Text("Tuning") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Branch Similarity Threshold: ${threshold.toInt()}")
                Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 0f..80f, steps = 15)
                Text("Branch Probability Min: ${minProb.toInt()}%")
                Slider(value = minProb, onValueChange = { minProb = it }, valueRange = 0f..100f)
                Text("Branch Probability Max: ${maxProb.toInt()}%")
                Slider(value = maxProb, onValueChange = { maxProb = it }, valueRange = 0f..100f)
                Text("Branch Ramp Speed: ${ramp.toInt()}%")
                Slider(value = ramp, onValueChange = { ramp = it }, valueRange = 0f..100f, steps = 10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = addLastEdge, onCheckedChange = { addLastEdge = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Loop extension optimization")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = justBackwards, onCheckedChange = { justBackwards = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Allow only reverse branches")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = justLong, onCheckedChange = { justLong = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Allow only long branches")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = removeSequential, onCheckedChange = { removeSequential = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Remove sequential branches")
                }
            }
        }
    )
}

@Composable
private fun BaseUrlDialog(initialValue: String, onSave: (String) -> Unit) {
    var urlInput by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {
            Button(
                onClick = { onSave(urlInput) },
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("Save", style = MaterialTheme.typography.labelSmall)
            }
        },
        title = { Text("API Base URL") },
        text = {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("Example: http://10.0.2.2:8000") },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                modifier = Modifier.heightIn(min = SmallFieldMinHeight)
            )
        }
    )
}

private fun formatDuration(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, secs)
}

private fun formatDurationShort(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}

@Composable
private fun FaqPanel() {
    val uriHandler = LocalUriHandler.current
    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("FAQ", style = MaterialTheme.typography.labelLarge)
            Text("What the what?", fontWeight = FontWeight.Bold)
            Text("This app lets you search a song on Spotify, match it to YouTube audio, and generate a forever-changing version of the song.")
            Text("How does it work?", fontWeight = FontWeight.Bold)
            Text("The engine on the other end of the API base you provide at app start or in the Settings menu analyzes audio into beats and segments, then plays it beat by beat. At each beat there is a chance to jump to a different part of the song that sounds similar. Similarity uses features like timbre, loudness, duration, and beat position. The visualization shows the possible jump paths for each beat.")
            Text("How can I tune the Jukebox?", fontWeight = FontWeight.Bold)
            Text("Use the Tune button to open the tuning panel. Lower the threshold for higher audio continuity; raise it for more branches. Adjust branch probability min/max and ramp speed to shape how often jumps happen. Use the toggles to allow or restrict certain branch types.")
            Text("Credits", fontWeight = FontWeight.Bold)
            val creditsLine1 = buildAnnotatedString {
                append("The Forever Jukebox & Analysis Engine by ")
                pushStringAnnotation(tag = "URL", annotation = "https://creighton.dev")
                withStyle(linkStyle) {
                    append("Creighton Linza")
                }
                pop()
                append(".")
            }
            ClickableText(
                text = creditsLine1,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                onClick = { offset ->
                    val annotation = creditsLine1.getStringAnnotations("URL", offset, offset).firstOrNull()
                    annotation?.let { link -> uriHandler.openUri(link.item) }
                }
            )
            val creditsLine2 = buildAnnotatedString {
                append("Based off of ")
                pushStringAnnotation(tag = "URL", annotation = "https://musicmachinery.com/")
                withStyle(linkStyle) {
                    append("Paul Lamere")
                }
                pop()
                append("'s original Infinite Jukebox.")
            }
            ClickableText(
                text = creditsLine2,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                onClick = { offset ->
                    val annotation = creditsLine2.getStringAnnotations("URL", offset, offset).firstOrNull()
                    annotation?.let { link -> uriHandler.openUri(link.item) }
                }
            )
        }
    }
}
