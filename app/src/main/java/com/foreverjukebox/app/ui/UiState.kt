package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.SpotifySearchItem
import com.foreverjukebox.app.data.ThemeMode
import com.foreverjukebox.app.data.TopSongItem
import com.foreverjukebox.app.data.YoutubeSearchItem
import com.foreverjukebox.app.engine.VisualizationData
import com.foreverjukebox.app.visualization.JumpLine
import kotlinx.serialization.Serializable

enum class TabId {
    Top,
    Search,
    Play,
    Faq
}

data class UiState(
    val baseUrl: String = "",
    val showBaseUrlPrompt: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.System,
    val activeTab: TabId = TabId.Top,
    val cacheSizeBytes: Long = 0,
    val search: SearchState = SearchState(),
    val playback: PlaybackState = PlaybackState(),
    val tuning: TuningState = TuningState()
)

data class SearchState(
    val topSongs: List<TopSongItem> = emptyList(),
    val topSongsLoading: Boolean = false,
    val spotifyResults: List<SpotifySearchItem> = emptyList(),
    val spotifyLoading: Boolean = false,
    val youtubeMatches: List<YoutubeSearchItem> = emptyList(),
    val youtubeLoading: Boolean = false,
    val pendingTrackName: String? = null,
    val pendingTrackArtist: String? = null
)

data class PlaybackState(
    val analysisProgress: Int? = null,
    val analysisMessage: String? = null,
    val analysisErrorMessage: String? = null,
    val analysisInFlight: Boolean = false,
    val analysisCalculating: Boolean = false,
    val audioLoading: Boolean = false,
    val playTitle: String = "",
    val audioLoaded: Boolean = false,
    val analysisLoaded: Boolean = false,
    val isRunning: Boolean = false,
    val beatsPlayed: Int = 0,
    val listenTime: String = "00:00:00",
    val trackDurationSeconds: Double? = null,
    val vizData: VisualizationData? = null,
    val activeVizIndex: Int = 0,
    val currentBeatIndex: Int = -1,
    val lastJumpFromIndex: Int? = null,
    val jumpLine: JumpLine? = null,
    val lastYouTubeId: String? = null
)

data class TuningState(
    val threshold: Int = 0,
    val minProb: Int = 18,
    val maxProb: Int = 50,
    val ramp: Int = 10,
    val addLastEdge: Boolean = true,
    val justBackwards: Boolean = false,
    val justLong: Boolean = false,
    val removeSequential: Boolean = false
)

@Serializable
data class TrackMetaJson(
    val title: String? = null,
    val artist: String? = null,
    val duration: Double? = null
)
