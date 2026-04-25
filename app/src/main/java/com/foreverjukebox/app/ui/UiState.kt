package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.data.buildSourceStableTrackId
import com.foreverjukebox.app.data.buildJobStableTrackId
import com.foreverjukebox.app.data.sourceProviderFromRaw
import com.foreverjukebox.app.data.SpotifySearchItem
import com.foreverjukebox.app.data.ThemeMode
import com.foreverjukebox.app.data.TopSongItem
import com.foreverjukebox.app.data.YoutubeSearchItem
import com.foreverjukebox.app.data.FavoriteTrack
import com.foreverjukebox.app.autocanonizer.AutocanonizerData
import com.foreverjukebox.app.engine.VisualizationData
import com.foreverjukebox.app.visualization.JumpLine
import com.foreverjukebox.app.visualization.defaultVisualizationIndex
import kotlinx.serialization.Serializable
import java.net.URI

enum class TabId {
    Input,
    Top,
    Search,
    Play,
    Faq
}

enum class TopSongsTab {
    TopSongs,
    Trending,
    Recent,
    Favorites
}

enum class PlaybackMode {
    Jukebox,
    Autocanonizer
}

enum class JukeboxAudioMode(
    val wireValue: String,
    val label: String,
    val playbackRate: Double
) {
    Off("off", "Off", 1.0),
    Nightcore("nightcore", "Nightcore", 1.2),
    Daycore("daycore", "Daycore", 0.8),
    Vaporwave("vaporwave", "Vaporwave", 0.65),
    EightD("eight_d", "8D Audio", 1.0),
    Lofi("lofi", "Lofi", 1.0);

    companion object {
        fun fromWireValue(value: String?): JukeboxAudioMode? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}

enum class SleepTimerOption(val label: String, val durationMs: Long?) {
    Off("Off", null),
    Minutes15("15 minutes", 15L * 60L * 1000L),
    Minutes30("30 minutes", 30L * 60L * 1000L),
    Minutes45("45 minutes", 45L * 60L * 1000L),
    Hour1("1 hour", 60L * 60L * 1000L),
    Hours2("2 hours", 2L * 60L * 60L * 1000L)
}

data class SleepTimerUiState(
    val selectedOption: SleepTimerOption = SleepTimerOption.Off,
    val remainingMs: Long = 0L,
    val isActive: Boolean = false
)

data class UiState(
    val appMode: AppMode? = null,
    val baseUrl: String = "",
    val castEnabled: Boolean = false,
    val showAppModeGate: Boolean = true,
    val showBaseUrlPrompt: Boolean = false,
    val localSelectedFileName: String? = null,
    val localAnalysisJsonPath: String? = null,
    val localCachedTracks: List<LocalCachedTrack> = emptyList(),
    val localCachedTrackErrorMessage: String? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    val activeTab: TabId = TabId.Top,
    val topSongsTab: TopSongsTab = TopSongsTab.TopSongs,
    val cacheSizeBytes: Long = 0,
    val favorites: List<FavoriteTrack> = emptyList(),
    val favoritesSyncCode: String? = null,
    val allowFavoritesSync: Boolean = false,
    val maxTrackLengthMinutes: Double? = null,
    val trackLengthLimitErrorMessage: String? = null,
    val favoritesSyncLoading: Boolean = false,
    val listenFavoriteToggleInFlight: Boolean = false,
    val versionUpdatePrompt: VersionUpdatePrompt? = null,
    val search: SearchState = SearchState(),
    val playback: PlaybackState = PlaybackState(),
    val tuning: TuningState = TuningState(),
    val sleepTimer: SleepTimerUiState = SleepTimerUiState()
)

data class LocalCachedTrack(
    val localId: String,
    val title: String,
    val artist: String?,
    val sourceUri: String?
)

data class VersionUpdatePrompt(
    val latestVersion: String,
    val downloadUrl: String
)

data class SearchState(
    val query: String = "",
    val topSongs: List<TopSongItem> = emptyList(),
    val topSongsLoading: Boolean = false,
    val trendingSongs: List<TopSongItem> = emptyList(),
    val trendingSongsLoading: Boolean = false,
    val recentSongs: List<TopSongItem> = emptyList(),
    val recentSongsLoading: Boolean = false,
    val spotifyResults: List<SpotifySearchItem> = emptyList(),
    val spotifyLoading: Boolean = false,
    val youtubeMatches: List<YoutubeSearchItem> = emptyList(),
    val youtubeLoading: Boolean = false,
    val pendingTrackName: String? = null,
    val pendingTrackArtist: String? = null
)

data class PlaybackState(
    val playMode: PlaybackMode = PlaybackMode.Jukebox,
    val jukeboxAudioMode: JukeboxAudioMode = JukeboxAudioMode.Off,
    val canonizerFinishOutSong: Boolean = false,
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
    val isPaused: Boolean = false,
    val beatsPlayed: Int = 0,
    val listenTime: String = "00:00:00",
    val trackDurationSeconds: Double? = null,
    val castTotalBeats: Int? = null,
    val castTotalBranches: Int? = null,
    val trackTitle: String? = null,
    val trackArtist: String? = null,
    val vizData: VisualizationData? = null,
    val autocanonizerData: AutocanonizerData? = null,
    val activeVizIndex: Int = defaultVisualizationIndex,
    val currentBeatIndex: Int = -1,
    val canonizerOtherIndex: Int? = null,
    val canonizerTileColorOverrides: Map<Int, String> = emptyMap(),
    val lastJumpFromIndex: Int? = null,
    val jumpLine: JumpLine? = null,
    val lastJobId: String? = null,
    val lastSourceProvider: String? = null,
    val lastSourceId: String? = null,
    val lastStableTrackId: String? = null,
    val lastYouTubeId: String? = null,
    val lastTrackCreatedAtEpochMs: Long? = null,
    val castPlaybackState: String? = null,
    val isCastLoading: Boolean = false,
    val deleteEligible: Boolean = false,
    val deleteInFlight: Boolean = false,
    val isCasting: Boolean = false,
    val castDeviceName: String? = null
)

data class TuningState(
    val threshold: Int = 2,
    val minProb: Int = 18,
    val maxProb: Int = 50,
    val ramp: Int = 10,
    val highlightAnchorBranch: Boolean = false,
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

val serverModeTabs: List<TabId> = listOf(TabId.Top, TabId.Search, TabId.Play, TabId.Faq)
val localModeTabs: List<TabId> = listOf(TabId.Input, TabId.Play, TabId.Faq)
val defaultOnboardingMode: AppMode = AppMode.Local

fun tabsForMode(mode: AppMode?): List<TabId> {
    return when (mode) {
        AppMode.Local -> localModeTabs
        AppMode.Server, null -> serverModeTabs
    }
}

fun defaultTabForMode(mode: AppMode?): TabId {
    return when (mode) {
        AppMode.Local -> TabId.Input
        AppMode.Server, null -> TabId.Top
    }
}

fun coerceTabForMode(mode: AppMode?, tabId: TabId): TabId {
    return if (tabsForMode(mode).contains(tabId)) {
        tabId
    } else {
        defaultTabForMode(mode)
    }
}

fun shouldShowAppModeGate(mode: AppMode?): Boolean = mode == null

fun shouldShowBaseUrlPrompt(mode: AppMode?, baseUrl: String): Boolean {
    return mode == AppMode.Server && !isValidBaseUrl(baseUrl)
}

fun shouldShowServerListenActions(mode: AppMode?): Boolean = mode == AppMode.Server

fun shouldShowLocalLoadingCancel(mode: AppMode?, playback: PlaybackState): Boolean {
    return mode == AppMode.Local &&
        !playback.isCasting &&
        (playback.analysisInFlight || playback.analysisCalculating || playback.audioLoading)
}

fun PlaybackState.hasCastTrack(): Boolean {
    return !stableTrackIdOrNull().isNullOrBlank()
}

fun PlaybackState.stableTrackIdOrNull(): String? {
    val stable = lastStableTrackId?.trim().orEmpty()
    if (stable.isNotBlank()) {
        return stable
    }
    val provider = sourceProviderFromRaw(lastSourceProvider)
    val sourceId = lastSourceId?.trim().orEmpty()
    if (provider != null && sourceId.isNotBlank()) {
        return buildSourceStableTrackId(provider, sourceId)
    }
    val youtubeId = lastYouTubeId?.trim().orEmpty()
    if (youtubeId.isNotBlank()) {
        return buildSourceStableTrackId("youtube", youtubeId)
    }
    val jobId = lastJobId?.trim().orEmpty()
    if (jobId.isNotBlank()) {
        return buildJobStableTrackId(jobId)
    }
    return null
}

fun PlaybackState.castControlsReady(): Boolean {
    return isCasting &&
        hasCastTrack() &&
        castPlaybackState != "loading" &&
        analysisErrorMessage.isNullOrBlank()
}

fun shouldShowPlaybackTransport(playback: PlaybackState): Boolean {
    return !playback.isCasting || playback.castControlsReady()
}

fun PlaybackState.castReceiverDetailsReady(): Boolean {
    return !isCasting || castControlsReady()
}

fun resolvePlaybackHeaderTitle(playback: PlaybackState): String? {
    if (playback.isCasting && playback.castPlaybackState == "loading") {
        return "Loading track on cast device..."
    }
    return playback.playTitle.takeIf { it.isNotBlank() }
}

fun PlaybackState.shouldShowCastNotification(): Boolean {
    if (!isCasting) return false
    if (isRunning) return true
    if (!trackTitle.isNullOrBlank() || !trackArtist.isNullOrBlank()) return true
    if (!stableTrackIdOrNull().isNullOrBlank()) return true
    return playTitle.isNotBlank()
}

fun PlaybackState.castNotificationTitle(): String? {
    val title = trackTitle?.takeIf { it.isNotBlank() }
    if (title != null) {
        return title
    }
    val fallback = playTitle.takeIf { it.isNotBlank() } ?: return null
    val split = fallback.substringBefore(" — ").trim()
    return split.ifBlank { fallback }
}

fun shouldCancelLocalAnalysisOnTabChange(
    mode: AppMode?,
    isLocalAnalysisRunning: Boolean,
    targetTab: TabId
): Boolean {
    if (mode != AppMode.Local || !isLocalAnalysisRunning) {
        return false
    }
    return when (targetTab) {
        TabId.Input, TabId.Play, TabId.Faq -> false
        TabId.Top, TabId.Search -> false
    }
}

fun shouldCancelLocalAnalysisOnInputChange(
    mode: AppMode?,
    isLocalAnalysisRunning: Boolean
): Boolean {
    return mode == AppMode.Local && isLocalAnalysisRunning
}

fun stateAfterModeChangeReset(
    current: UiState,
    targetMode: AppMode,
    castEnabled: Boolean
): UiState {
    return current.copy(
        appMode = targetMode,
        showAppModeGate = false,
        showBaseUrlPrompt = shouldShowBaseUrlPrompt(targetMode, current.baseUrl),
        castEnabled = castEnabled,
        localSelectedFileName = null,
        localAnalysisJsonPath = null,
        activeTab = defaultTabForMode(targetMode),
        topSongsTab = TopSongsTab.TopSongs,
        search = SearchState(),
        playback = PlaybackState(),
        tuning = TuningState(highlightAnchorBranch = current.tuning.highlightAnchorBranch)
    )
}

fun stateAfterLocalAnalysisCancel(current: UiState): UiState {
    return current.copy(
        activeTab = TabId.Input,
        localSelectedFileName = null,
        localAnalysisJsonPath = null
    )
}

fun isValidBaseUrl(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return false
    val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return false
    val scheme = parsed.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    return !parsed.host.isNullOrBlank()
}
