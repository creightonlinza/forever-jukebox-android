package com.foreverjukebox.app.ui

import com.foreverjukebox.app.autocanonizer.AutocanonizerData
import com.foreverjukebox.app.BuildConfig
import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.data.DEFAULT_MAX_FAVORITES
import com.foreverjukebox.app.data.FavoritePlayMode
import com.foreverjukebox.app.data.FavoriteTrack
import com.foreverjukebox.app.data.ThemeMode
import com.foreverjukebox.app.data.canonicalTrackId
import com.foreverjukebox.app.engine.VisualizationData
import com.foreverjukebox.app.net.CleartextPolicy
import com.foreverjukebox.app.visualization.JumpLine
import com.foreverjukebox.app.visualization.defaultVisualizationIndex
import java.net.URI
import kotlinx.serialization.Serializable

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

// Only autocanonizer is persisted explicitly; jukebox is the implicit default and
// maps to null so untagged/legacy tracks fall back to jukebox on the way back in.
fun PlaybackMode.toFavoritePlayModeOrNull(): FavoritePlayMode? = when (this) {
    PlaybackMode.Autocanonizer -> FavoritePlayMode.Autocanonizer
    PlaybackMode.Jukebox -> null
}

// Legacy favorites predate autocanonizer favorites and decode to a null
// playMode; treat them as jukebox.
fun FavoritePlayMode?.toPlaybackMode(): PlaybackMode = when (this) {
    FavoritePlayMode.Autocanonizer -> PlaybackMode.Autocanonizer
    FavoritePlayMode.Jukebox, null -> PlaybackMode.Jukebox
}

enum class JukeboxAudioMode(
    val wireValue: String,
    val label: String,
    val playbackRate: Double,
    val nativeModeCode: Int,
    val supportsIntensity: Boolean = false
) {
    Off("off", "Off", 1.0, 0),
    Nightcore("nightcore", "Nightcore", 1.2, 1, supportsIntensity = true),
    Daycore("daycore", "Daycore", 0.8, 2, supportsIntensity = true),
    Vaporwave("vaporwave", "Vaporwave", 0.65, 3, supportsIntensity = true),
    EightD("eight_d", "8D Audio", 1.0, 4),
    Lofi("lofi", "Lofi", 1.0, 5),
    EightBit("eight_bit", "8-Bit", 1.0, 6),
    Underwater("underwater", "Underwater", 1.0, 7),
    Cathedral("cathedral", "Cathedral", 1.0, 8),
    Cowbell("cowbell", "More Cowbell", 1.0, 9);

    companion object {
        fun fromWireValue(value: String?): JukeboxAudioMode? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireValue == normalized }
        }
    }
}

object AudioModeIntensity {
    const val MIN = 50
    const val MAX = 150
    const val DEFAULT = 100

    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)

    // Absence/blank/invalid resolves to DEFAULT; a mode that doesn't support
    // intensity ignores the raw value entirely so stale values never carry over.
    fun parse(raw: String?, mode: JukeboxAudioMode?): Int {
        if (raw == null || mode == null || !mode.supportsIntensity) return DEFAULT
        return clamp(raw.trim().toIntOrNull() ?: return DEFAULT)
    }

    // Emitted only when meaningful and non-default, so DEFAULT is always
    // represented by absence in persisted/shared strings.
    fun wireParamOrNull(mode: JukeboxAudioMode?, intensity: Int): String? {
        if (mode == null || !mode.supportsIntensity) return null
        val clamped = clamp(intensity)
        if (clamped == DEFAULT) return null
        return "ai=$clamped"
    }

    fun scaleRate(rate: Double, intensity: Int): Double {
        if (intensity == DEFAULT) return rate
        return 1.0 + (rate - 1.0) * (clamp(intensity) / 100.0)
    }
}

data class AudioModeOption(
    val wireValue: String,
    val label: String
)

val localAudioModeOptions: List<AudioModeOption> = JukeboxAudioMode.entries.map {
    AudioModeOption(wireValue = it.wireValue, label = it.label)
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
    val adminKey: String = "",
    val castEnabled: Boolean = false,
    val showAppModeGate: Boolean = true,
    val showBaseUrlPrompt: Boolean = false,
    val localSelectedFileName: String? = null,
    val localAnalysisJsonPath: String? = null,
    val localCachedTracks: List<LocalCachedTrack> = emptyList(),
    val localCachedTrackErrorMessage: String? = null,
    val localAnalysisSortKey: FavoriteSortKey = FavoriteSortKey.Title,
    val localAnalysisSortDirection: FavoriteSortDirection = FavoriteSortDirection.Ascending,
    val themeMode: ThemeMode = ThemeMode.System,
    val activeTab: TabId = TabId.Top,
    val topSongsTab: TopSongsTab = TopSongsTab.TopSongs,
    val cacheSizeBytes: Long = 0,
    val favorites: List<FavoriteTrack> = emptyList(),
    val favoritesSortKey: FavoriteSortKey = FavoriteSortKey.Title,
    val favoritesSortDirection: FavoriteSortDirection = FavoriteSortDirection.Ascending,
    val favoritesSyncCode: String? = null,
    val allowFavoritesSync: Boolean = false,
    val maxFavorites: Int = DEFAULT_MAX_FAVORITES,
    val maxTrackLengthMinutes: Double? = null,
    val loadingAudioFeedbackEnabled: Boolean = false,
    val trackLengthLimitErrorMessage: String? = null,
    val favoritesSyncLoading: Boolean = false,
    val listenFavoriteToggleInFlight: Boolean = false,
    val versionUpdatePrompt: VersionUpdatePrompt? = null,
    val whatsNewVersionCodeLoaded: Boolean = false,
    val lastShownWhatsNewVersionCode: Int? = null,
    val whatsNewPrompt: WhatsNewPrompt? = null,
    val search: SearchState = SearchState(),
    val playback: PlaybackState = PlaybackState(),
    val playlist: JukeboxPlaylistState = JukeboxPlaylistState(),
    val tuning: TuningState = TuningState(),
    val sleepTimer: SleepTimerUiState = SleepTimerUiState(),
    val fullscreenVisualizationVisible: Boolean = false
)

data class LocalCachedTrack(
    val localId: String,
    val title: String,
    val artist: String?,
    val sourceUri: String?,
    val durationSeconds: Double?
)

data class FailedLoadRetryRequest(
    val trackId: String,
    val title: String?,
    val artist: String?,
    val playAfterLoaded: Boolean
)

data class VersionUpdatePrompt(
    val latestVersion: String,
    val downloadUrl: String
)

data class WhatsNewPrompt(
    val versionCode: Int,
    val title: String,
    val bullets: List<String>
)

data class SearchState(
    val query: String = "",
    val topSongs: List<RemoteSongItem> = emptyList(),
    val topSongsLoading: Boolean = false,
    val topSongsErrorMessage: String? = null,
    val trendingSongs: List<RemoteSongItem> = emptyList(),
    val trendingSongsLoading: Boolean = false,
    val trendingSongsErrorMessage: String? = null,
    val recentSongs: List<RemoteSongItem> = emptyList(),
    val recentSongsLoading: Boolean = false,
    val recentSongsErrorMessage: String? = null,
    val spotifyResults: List<RemoteMusicSearchItem> = emptyList(),
    val spotifyLoading: Boolean = false,
    val videoMatches: List<RemoteVideoSearchItem> = emptyList(),
    val youtubeLoading: Boolean = false,
    val pendingTrackName: String? = null,
    val pendingTrackArtist: String? = null
)

data class AutocanonizerUiState(
    val mainSeconds: Double = 0.0,
    val otherSeconds: Double = 0.0,
    val trackDurationSeconds: Double = 0.0
)

/**
 * Sender-owned phase of pushing a track to the cast receiver. Written only by the sender-side cast
 * code; [reduceCastStatus] never sets it and only clears it once the receiver reports status for
 * [trackId] (or a terminal error), so periodic statuses from the still-playing previous track can't
 * stomp an in-flight transfer.
 */
sealed interface CastTransfer {
    val trackId: String

    /** Audio/analysis PUTs to the relay. [percent] is null when the audio size is unknown. */
    data class Uploading(override val trackId: String, val percent: Int?) : CastTransfer

    /** LOAD sent (or pull registered); waiting for the receiver's first status for this track. */
    data class WaitingForReceiver(override val trackId: String) : CastTransfer
}

data class PlaybackState(
    val playMode: PlaybackMode = PlaybackMode.Jukebox,
    val jukeboxAudioMode: JukeboxAudioMode = JukeboxAudioMode.Off,
    val jukeboxAudioModeIntensity: Int = AudioModeIntensity.DEFAULT,
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
    val playAfterLoaded: Boolean = false,
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
    val autocanonizer: AutocanonizerUiState = AutocanonizerUiState(),
    val activeVizIndex: Int = defaultVisualizationIndex,
    val currentBeatIndex: Int = -1,
    val canonizerOtherIndex: Int? = null,
    val canonizerTileColorOverrides: Map<Int, String> = emptyMap(),
    val lastJumpFromIndex: Int? = null,
    val jumpLine: JumpLine? = null,
    val lastJobId: String? = null,
    val lastYouTubeId: String? = null,
    // Content URI of the currently loaded local track. Non-null marks the track as a Local-mode cast
    // candidate; the relay reports the bare fingerprint (== cacheKey) in the status jobId field, so
    // lastJobId cannot be relied on to identify a local cast. Set on local load, cleared otherwise.
    val localSourceUri: String? = null,
    val lastTrackCreatedAtEpochMs: Long? = null,
    val castPlaybackState: String? = null,
    val isCastLoading: Boolean = false,
    val castTransfer: CastTransfer? = null,
    val castAudioModeWireValue: String = JukeboxAudioMode.Off.wireValue,
    val castAudioModeIntensity: Int = AudioModeIntensity.DEFAULT,
    val castSupportedAudioModes: List<AudioModeOption> = emptyList(),
    val deleteEligible: Boolean = false,
    val deleteInFlight: Boolean = false,
    val isCasting: Boolean = false,
    val castDeviceName: String? = null
)

internal fun AutocanonizerUiState.withCursorTimes(
    mainSeconds: Double,
    otherSeconds: Double
): AutocanonizerUiState {
    return copy(
        mainSeconds = mainSeconds,
        otherSeconds = otherSeconds
    )
}

internal fun AutocanonizerUiState.withResetCursorTimes(): AutocanonizerUiState {
    return copy(
        mainSeconds = 0.0,
        otherSeconds = 0.0
    )
}

internal fun autocanonizerUiStateForTrack(trackDurationSeconds: Double): AutocanonizerUiState {
    return AutocanonizerUiState(trackDurationSeconds = trackDurationSeconds)
}

internal fun playbackStateAfterAutocanonizerPause(playback: PlaybackState): PlaybackState {
    return playback.copy(
        isRunning = false,
        isPaused = true,
        canonizerOtherIndex = null
    )
}

internal fun playbackStateAfterAutocanonizerStop(playback: PlaybackState): PlaybackState {
    return playback.copy(
        isRunning = false,
        isPaused = false,
        canonizerOtherIndex = null,
        autocanonizer = playback.autocanonizer.withResetCursorTimes()
    )
}

internal fun playbackStateAfterAutocanonizerStart(playback: PlaybackState): PlaybackState {
    return playback.copy(
        beatsPlayed = 0,
        currentBeatIndex = -1,
        canonizerOtherIndex = null,
        canonizerTileColorOverrides = emptyMap(),
        lastJumpFromIndex = null,
        jumpLine = null,
        autocanonizer = playback.autocanonizer.withResetCursorTimes()
    )
}

data class TuningState(
    val threshold: Int = 2,
    val computedThreshold: Int? = null,
    val minProb: Int = 18,
    val maxProb: Int = 50,
    val ramp: Int = 10,
    val highlightAnchorBranch: Boolean = false,
    val justBackwards: Boolean = false,
    val minJumpDistancePercent: Int = 0,
    val removeSequential: Boolean = false,
    // Receiver-reported track state while casting; when playing on-device the engine
    // owns deleted edges and the anchor branch, and these stay empty/null. The anchor
    // branch id is the `ab` wire-param value (an edge id on the web/receiver engine).
    val deletedEdgeIds: List<Int> = emptyList(),
    val anchorBranchId: Int? = null
)

@Serializable
data class TrackMetaJson(
    val title: String? = null,
    val artist: String? = null,
    val duration: Double? = null
)

data class LoadingTrackMetadata(
    val title: String,
    val artist: String?
)

val serverModeTabs: List<TabId> = listOf(TabId.Top, TabId.Search, TabId.Play, TabId.Faq)
val localModeTabs: List<TabId> = listOf(TabId.Input, TabId.Play, TabId.Faq)
val defaultOnboardingMode: AppMode = AppMode.Local

fun tabsForMode(mode: AppMode?): List<TabId> {
    if (!BuildConfig.SERVER_MODE_AVAILABLE) {
        return localModeTabs
    }
    return when (mode) {
        AppMode.Local -> localModeTabs
        AppMode.Server, null -> serverModeTabs
    }
}

fun defaultTabForMode(mode: AppMode?): TabId {
    if (!BuildConfig.SERVER_MODE_AVAILABLE) {
        return TabId.Input
    }
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

fun shouldShowAppModeGate(mode: AppMode?): Boolean {
    return BuildConfig.SERVER_MODE_AVAILABLE && mode == null
}

fun shouldShowBaseUrlPrompt(mode: AppMode?, baseUrl: String): Boolean {
    if (!BuildConfig.SERVER_MODE_AVAILABLE) return false
    return mode == AppMode.Server && !isValidBaseUrl(baseUrl)
}

fun shouldShowServerListenActions(mode: AppMode?): Boolean {
    return BuildConfig.SERVER_MODE_AVAILABLE && mode == AppMode.Server
}

fun shouldShowDeleteTrackAction(
    mode: AppMode?,
    playback: PlaybackState,
    adminKey: String
): Boolean {
    return BuildConfig.SERVER_MODE_AVAILABLE &&
        mode == AppMode.Server &&
        !playback.lastJobId.isNullOrBlank() &&
        (playback.deleteEligible || adminKey.isNotBlank())
}

fun shouldShowLocalLoadingCancel(mode: AppMode?, playback: PlaybackState): Boolean {
    return mode == AppMode.Local &&
        playback.analysisInFlight &&
        !playback.audioLoading &&
        playback.analysisMessage != "Loading audio"
}

fun shouldShowPlayAfterLoadedOption(mode: AppMode?, playback: PlaybackState): Boolean {
    return (mode == AppMode.Local || mode == AppMode.Server) &&
        !playback.isCasting &&
        playback.isLoading()
}

fun shouldStartPlayAfterLoaded(playback: PlaybackState): Boolean {
    return playback.playAfterLoaded &&
        !playback.isCasting &&
        playback.audioLoaded &&
        playback.analysisLoaded &&
        !playback.isLoading() &&
        playback.analysisErrorMessage.isNullOrBlank() &&
        !playback.isRunning
}

fun shouldEnablePlayAfterLoadedForPlaylistSkip(state: UiState): Boolean {
    return !state.playback.isCasting &&
        shouldShowActivePlaylistControls(state.playlist)
}

fun PlaybackState.isLoading(): Boolean = analysisInFlight || analysisCalculating || audioLoading

fun PlaybackState.isTrackLoading(): Boolean {
    return isLoading() || isCastLoading || castTransfer != null || castPlaybackState == "loading"
}

fun shouldPlayLoadingAudioFeedback(state: UiState): Boolean {
    return state.loadingAudioFeedbackEnabled &&
        !state.playback.isCasting &&
        state.playback.isLoading()
}

fun resolveLoadingTrackMetadata(
    playback: PlaybackState,
    localSelectedFileName: String?
): LoadingTrackMetadata? {
    val title = playback.trackTitle.takeIfNotBlank()
        ?: localSelectedFileName.takeIfNotBlank()
        ?: return null
    return LoadingTrackMetadata(
        title = title,
        artist = playback.trackArtist.takeIfNotBlank()
    )
}

fun shouldRetryFailedLoadFromTransport(state: UiState): Boolean {
    return BuildConfig.SERVER_MODE_AVAILABLE &&
        state.appMode == AppMode.Server &&
        !state.playback.analysisErrorMessage.isNullOrBlank() &&
        !state.playback.isTrackLoading() &&
        !state.playback.shareTrackIdOrNull().isNullOrBlank()
}

fun canPlayLoadedTrackFromMemory(playback: PlaybackState): Boolean {
    return !playback.analysisErrorMessage.isNullOrBlank() &&
        !playback.isCasting &&
        playback.audioLoaded &&
        playback.analysisLoaded &&
        !playback.isTrackLoading()
}

fun shouldKeepFailedLoadNotificationVisible(state: UiState): Boolean {
    return shouldRetryFailedLoadFromTransport(state) &&
        !canPlayLoadedTrackFromMemory(state.playback)
}

fun failedLoadRetryRequest(playback: PlaybackState): FailedLoadRetryRequest? {
    val trackId = playback.shareTrackIdOrNull() ?: return null
    return FailedLoadRetryRequest(
        trackId = trackId,
        title = playback.trackTitle,
        artist = playback.trackArtist,
        playAfterLoaded = playback.playAfterLoaded
    )
}

internal fun shouldBlockPlaybackChangeWhileLoading(playback: PlaybackState): Boolean {
    return playback.isTrackLoading()
}

fun shouldShowFullscreenVisualization(playback: PlaybackState): Boolean {
    return !playback.isCasting &&
        playback.audioLoaded &&
        playback.analysisLoaded &&
        playback.analysisErrorMessage.isNullOrBlank()
}

fun stateAfterFullscreenVisualizationOpen(current: UiState): UiState {
    return if (shouldShowFullscreenVisualization(current.playback)) {
        current.copy(fullscreenVisualizationVisible = true)
    } else {
        current
    }
}

fun stateAfterFullscreenVisualizationClose(current: UiState): UiState {
    return current.copy(fullscreenVisualizationVisible = false)
}

fun stateAfterFullscreenVisualizationSync(current: UiState): UiState {
    return if (
        current.fullscreenVisualizationVisible &&
        !shouldShowFullscreenVisualization(current.playback)
    ) {
        stateAfterFullscreenVisualizationClose(current)
    } else {
        current
    }
}

fun PlaybackState.hasCastTrack(): Boolean {
    return !lastJobId.isNullOrBlank()
}

fun PlaybackState.shareTrackIdOrNull(): String? {
    val jobId = lastJobId?.trim().orEmpty()
    if (jobId.isNotBlank()) {
        return jobId
    }
    return null
}

fun PlaybackState.reusableTrackIdsForMatching(): Set<String> {
    val ids = linkedSetOf<String>()
    canonicalTrackId(lastJobId)?.let(ids::add)
    canonicalTrackId(lastYouTubeId)?.let(ids::add)
    return ids
}

fun PlaybackState.castControlsReady(): Boolean {
    return isCasting &&
        hasCastTrack() &&
        castTransfer == null &&
        castPlaybackState != "loading" &&
        analysisErrorMessage.isNullOrBlank()
}

fun shouldShowPlaybackTransport(playback: PlaybackState): Boolean {
    return !playback.isCasting || playback.castControlsReady()
}

/** What the cast screen's status area should show; null renders the idle cast content. */
sealed interface CastScreenStatus {
    data class Failed(val message: String, val canRetry: Boolean) : CastScreenStatus
    data class Analyzing(val progress: Int?, val message: String?, val showCancel: Boolean) : CastScreenStatus
    data class Uploading(val percent: Int?) : CastScreenStatus
    data object WaitingForReceiver : CastScreenStatus
}

fun resolveCastScreenStatus(mode: AppMode?, playback: PlaybackState): CastScreenStatus? {
    if (!playback.isCasting) return null
    val error = playback.analysisErrorMessage
    if (!error.isNullOrBlank()) {
        // Retry only makes sense for errors from the cast pipeline (upload/pull/receiver), which
        // set castPlaybackState = "error"; local analysis failures have nothing to re-send.
        return CastScreenStatus.Failed(error, canRetry = playback.castPlaybackState == "error")
    }
    if (playback.analysisInFlight || playback.analysisCalculating) {
        return CastScreenStatus.Analyzing(
            progress = playback.analysisProgress,
            message = if (playback.analysisCalculating) {
                "Calculating pathways"
            } else {
                playback.analysisMessage ?: "Processing audio"
            },
            showCancel = shouldShowLocalLoadingCancel(mode, playback)
        )
    }
    return when (val transfer = playback.castTransfer) {
        is CastTransfer.Uploading -> CastScreenStatus.Uploading(transfer.percent)
        is CastTransfer.WaitingForReceiver -> CastScreenStatus.WaitingForReceiver
        // Server-mode / receiver-side loading arrives via receiver status alone.
        null -> if (playback.hasCastTrack() && (playback.isCastLoading || playback.castPlaybackState == "loading")) {
            CastScreenStatus.WaitingForReceiver
        } else {
            null
        }
    }
}

fun PlaybackState.castReceiverDetailsReady(): Boolean {
    return !isCasting || castControlsReady()
}

fun PlaybackState.shouldShowCastNotification(): Boolean {
    if (!isCasting) return false
    if (isRunning) return true
    if (!trackTitle.isNullOrBlank() || !trackArtist.isNullOrBlank()) return true
    if (!shareTrackIdOrNull().isNullOrBlank()) return true
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
    val resolvedTargetMode = if (BuildConfig.SERVER_MODE_AVAILABLE) targetMode else AppMode.Local
    return current.copy(
        appMode = resolvedTargetMode,
        showAppModeGate = false,
        showBaseUrlPrompt = shouldShowBaseUrlPrompt(resolvedTargetMode, current.baseUrl),
        castEnabled = castEnabled,
        localSelectedFileName = null,
        localAnalysisJsonPath = null,
        activeTab = defaultTabForMode(resolvedTargetMode),
        topSongsTab = TopSongsTab.TopSongs,
        search = SearchState(),
        playback = PlaybackState(),
        playlist = JukeboxPlaylistState(),
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

/**
 * Cancel a local analysis started while casting: clear only the analysis fields and the provisional
 * new-track metadata so the next receiver status backfills the still-playing track. The cast session
 * itself is untouched.
 */
fun stateAfterCastAnalysisCancel(current: UiState): UiState {
    return current.copy(
        localSelectedFileName = null,
        localAnalysisJsonPath = null,
        playback = current.playback.copy(
            analysisProgress = null,
            analysisMessage = null,
            analysisErrorMessage = null,
            analysisInFlight = false,
            analysisCalculating = false,
            trackTitle = null,
            trackArtist = null,
            playTitle = ""
        )
    )
}

private fun String?.takeIfNotBlank(): String? = this?.trim()?.takeIf { it.isNotBlank() }

fun isValidBaseUrl(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return false
    val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return false
    val scheme = parsed.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    return !parsed.host.isNullOrBlank()
}

/**
 * True when [value] is a valid http:// base URL whose host is not recognizably local. Such URLs
 * are saveable — a public-looking name can still resolve to a private address (split DNS), which
 * CleartextGuardInterceptor permits — but requests to genuinely public addresses will be blocked,
 * so the server URL dialogs surface a warning at entry time.
 */
fun isCleartextUrlToUnrecognizedHost(value: String): Boolean {
    val trimmed = value.trim()
    if (!isValidBaseUrl(trimmed)) return false
    val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return false
    if (!parsed.scheme.equals("http", ignoreCase = true)) return false
    val host = parsed.host ?: return false
    return !CleartextPolicy.isKnownLocalHost(host)
}
