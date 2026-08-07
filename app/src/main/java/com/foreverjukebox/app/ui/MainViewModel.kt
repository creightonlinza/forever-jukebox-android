package com.foreverjukebox.app.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.foreverjukebox.app.AppLog
import com.foreverjukebox.app.BuildConfig
import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.data.AppPreferences
import com.foreverjukebox.app.data.FavoritePlayMode
import com.foreverjukebox.app.data.FavoriteSourceType
import com.foreverjukebox.app.data.FavoriteTrack
import com.foreverjukebox.app.data.HttpStatusException
import com.foreverjukebox.app.data.SOURCE_PROVIDER_YOUTUBE
import com.foreverjukebox.app.data.SavedPlaylistTrack
import com.foreverjukebox.app.data.ThemeMode
import com.foreverjukebox.app.data.canonicalJobId
import com.foreverjukebox.app.data.canonicalTrackId
import com.foreverjukebox.app.data.parseTrackId
import com.foreverjukebox.app.data.sanitizeMaxFavorites
import com.foreverjukebox.app.data.sourceProviderFromRaw
import com.foreverjukebox.app.audio.LoadingAudioFeedbackController
import com.foreverjukebox.app.audio.SoundPoolLoadingAudioFeedbackPlayer
import com.foreverjukebox.app.local.LocalAnalysisService
import com.foreverjukebox.app.playback.ForegroundPlaybackService
import com.foreverjukebox.app.playback.PlaybackControllerHolder
import com.foreverjukebox.app.playback.PlaybackStartResult
import com.foreverjukebox.app.visualization.defaultVisualizationIndex
import com.foreverjukebox.app.visualization.visualizationCount
import com.foreverjukebox.app.visualization.visualizationLabels
import com.foreverjukebox.app.cast.CastRelayClient
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

internal suspend fun tryQueueYoutubeAnalysisForCast(
    baseUrl: String,
    youtubeId: String,
    title: String?,
    artist: String?,
    startAnalysis: suspend (baseUrl: String, youtubeId: String, title: String?, artist: String?) -> TrackAnalysisStartResult
): String? {
    val normalizedBaseUrl = baseUrl.trim()
    if (normalizedBaseUrl.isBlank()) {
        return null
    }
    return runCatching {
        canonicalJobId(startAnalysis(normalizedBaseUrl, youtubeId, title, artist).id)
    }.getOrNull()
}

/**
 * The Local-mode Cast relay is considered configured when both this flavor's receiver app ID and the
 * relay base URL are present (both are compiled-in BuildConfig values), so Local mode gains casting.
 */
private val relayConfigured: Boolean =
    BuildConfig.RELAY_CAST_APP_ID.isNotBlank() && BuildConfig.RELAY_CAST_BASE_URL.isNotBlank()

internal fun resetSearchStateAfterTrackSelection(search: SearchState): SearchState {
    return search.copy(
        query = "",
        spotifyResults = emptyList(),
        videoMatches = emptyList(),
        youtubeLoading = false,
        pendingTrackName = null,
        pendingTrackArtist = null,
        urlErrorMessage = null
    )
}

internal data class RemoteVideoSelection(
    val youtubeId: String,
    val title: String?,
    val artist: String?
)

internal data class TrackMetadata(
    val title: String? = null,
    val artist: String? = null
)

internal fun resolveRemoteVideoSelection(
    item: RemoteVideoSearchItem,
    search: SearchState
): RemoteVideoSelection? {
    val youtubeId = item.id.takeIfNotBlank() ?: return null
    val pendingTitle = search.pendingTrackName.takeIfNotBlank()
    val pendingArtist = search.pendingTrackArtist.takeIfNotBlank()
    // Resolve each field independently so a partial pending state can never silently
    // drop the artist just because the title fell back to the video's own title.
    // The video item carries no artist, so artist can only come from pending metadata.
    val title = pendingTitle ?: item.title.takeIfNotBlank()
    val artist = pendingArtist
    return RemoteVideoSelection(
        youtubeId = youtubeId,
        title = title,
        artist = artist
    )
}

internal fun resolveTrackMeta(
    trackId: String,
    search: SearchState,
    favorites: List<FavoriteTrack>
): TrackMetadata {
    val canonicalTarget = canonicalTrackId(trackId) ?: trackId.trim().ifBlank {
        return TrackMetadata()
    }
    val feedMatch = sequenceOf(
        search.topSongs,
        search.trendingSongs,
        search.recentSongs
    )
        .flatten()
        .firstOrNull { item ->
            canonicalTrackId(trackIdFromRemoteSong(item)) == canonicalTarget ||
                canonicalTrackId(videoTrackIdFromRemoteSong(item)) == canonicalTarget
        }
    if (feedMatch != null) {
        return TrackMetadata(
            title = feedMatch.title.takeIfNotBlank(),
            artist = feedMatch.artist.takeIfNotBlank()
        )
    }
    val favoriteMatch = favorites.firstOrNull {
        canonicalTrackId(it.uniqueSongId) == canonicalTarget
    }
    if (favoriteMatch != null) {
        return TrackMetadata(
            title = favoriteMatch.title.takeIfNotBlank(),
            artist = favoriteMatch.artist.takeIfNotBlank()
        )
    }
    return TrackMetadata()
}

internal fun resolveTrackLoadMetadata(
    trackId: String,
    title: String?,
    artist: String?,
    search: SearchState,
    favorites: List<FavoriteTrack>
): TrackMetadata {
    val normalizedTitle = title.takeIfNotBlank()
    val normalizedArtist = artist.takeIfNotBlank()
    return if (normalizedTitle != null || normalizedArtist != null) {
        TrackMetadata(
            title = normalizedTitle,
            artist = normalizedArtist
        )
    } else {
        resolveTrackMeta(trackId, search, favorites)
    }
}

internal fun normalizedBaseUrlForComparison(value: String?): String {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isBlank()) {
        return ""
    }
    val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed.trimEnd('/')
    val scheme = parsed.scheme?.lowercase().orEmpty()
    val host = parsed.host?.lowercase()
    if (scheme.isBlank() || host.isNullOrBlank()) {
        return trimmed.trimEnd('/')
    }
    val port = if (parsed.port != -1) ":${parsed.port}" else ""
    val path = parsed.path?.trimEnd('/').orEmpty().let { normalizedPath ->
        if (normalizedPath.isBlank() || normalizedPath == "/") "" else normalizedPath
    }
    return "$scheme://$host$port$path"
}

internal fun hasBaseUrlServerChanged(previous: String?, next: String?): Boolean {
    return normalizedBaseUrlForComparison(previous) != normalizedBaseUrlForComparison(next)
}

internal fun shouldReuseLookupJob(response: TrackAnalysisResult?): Boolean {
    val jobId = canonicalJobId(response?.id)
    return response != null &&
        jobId != null
}

internal fun sleepTimerOptionForDurationMs(durationMs: Long?): SleepTimerOption {
    if (durationMs == null || durationMs <= 0L) {
        return SleepTimerOption.Off
    }
    return SleepTimerOption.entries.firstOrNull { option ->
        option.durationMs == durationMs
    } ?: SleepTimerOption.Off
}

internal fun resolveKnownJobIdForSource(
    state: UiState,
    sourceProvider: String,
    sourceId: String
): String? {
    val provider = sourceProviderFromRaw(sourceProvider) ?: return null
    val normalizedSourceId = sourceId.trim()
    if (normalizedSourceId.isBlank()) return null
    val matched = sequenceOf(
        state.search.topSongs,
        state.search.trendingSongs,
        state.search.recentSongs
    )
        .flatten()
        .firstOrNull { item ->
            sourceProviderFromRaw(item.sourceProvider) == provider &&
                item.sourceId?.trim() == normalizedSourceId
        } ?: return null
    return canonicalJobId(matched.id)
}

internal fun favoriteRemovalTrackIdsForDeletion(
    playback: PlaybackState,
    fallbackJobId: String? = null
): Set<String> {
    val trackIds = linkedSetOf<String>()

    fun addCanonical(raw: String?) {
        val canonical = canonicalTrackId(raw) ?: return
        trackIds += canonical
    }

    val youtubeId = playback.lastYouTubeId?.trim().orEmpty()
    if (youtubeId.isNotBlank()) {
        addCanonical(youtubeId)
    }

    val fallback = fallbackJobId?.trim().orEmpty()
    if (fallback.isNotBlank()) {
        addCanonical(canonicalJobId(fallback))
    }
    val lastJobId = playback.lastJobId?.trim().orEmpty()
    if (lastJobId.isNotBlank()) {
        addCanonical(canonicalJobId(lastJobId))
    }

    return trackIds
}

internal fun removeFavoritesForTrackIds(
    favorites: List<FavoriteTrack>,
    trackIds: Set<String>
): List<FavoriteTrack> {
    if (trackIds.isEmpty()) return favorites
    return favorites.filterNot { favorite ->
        val canonical = canonicalTrackId(favorite.uniqueSongId)
        canonical != null && canonical in trackIds
    }
}

private fun buildPlaybackTitle(
    title: String?,
    artist: String?,
    playMode: PlaybackMode,
    audioMode: JukeboxAudioMode
): String {
    if (title.isNullOrBlank()) {
        return ""
    }
    val resolvedTitle = when {
        playMode == PlaybackMode.Autocanonizer -> "$title (autocanonized)"
        audioMode != JukeboxAudioMode.Off -> "$title (${audioMode.wireValue})"
        else -> title
    }
    return if (artist.isNullOrBlank()) {
        resolvedTitle
    } else {
        "$resolvedTitle — $artist"
    }
}

private fun String?.takeIfNotBlank(): String? = this?.trim()?.takeIf { it.isNotBlank() }

private data class CrashContextKeys(
    val appMode: String,
    val playMode: String,
    val casting: String,
    val viz: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val serverGateway = createServerGateway()
    private val analytics = createAnalyticsGateway(application)
    private val diagnostics = createDiagnosticsGateway(application)
    private val controller = PlaybackControllerHolder.get(application)
    private val engine = controller.engine
    private val defaultConfig = engine.getConfig()
    private val json = Json { ignoreUnknownKeys = true }
    private val localAnalysisService = LocalAnalysisService.create(application)
    private val loadingAudioFeedbackController = LoadingAudioFeedbackController(
        SoundPoolLoadingAudioFeedbackPlayer(application, viewModelScope)
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var appConfigLoaded = false
    private var foregroundRecoveryInFlight = false
    private var castSelectionJob: Job? = null
    private var pendingDeepLinkUriString: String? = null
    private var pendingShare: PendingShare? = null
    private var baseUrlLoaded = false
    private var serverConfigState = ServerConfigState.Pending
    private var savedPlaylistTracks: List<SavedPlaylistTrack> = emptyList()
    private var lastCowbellBeatsPlayed = -1
    private val tabHistory = ArrayDeque<TabId>()
    private val castController = CastController(getApplication())
    private val castRelayClient = CastRelayClient()
    private val castPlaybackCoordinator = CastPlaybackCoordinator(
        castController = castController,
        getState = { state.value },
        updateState = { updater -> _state.update(updater) },
        onCastUnavailable = ::notifyCastUnavailable,
        onSyncCastNotification = ::syncCastNotification,
        castTrackLengthLimitErrorMessage = ::castTrackLengthLimitErrorMessage,
        scope = viewModelScope,
        castRelayClient = castRelayClient,
        buildUploadSource = CastLocalUploadSourceFactory(::buildCastLocalUploadSource),
        relayBaseUrl = BuildConfig.RELAY_CAST_BASE_URL
    )
    private val searchCoordinator = createRemoteSearchController(
        scope = viewModelScope,
        serverGateway = serverGateway,
        getState = { state.value },
        updateSearchState = ::updateSearchState,
        setSearchQuery = ::setSearchQuery,
        logError = { message, error -> AppLog.warn(TAG, message, error) }
    )
    private val favoritesController = FavoritesController(
        scope = viewModelScope,
        serverGateway = serverGateway,
        preferences = preferences,
        getState = { state.value },
        updateState = { updater -> _state.update(updater) },
        showToast = ::showToast
    )
    private val playbackCoordinator = PlaybackCoordinator(
        application = getApplication(),
        scope = viewModelScope,
        serverGateway = serverGateway,
        controller = controller,
        engine = engine,
        json = json,
        defaultConfig = defaultConfig,
        getState = { state.value },
        updateState = { updater -> _state.update(updater) },
        updatePlaybackState = ::updatePlaybackState,
        applyActiveTab = ::applyActiveTab,
        onStableTrackLoaded = ::handleStableTrackLoaded
    )
    private val remoteTrackLoadCoordinator = RemoteTrackLoadCoordinator(
        scope = viewModelScope,
        playbackCoordinator = playbackCoordinator,
        getState = { state.value }
    )
    private val localAnalysisCoordinator = LocalAnalysisCoordinator(
        scope = viewModelScope,
        application = getApplication(),
        localAnalysisService = localAnalysisService,
        controller = controller,
        playbackCoordinator = playbackCoordinator,
        castPlaybackCoordinator = castPlaybackCoordinator,
        getState = { state.value },
        updateState = { updater ->
            _state.update(updater)
            hydrateSavedPlaylistIfInactive()
        },
        applyActiveTab = ::applyActiveTab,
        // error (not warn): local analysis failure is the core on-device path and this
        // lambda holds the real exception; the surfaced-message non-fatal from
        // PlaybackCoordinator.setAnalysisError has no stack trace.
        logError = { message, error -> AppLog.error(TAG, message, error) },
        diagnostics = diagnostics
    )
    private val tuningCoordinator = TuningCoordinator(
        engine = engine,
        defaultConfig = defaultConfig,
        preferences = preferences,
        playbackCoordinator = playbackCoordinator,
        castPlaybackCoordinator = castPlaybackCoordinator,
        getState = { state.value },
        updateState = { updater -> _state.update(updater) },
        randomBranchDeltaPercentScale = RANDOM_BRANCH_DELTA_PERCENT_SCALE,
        persistLocalTrackTuning = { localId, params -> localAnalysisService.saveTuning(localId, params) }
    )
    private val castSessionCoordinator = CastSessionCoordinator(
        controller = controller,
        castPlaybackCoordinator = castPlaybackCoordinator,
        playbackCoordinator = playbackCoordinator,
        cancelServerTrackLoad = remoteTrackLoadCoordinator::cancel,
        getState = { state.value },
        updateState = { updater -> _state.update(updater) },
        applyActiveTab = ::applyActiveTab,
        syncCastNotification = ::syncCastNotification
    )
    private val listenLinkCoordinator = ListenLinkCoordinator(
        engineTuningParams = playbackCoordinator::buildTuningParamsString,
        getState = { state.value },
        setPlaybackMode = ::setPlaybackMode,
        loadTrackById = ::loadTrackById
    )
    private val appLifecycleCoordinator = AppLifecycleCoordinator(
        scope = viewModelScope,
        serverGateway = serverGateway,
        controller = controller,
        playbackCoordinator = playbackCoordinator,
        localAnalysisCoordinator = localAnalysisCoordinator,
        cancelServerTrackLoad = remoteTrackLoadCoordinator::cancel,
        updateState = { updater -> _state.update(updater) },
        isDebugBuild = BuildConfig.DEBUG,
        currentVersionName = BuildConfig.VERSION_NAME,
        githubRepoOwner = GITHUB_REPO_OWNER,
        githubRepoName = GITHUB_REPO_NAME
    )
    private val playbackServiceEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                ForegroundPlaybackService.ACTION_SLEEP_TIMER_EXPIRED -> {
                    handleSleepTimerExpired()
                }
                ForegroundPlaybackService.ACTION_PLAYBACK_STATE_CHANGED -> {
                    handleLocalPlaybackStateChanged()
                }
                ForegroundPlaybackService.ACTION_PLAYLIST_PREVIOUS -> {
                    skipToPreviousPlaylistTrack()
                }
                ForegroundPlaybackService.ACTION_PLAYLIST_NEXT -> {
                    skipToNextPlaylistTrack()
                }
                ForegroundPlaybackService.ACTION_CLOSE_FULLSCREEN -> {
                    closeFullscreenVisualization()
                }
                ForegroundPlaybackService.ACTION_RETRY_FAILED_LOAD -> {
                    if (canPlayLoadedTrackFromMemory(state.value.playback)) {
                        togglePlayback()
                    } else {
                        retryFailedLoad()
                    }
                }
            }
        }
    }

    init {
        val playbackServiceEvents = IntentFilter().apply {
            addAction(ForegroundPlaybackService.ACTION_SLEEP_TIMER_EXPIRED)
            addAction(ForegroundPlaybackService.ACTION_PLAYBACK_STATE_CHANGED)
            addAction(ForegroundPlaybackService.ACTION_PLAYLIST_PREVIOUS)
            addAction(ForegroundPlaybackService.ACTION_PLAYLIST_NEXT)
            addAction(ForegroundPlaybackService.ACTION_CLOSE_FULLSCREEN)
            addAction(ForegroundPlaybackService.ACTION_RETRY_FAILED_LOAD)
        }
        ContextCompat.registerReceiver(
            getApplication(),
            playbackServiceEventReceiver,
            playbackServiceEvents,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Crash-context keys are derived from state in one place rather than at
        // each mutation site, so every writer of these fields is covered.
        viewModelScope.launch {
            state.map { current ->
                CrashContextKeys(
                    appMode = when (current.appMode) {
                        AppMode.Local -> "local"
                        AppMode.Server -> "server"
                        null -> "unset"
                    },
                    playMode = analyticsPlayMode(current.playback.playMode),
                    casting = current.playback.isCasting.toString(),
                    viz = visualizationLabels.getOrNull(current.playback.activeVizIndex).orEmpty()
                )
            }.distinctUntilChanged().collect { keys ->
                diagnostics.setCrashKey("app_mode", keys.appMode)
                diagnostics.setCrashKey("play_mode", keys.playMode)
                diagnostics.setCrashKey("casting", keys.casting)
                diagnostics.setCrashKey("viz", keys.viz)
            }
        }
        viewModelScope.launch {
            preferences.appMode.collect { mode ->
                val effectiveMode = if (BuildConfig.SERVER_MODE_AVAILABLE) {
                    mode
                } else {
                    AppMode.Local
                }
                _state.update { current ->
                    val nextActiveTab = coerceTabForMode(effectiveMode, current.activeTab)
                    current.copy(
                        appMode = effectiveMode,
                        activeTab = nextActiveTab,
                        showAppModeGate = shouldShowAppModeGate(effectiveMode),
                        showBaseUrlPrompt = shouldShowBaseUrlPrompt(effectiveMode, current.baseUrl),
                        castEnabled = resolveCastEnabled(effectiveMode, relayConfigured)
                    )
                }
                hydrateSavedPlaylistIfInactive()
                maybeRefreshServerDataForCurrentState()
                maybeShowAutomaticWhatsNew()
                consumePendingShareIfReady()
            }
        }
        viewModelScope.launch {
            preferences.baseUrl.collect { url ->
                _state.update { current ->
                    val mode = current.appMode
                    current.copy(
                        baseUrl = url.orEmpty(),
                        showBaseUrlPrompt = shouldShowBaseUrlPrompt(mode, url.orEmpty()),
                        castEnabled = resolveCastEnabled(mode, relayConfigured)
                    )
                }
                baseUrlLoaded = true
                maybeRefreshServerDataForCurrentState()
                consumePendingDeepLinkIfReady()
                consumePendingShareIfReady()
            }
        }
        viewModelScope.launch {
            preferences.adminKey.collect { key ->
                _state.update { current ->
                    current.copy(adminKey = key.orEmpty())
                }
            }
        }
        viewModelScope.launch {
            preferences.favorites.collect { favorites ->
                val normalized = favoritesController.normalizeFavorites(favorites)
                if (normalized != favorites) {
                    favoritesController.updateFavorites(normalized, sync = false)
                } else {
                    _state.update { it.copy(favorites = normalized) }
                }
            }
        }
        viewModelScope.launch {
            preferences.favoritesSyncCode.collect { code ->
                _state.update { it.copy(favoritesSyncCode = code) }
                favoritesController.maybeHydrateFavoritesFromSync()
            }
        }
        viewModelScope.launch {
            preferences.savedPlaylist.collect { tracks ->
                savedPlaylistTracks = tracks
                hydrateSavedPlaylistIfInactive()
            }
        }
        viewModelScope.launch {
            preferences.whatsNewVersionCode.collect { versionCode ->
                _state.update {
                    it.copy(
                        whatsNewVersionCodeLoaded = true,
                        lastShownWhatsNewVersionCode = versionCode
                    )
                }
                maybeShowAutomaticWhatsNew()
            }
        }
        viewModelScope.launch {
            preferences.appConfig.collect { config ->
                if (config != null) {
                    serverConfigState = ServerConfigState.Loaded
                    val maxFavorites = sanitizeMaxFavorites(config.maxFavorites)
                    val currentFavorites = state.value.favorites
                    val normalizedFavorites = favoritesController.normalizeFavorites(
                        items = currentFavorites,
                        maxFavorites = maxFavorites
                    )
                    _state.update {
                        it.copy(
                            allowFavoritesSync = config.allowFavoritesSync,
                            maxFavorites = maxFavorites,
                            favorites = favoritesController.normalizeFavorites(
                                items = it.favorites,
                                maxFavorites = maxFavorites
                            ),
                            maxTrackLengthMinutes = config.maxTrackLength,
                            allowUserUrl = config.allowUserUrl,
                            allowUserUpload = config.allowUserUpload,
                            maxUploadSizeBytes = config.maxUploadSize,
                            allowedUploadExts = config.allowedUploadExts
                        )
                    }
                    if (normalizedFavorites != currentFavorites) {
                        favoritesController.updateFavorites(normalizedFavorites, sync = false)
                    }
                    favoritesController.maybeHydrateFavoritesFromSync()
                } else {
                    val maxFavorites = sanitizeMaxFavorites(null)
                    val currentFavorites = state.value.favorites
                    val normalizedFavorites = favoritesController.normalizeFavorites(
                        items = currentFavorites,
                        maxFavorites = maxFavorites
                    )
                    _state.update {
                        it.copy(
                            allowFavoritesSync = false,
                            maxFavorites = maxFavorites,
                            favorites = favoritesController.normalizeFavorites(
                                items = it.favorites,
                                maxFavorites = maxFavorites
                            ),
                            maxTrackLengthMinutes = null,
                            allowUserUrl = false,
                            allowUserUpload = false,
                            maxUploadSizeBytes = null,
                            allowedUploadExts = emptyList()
                        )
                    }
                    if (normalizedFavorites != currentFavorites) {
                        favoritesController.updateFavorites(normalizedFavorites, sync = false)
                    }
                }
                consumePendingShareIfReady()
            }
        }
        viewModelScope.launch {
            preferences.themeMode.collect { mode ->
                _state.update { it.copy(themeMode = mode) }
            }
        }
        viewModelScope.launch {
            preferences.activeVizIndex.collect { index ->
                val resolvedIndex = if (index in 0 until visualizationCount) {
                    index
                } else {
                    defaultVisualizationIndex
                }
                _state.update {
                    it.copy(playback = it.playback.copy(activeVizIndex = resolvedIndex))
                }
            }
        }
        viewModelScope.launch {
            preferences.favoritesSortKey.collect { raw ->
                val sortKey = favoriteSortKeyFromString(raw)
                _state.update { it.copy(favoritesSortKey = sortKey) }
            }
        }
        viewModelScope.launch {
            preferences.favoritesSortDirection.collect { raw ->
                val sortDirection = favoriteSortDirectionFromString(raw)
                _state.update { it.copy(favoritesSortDirection = sortDirection) }
            }
        }
        viewModelScope.launch {
            preferences.localAnalysisSortKey.collect { raw ->
                val sortKey = favoriteSortKeyFromString(raw)
                _state.update { it.copy(localAnalysisSortKey = sortKey) }
            }
        }
        viewModelScope.launch {
            preferences.localAnalysisSortDirection.collect { raw ->
                val sortDirection = favoriteSortDirectionFromString(raw)
                _state.update { it.copy(localAnalysisSortDirection = sortDirection) }
            }
        }
        viewModelScope.launch {
            preferences.canonizerFinishOutSong.collect { enabled ->
                controller.autocanonizer.setFinishOutSong(enabled)
                _state.update {
                    it.copy(
                        playback = it.playback.copy(canonizerFinishOutSong = enabled)
                    )
                }
            }
        }
        viewModelScope.launch {
            preferences.highlightAnchorBranch.collect { enabled ->
                _state.update {
                    it.copy(
                        tuning = it.tuning.copy(highlightAnchorBranch = enabled)
                    )
                }
            }
        }
        viewModelScope.launch {
            preferences.loadingAudioFeedback.collect { enabled ->
                _state.update { it.copy(loadingAudioFeedbackEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            state.collect { current ->
                loadingAudioFeedbackController.update(
                    enabled = current.loadingAudioFeedbackEnabled,
                    loading = shouldPlayLoadingAudioFeedback(current),
                    failureMessage = current.playback.analysisErrorMessage
                )
            }
        }
        viewModelScope.launch {
            ForegroundPlaybackService.sleepTimerState.collect { status ->
                val selectedOption = sleepTimerOptionForDurationMs(status.configuredDurationMs)
                val remainingMs = status.remainingMs.coerceAtLeast(0L)
                _state.update {
                    it.copy(
                        sleepTimer = SleepTimerUiState(
                            selectedOption = selectedOption,
                            remainingMs = remainingMs,
                            isActive = status.isActive
                        )
                    )
                }
            }
        }
        engine.onUpdate { engineState ->
            val currentPlayback = state.value.playback
            if (currentPlayback.playMode == PlaybackMode.Autocanonizer) {
                return@onUpdate
            }
            val currentBeatIndex = engineState.currentBeatIndex
            if (
                currentPlayback.jukeboxAudioMode == JukeboxAudioMode.Cowbell &&
                !currentPlayback.isCasting &&
                currentBeatIndex >= 0 &&
                engineState.beatsPlayed != lastCowbellBeatsPlayed
            ) {
                lastCowbellBeatsPlayed = engineState.beatsPlayed
                val beats = currentPlayback.vizData?.beats
                val beat = beats?.getOrNull(currentBeatIndex)
                if (beat != null) {
                    controller.handleCowbellBeatEnter(
                        beatIndex = currentBeatIndex,
                        beat = beat,
                        nextBeat = beats.getOrNull(currentBeatIndex + 1),
                        playbackRate = controller.player.getPlaybackRate()
                    )
                }
            }
            val lastJumpFrom = engineState.lastJumpFromIndex
            val jumpLine = jumpLineForEngineState(engineState, SystemClock.elapsedRealtime())
            _state.update {
                it.copy(
                    playback = it.playback.copy(
                        beatsPlayed = engineState.beatsPlayed,
                        currentBeatIndex = currentBeatIndex,
                        lastJumpFromIndex = lastJumpFrom,
                        jumpLine = jumpLine
                    )
                )
            }
            playbackCoordinator.maybeUpdateNotification()
        }
        controller.autocanonizer.setOnBeat { index, _, forcedOtherIndex, cursorTimes ->
            if (state.value.playback.playMode != PlaybackMode.Autocanonizer) {
                return@setOnBeat
            }
            val tileOverrides = controller.autocanonizer.getTileColorOverrides()
            _state.update {
                it.copy(
                    playback = it.playback.copy(
                        beatsPlayed = index + 1,
                        currentBeatIndex = index,
                        canonizerOtherIndex = forcedOtherIndex,
                        canonizerTileColorOverrides = tileOverrides,
                        autocanonizer = it.playback.autocanonizer.withCursorTimes(
                            mainSeconds = cursorTimes.mainSeconds,
                            otherSeconds = cursorTimes.otherSeconds
                        ),
                        lastJumpFromIndex = null,
                        jumpLine = null
                    )
                )
            }
            playbackCoordinator.maybeUpdateNotification()
        }
        controller.autocanonizer.setOnEnded {
            if (state.value.playback.playMode != PlaybackMode.Autocanonizer) {
                return@setOnEnded
            }
            controller.stopExternalPlayback()
            playbackCoordinator.stopListenTimer()
            playbackCoordinator.updateListenTimeDisplay()
            _state.update {
                it.copy(
                    playback = playbackStateAfterAutocanonizerStop(it.playback)
                )
            }
            val current = state.value
            if (shouldAdvancePlaylistOnAutocanonizerEnd(current)) {
                selectPlaylistTrack(
                    index = current.playlist.currentIndex + 1,
                    playAfterLoaded = true
                )
                return@setOnEnded
            }
            syncPlaybackServiceSession()
        }

        playbackCoordinator.restorePlaybackState()
        localAnalysisCoordinator.refreshLocalCachedTracks()
        checkForAppUpdateOnce()
    }

    override fun onCleared() {
        remoteTrackLoadCoordinator.cancel()
        cancelCastSelection()
        localAnalysisCoordinator.cancelLocalAnalysisInternal(showCancelledMessage = false)
        runCatching {
            getApplication<Application>().unregisterReceiver(playbackServiceEventReceiver)
        }
        super.onCleared()
        loadingAudioFeedbackController.release()
        playbackCoordinator.onCleared()
        controller.release()
    }

    private fun handleSleepTimerExpired() {
        playbackCoordinator.stopListenTimer()
        playbackCoordinator.updateListenTimeDisplay()
        _state.update {
            it.copy(
                playback = if (it.playback.playMode == PlaybackMode.Autocanonizer) {
                    playbackStateAfterAutocanonizerStop(it.playback)
                } else {
                    it.playback.copy(
                        isRunning = false,
                        isPaused = false,
                        canonizerOtherIndex = null
                    )
                }
            )
        }
        if (state.value.playback.isCasting) {
            syncCastNotification()
        } else {
            syncPlaybackServiceSession()
        }
    }

    private fun handleLocalPlaybackStateChanged() {
        val playback = state.value.playback
        if (playback.isCasting) {
            return
        }
        val isRunning = controller.isPlaying()
        val isPaused = controller.isPaused()
        if (isRunning) {
            playbackCoordinator.clearAnalysisErrorForPlaybackStart()
            playbackCoordinator.startListenTimer()
        } else {
            playbackCoordinator.stopListenTimer()
        }
        playbackCoordinator.updateListenTimeDisplay()
        _state.update {
            it.copy(
                playback = when {
                    it.playback.playMode == PlaybackMode.Autocanonizer &&
                        !isRunning &&
                        !isPaused -> playbackStateAfterAutocanonizerStop(it.playback)
                    else -> it.playback.copy(
                        isRunning = isRunning,
                        isPaused = isPaused,
                        canonizerOtherIndex = if (isRunning || isPaused) {
                            it.playback.canonizerOtherIndex
                        } else {
                            null
                        }
                    )
                }
            )
        }
        syncPlaybackServiceSession()
    }

    fun onHostStarted() {
        if (foregroundRecoveryInFlight) {
            return
        }
        foregroundRecoveryInFlight = true
        viewModelScope.launch {
            try {
                recoverLoadingStateOnForeground()
            } finally {
                foregroundRecoveryInFlight = false
            }
        }
    }

    fun setBaseUrl(url: String) {
        val trimmedUrl = url.trim()
        val current = state.value
        val mode = current.appMode
        val didServerChange = mode == AppMode.Server &&
            hasBaseUrlServerChanged(current.baseUrl, trimmedUrl)
        if (didServerChange) {
            resetRuntimeForServerSwitch(trimmedUrl)
        } else {
            _state.update {
                it.copy(
                    baseUrl = trimmedUrl,
                    showBaseUrlPrompt = shouldShowBaseUrlPrompt(it.appMode, trimmedUrl),
                    castEnabled = resolveCastEnabled(mode, relayConfigured)
                )
            }
        }
        consumePendingDeepLinkIfReady()
        consumePendingShareIfReady()
        viewModelScope.launch {
            preferences.setBaseUrl(trimmedUrl)
            if (didServerChange) {
                preferences.setFavorites(emptyList())
                preferences.setFavoritesSyncCode(null)
                preferences.clearAppConfig()
            }
            if (state.value.appMode == AppMode.Server) {
                delay(100)
                refreshTopSongs()
            }
        }
    }

    fun setAdminKey(key: String) {
        val trimmedKey = key.trim()
        _state.update { it.copy(adminKey = trimmedKey) }
        viewModelScope.launch {
            preferences.setAdminKey(trimmedKey)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferences.setThemeMode(mode)
        }
    }

    fun setLoadingAudioFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setLoadingAudioFeedback(enabled)
        }
    }

    fun startLocalAnalysis(uri: Uri, displayName: String?) {
        if (blockPlaybackChangeWhileLoading()) return
        analytics.logUpload("file")
        clearInactiveSavedPlaylistBeforeOutsideSelection()
        localAnalysisCoordinator.startLocalAnalysis(uri, displayName)
    }

    fun openCachedLocalTrack(localId: String) {
        if (blockPlaybackChangeWhileLoading()) return
        clearInactiveSavedPlaylistBeforeOutsideSelection()
        localAnalysisCoordinator.openCachedLocalTrack(localId)
    }

    fun deleteCachedLocalTrack(localId: String) {
        val trackId = localId.trim()
            .takeIf { it.isNotBlank() }
        if (trackId != null) {
            val favorites = state.value.favorites
            val updated = removeFavoritesForTrackIds(favorites, setOf(trackId))
            if (updated.size != favorites.size) {
                favoritesController.updateFavorites(updated)
            }
        }
        localAnalysisCoordinator.deleteCachedLocalTrack(localId)
    }

    fun dismissLocalCachedTrackErrorDialog() {
        localAnalysisCoordinator.dismissLocalCachedTrackErrorDialog()
    }

    fun cancelLocalAnalysis() {
        localAnalysisCoordinator.cancelLocalAnalysis()
    }

    fun retryCastLoad() {
        castPlaybackCoordinator.retryLastCastRequest()
    }

    fun setAppMode(mode: AppMode) {
        if (state.value.appMode == mode) return
        resetRuntimeForModeChange(mode)
        viewModelScope.launch {
            preferences.setAppMode(mode)
        }
        maybeRefreshServerDataForCurrentState()
    }

    fun completeAppModeOnboarding(mode: AppMode, baseUrl: String) {
        if (mode == AppMode.Server) {
            setBaseUrl(baseUrl)
        }
        setAppMode(mode)
    }

    fun setActiveTab(tabId: TabId) {
        val resolvedTab = coerceTabForMode(state.value.appMode, tabId)
        if (resolvedTab == TabId.Top && state.value.activeTab == TabId.Top) {
            setTopSongsTab(TopSongsTab.TopSongs)
            return
        }
        if (resolvedTab == TabId.Search && state.value.activeTab == TabId.Search) {
            setSearchPanelTab(SearchPanelTab.Search)
            return
        }
        applyActiveTab(resolvedTab, recordHistory = true)
    }

    fun navigateBack(): Boolean {
        if (tabHistory.isEmpty()) return false
        val previous = tabHistory.removeLast()
        applyActiveTab(previous, recordHistory = false)
        return true
    }

    fun setTopSongsTab(tab: TopSongsTab) {
        _state.update { it.copy(topSongsTab = tab) }
        searchCoordinator.onTopSongsTabSelected(tab)
    }

    fun setSearchPanelTab(tab: SearchPanelTab) {
        _state.update { it.copy(searchPanelTab = tab) }
    }

    fun refreshFavoritesFromSync() {
        favoritesController.refreshFavoritesFromSync()
    }

    fun createFavoritesSyncCode() {
        favoritesController.createFavoritesSyncCode()
    }

    suspend fun fetchFavoritesPreview(code: String): List<FavoriteTrack>? {
        return favoritesController.fetchFavoritesPreview(code)
    }

    fun applyFavoritesSync(code: String, favorites: List<FavoriteTrack>) {
        favoritesController.applyFavoritesSync(code, favorites)
    }

    private fun applyActiveTab(tabId: TabId, recordHistory: Boolean) {
        val resolvedTab = coerceTabForMode(state.value.appMode, tabId)
        if (shouldCancelLocalAnalysisOnTabChange(
                mode = state.value.appMode,
                isLocalAnalysisRunning = localAnalysisCoordinator.isAnalysisRunning(),
                targetTab = resolvedTab
            )
        ) {
            localAnalysisCoordinator.cancelLocalAnalysisInternal(showCancelledMessage = true)
        }
        val current = state.value.activeTab
        if (resolvedTab == current) return
        if (recordHistory && tabHistory.lastOrNull() != current) {
            tabHistory.addLast(current)
        }
        _state.update {
            val nextTopTab = if (resolvedTab == TabId.Top) TopSongsTab.TopSongs else it.topSongsTab
            val nextSearchTab = if (resolvedTab == TabId.Search) {
                SearchPanelTab.Search
            } else {
                it.searchPanelTab
            }
            it.copy(activeTab = resolvedTab, topSongsTab = nextTopTab, searchPanelTab = nextSearchTab)
        }
        if (resolvedTab == TabId.Top) {
            searchCoordinator.onTopTabActivated()
        }
        if (resolvedTab == TabId.Input) {
            localAnalysisCoordinator.refreshLocalCachedTracks()
        }
        if (resolvedTab != TabId.Play) {
            _state.update { it.copy(playback = it.playback.copy()) }
        }
    }

    private fun maybeRefreshServerDataForCurrentState() {
        val currentState = state.value
        if (currentState.appMode != AppMode.Server) return
        val baseUrl = currentState.baseUrl
        if (baseUrl.isBlank()) return
        if (!appConfigLoaded) {
            appConfigLoaded = true
            viewModelScope.launch {
                runCatching { serverGateway.getAppConfig(baseUrl).also { preferences.setAppConfig(it) } }
                // The config is settled either way: a success re-enters through the appConfig
                // collector, a failure leaves whatever was already cached as the answer. This is
                // what keeps a shared track from waiting forever on an unreachable server.
                if (serverConfigState == ServerConfigState.Pending) {
                    serverConfigState = ServerConfigState.Missing
                }
                consumePendingShareIfReady()
            }
        }
        searchCoordinator.maybeRefreshForState(currentState)
        favoritesController.maybeHydrateFavoritesFromSync()
    }

    private fun resetRuntimeForModeChange(targetMode: AppMode) {
        remoteTrackLoadCoordinator.cancel()
        cancelCastSelection()
        localAnalysisCoordinator.cancelLocalAnalysisInternal(showCancelledMessage = false)
        searchCoordinator.resetRuntimeState()
        appConfigLoaded = false
        serverConfigState = ServerConfigState.Pending
        tabHistory.clear()

        if (targetMode == AppMode.Local || state.value.playback.isCasting) {
            runCatching { castPlaybackCoordinator.endSession() }
        }
        castPlaybackCoordinator.resetStatusListener()
        playbackCoordinator.resetForNewTrack()
        engine.clearAnalysis()
        controller.player.clear()
        controller.setTrackMeta(null, null)

        _state.update { current ->
            stateAfterModeChangeReset(
                current = current,
                targetMode = targetMode,
                castEnabled = resolveCastEnabled(targetMode, relayConfigured)
            )
        }
    }

    private fun resetRuntimeForServerSwitch(nextBaseUrl: String) {
        remoteTrackLoadCoordinator.cancel()
        cancelCastSelection()
        searchCoordinator.resetRuntimeState()
        favoritesController.resetRuntimeState()
        appConfigLoaded = false
        serverConfigState = ServerConfigState.Pending
        tabHistory.clear()

        stopCasting()
        clearPlaylistState()
        castPlaybackCoordinator.resetStatusListener()
        playbackCoordinator.resetForNewTrack()
        playbackCoordinator.clearCache()
        engine.clearAnalysis()
        controller.player.clear()
        controller.setTrackMeta(null, null)

        _state.update { current ->
            val mode = current.appMode
            current.copy(
                baseUrl = nextBaseUrl,
                showBaseUrlPrompt = shouldShowBaseUrlPrompt(mode, nextBaseUrl),
                castEnabled = resolveCastEnabled(mode, relayConfigured),
                activeTab = TabId.Top,
                topSongsTab = TopSongsTab.TopSongs,
                searchPanelTab = SearchPanelTab.Search,
                favorites = emptyList(),
                favoritesSyncCode = null,
                allowFavoritesSync = false,
                maxTrackLengthMinutes = null,
                trackLengthLimitErrorMessage = null,
                favoritesSyncLoading = false,
                listenFavoriteToggleInFlight = false,
                search = SearchState(),
                playback = PlaybackState(),
                playlist = JukeboxPlaylistState()
            )
        }
    }

    private fun updateSearchState(transform: (SearchState) -> SearchState) {
        _state.update { it.copy(search = transform(it.search)) }
    }

    private fun setSearchQuery(value: String) {
        updateSearchState { it.copy(query = value) }
    }

    private fun clearSearchSelectionState() {
        updateSearchState(::resetSearchStateAfterTrackSelection)
    }

    private fun updatePlaybackState(transform: (PlaybackState) -> PlaybackState) {
        _state.update { it.copy(playback = transform(it.playback)) }
    }

    private fun persistSavedPlaylistTracks(tracks: List<PlaylistTrack>) {
        val savedTracks = tracks.map { it.toSavedPlaylistTrack() }
        savedPlaylistTracks = savedTracks
        viewModelScope.launch {
            preferences.setSavedPlaylist(savedTracks)
        }
    }

    private fun savedPlaylistStateForCurrentMode(current: UiState): JukeboxPlaylistState {
        val savedTracks = savedPlaylistTracks.mapNotNull { it.toPlaylistTrack() }
        val playableTracks = playablePlaylistTracks(
            tracks = savedTracks,
            appMode = current.appMode,
            localCachedTracks = current.localCachedTracks
        )
        if (playableTracks.size < 2) {
            return JukeboxPlaylistState()
        }
        return JukeboxPlaylistState(tracks = playableTracks, currentIndex = -1)
    }

    private fun hydrateSavedPlaylistIfInactive() {
        val current = state.value
        if (current.playlist.isActive()) {
            return
        }
        val savedPlaylist = savedPlaylistStateForCurrentMode(current)
        if (current.playlist == savedPlaylist) {
            return
        }
        _state.update {
            it.copy(playlist = savedPlaylist)
        }
    }

    private fun updatePlaylistState(
        transform: (JukeboxPlaylistState) -> JukeboxPlaylistState
    ) {
        val before = state.value.playlist
        val after = transform(before)
        if (after == before) {
            return
        }
        _state.update {
            it.copy(playlist = after)
        }
        if (after.tracks != before.tracks) {
            persistSavedPlaylistTracks(after.tracks)
        }
    }

    private fun clearPlaylistState() {
        val current = state.value.playlist
        if (current == JukeboxPlaylistState() && savedPlaylistTracks.isEmpty()) {
            return
        }
        savedPlaylistTracks = emptyList()
        _state.update {
            it.copy(playlist = JukeboxPlaylistState())
        }
        viewModelScope.launch {
            preferences.setSavedPlaylist(emptyList())
        }
    }

    private fun clearInactiveSavedPlaylistBeforeOutsideSelection() {
        if (state.value.playlist.isInactiveSavedPlaylist()) {
            clearPlaylistState()
        }
    }

    private fun migrateLegacyServerTrackId(
        legacyTrackId: String,
        jobId: String,
        title: String?,
        artist: String?,
        tuningParams: String?
    ) {
        val legacyCanonical = canonicalTrackId(legacyTrackId) ?: return
        val canonicalJobTrackId = canonicalJobId(jobId) ?: return
        if (legacyCanonical == canonicalJobTrackId) return

        val favorites = state.value.favorites
        val updatedFavorites = favorites.map { favorite ->
            if (canonicalTrackId(favorite.uniqueSongId) == legacyCanonical) {
                favorite.copy(uniqueSongId = canonicalJobTrackId)
            } else {
                favorite
            }
        }
        if (updatedFavorites != favorites) {
            favoritesController.updateFavorites(updatedFavorites)
        }

        updatePlaylistState { playlist ->
            playlist.copy(
                tracks = playlist.tracks.map { track ->
                    if (
                        track.type == PlaylistTrackType.Server &&
                        canonicalTrackId(track.id) == legacyCanonical
                    ) {
                        track.copy(
                            id = canonicalJobTrackId,
                            title = title.takeIfNotBlank() ?: track.title,
                            artist = artist.takeIfNotBlank() ?: track.artist,
                            tuningParams = tuningParams?.takeIf { it.isNotBlank() }
                                ?: track.tuningParams
                        )
                    } else {
                        track
                    }
                }
            )
        }
    }

    fun setPlayAfterLoaded(checked: Boolean) {
        updatePlaybackState { it.copy(playAfterLoaded = checked) }
    }

    private fun maybeStartPlayAfterLoaded() {
        if (!shouldStartPlayAfterLoaded(state.value.playback)) {
            return
        }
        updatePlaybackState { it.copy(playAfterLoaded = false) }
        togglePlayback()
    }

    private fun handleStableTrackLoaded() {
        refreshActivePlaylistCurrentTrack()
        maybeStartPlayAfterLoaded()
    }

    private fun refreshActivePlaylistCurrentTrack() {
        maybeSelectPlaylistTrack(currentPlaylistTrackOrNull())
    }

    private fun playlistTrackForServerTrack(
        trackId: String,
        title: String?,
        artist: String?,
        tuningParams: String?,
        playMode: FavoritePlayMode? = null
    ): PlaylistTrack? {
        return serverPlaylistTrack(trackId, title, artist, tuningParams, playMode)
    }

    private fun playlistTrackForLocalCached(localId: String): PlaylistTrack? {
        val normalized = localId.trim()
        if (normalized.isBlank()) return null
        val cached = state.value.localCachedTracks.firstOrNull { it.localId == normalized } ?: return null
        return PlaylistTrack(
            id = normalized,
            type = PlaylistTrackType.LocalCached,
            title = cached.title,
            artist = cached.artist
        )
    }

    private fun currentPlaylistTrackOrNull(): PlaylistTrack? {
        val currentState = state.value
        val playback = currentState.playback
        val hasLoadedTrack = (playback.audioLoaded && playback.analysisLoaded) || playback.hasCastTrack()
        if (!hasLoadedTrack) return null
        val trackId = playback.shareTrackIdOrNull() ?: return null
        val type = if (currentState.appMode == AppMode.Local) {
            PlaylistTrackType.LocalCached
        } else {
            PlaylistTrackType.Server
        }
        return PlaylistTrack(
            id = canonicalTrackId(trackId) ?: trackId.trim(),
            type = type,
            title = playback.trackTitle,
            artist = playback.trackArtist,
            tuningParams = if (type == PlaylistTrackType.Server) {
                tuningParamsForCurrentTrack(
                    currentState,
                    playbackCoordinator::buildTuningParamsString
                )
            } else {
                null
            },
            playMode = if (type == PlaylistTrackType.Server) {
                playback.playMode.toFavoritePlayModeOrNull()
            } else {
                null
            }
        )
    }

    private fun addTrackToPlaylistFromLongPress(track: PlaylistTrack) {
        val playlist = state.value.playlist
        if (playlist.isInactiveSavedPlaylist()) {
            viewModelScope.launch { showToast("Load a track before starting a playlist.") }
            return
        }
        if (!playlist.isInitialized()) {
            val current = currentPlaylistTrackOrNull()
            if (current == null) {
                viewModelScope.launch { showToast("Load a track before starting a playlist.") }
                return
            }
            if (current == track || initializePlaylist(current, track).tracks.size < 2) {
                viewModelScope.launch { showToast("Already in playlist.") }
                return
            }
            updatePlaylistState { initializePlaylist(current, track) }
            syncPlaybackServiceSession()
            viewModelScope.launch { showToast("Added to playlist.") }
            return
        }
        if (playlist.containsTrack(track)) {
            viewModelScope.launch { showToast("Already in playlist.") }
            return
        }
        if (playlist.tracks.size >= MAX_PLAYLIST_TRACKS) {
            viewModelScope.launch { showToast("Playlist is full.") }
            return
        }
        updatePlaylistState { it.appendTrack(track) }
        syncPlaybackServiceSession()
        viewModelScope.launch { showToast("Added to playlist.") }
    }

    private fun maybeSelectPlaylistTrack(track: PlaylistTrack?) {
        if (track == null || !state.value.playlist.isActive()) {
            return
        }
        updatePlaylistState { it.replaceCurrentTrackWith(track) }
    }

    private fun loadPlaylistTrack(
        track: PlaylistTrack,
        playAfterLoaded: Boolean = false
    ) {
        when (track.type) {
            PlaylistTrackType.Server -> {
                // Honor the track's saved play mode as the playlist advances; a null
                // playMode (untagged/jukebox favorites, top songs) falls back to jukebox.
                setPlaybackMode(track.playMode.toPlaybackMode())
                loadTrackByIdInternal(
                    track.id,
                    track.title,
                    track.artist,
                    track.tuningParams,
                    playAfterLoaded
                )
            }
            PlaylistTrackType.LocalCached -> localAnalysisCoordinator.openCachedLocalTrack(
                localId = track.id,
                playAfterLoaded = playAfterLoaded
            )
        }
    }

    fun toggleFavoriteForCurrent(): FavoriteToggleResult {
        val currentState = state.value
        val playback = currentState.playback
        val currentTrackId = playback.shareTrackIdOrNull() ?: return FavoriteToggleResult.NoTrack
        val currentCanonicalId =
            canonicalJobId(currentTrackId) ?: return FavoriteToggleResult.NoTrack
        if (shouldBlockListenFavoriteToggle(currentState)) {
            return FavoriteToggleResult.BlockedInFlight
        }
        val favorites = currentState.favorites
        val syncFromListenToggle = hasRealFavoritesSyncPath(currentState)
        val currentTrackIds = playback.reusableTrackIdsForMatching()
        val existing = favorites.any {
            canonicalTrackId(it.uniqueSongId) in currentTrackIds
        }
        return if (existing) {
            favoritesController.updateFavorites(
                favorites.filterNot {
                    canonicalTrackId(it.uniqueSongId) in currentTrackIds
                },
                fromListenToggle = syncFromListenToggle
            )
            FavoriteToggleResult.Removed
        } else {
            if (favorites.size >= sanitizeMaxFavorites(currentState.maxFavorites)) {
                FavoriteToggleResult.LimitReached
            } else {
                val title = playback.trackTitle?.takeIf { it.isNotBlank() } ?: "Untitled"
                val artist = playback.trackArtist?.takeIf { it.isNotBlank() } ?: "Unknown"
                val newFavorite = FavoriteTrack(
                    uniqueSongId = currentCanonicalId,
                    title = title,
                    artist = artist,
                    duration = playback.trackDurationSeconds,
                    sourceType = if (playback.lastYouTubeId.isNullOrBlank()) null else FavoriteSourceType.Youtube,
                    tuningParams = tuningParamsForCurrentTrack(
                        currentState,
                        playbackCoordinator::buildTuningParamsString
                    ),
                    playMode = playback.playMode.toFavoritePlayModeOrNull()
                )
                favoritesController.updateFavorites(
                    favorites + newFavorite,
                    fromListenToggle = syncFromListenToggle
                )
                analytics.logFavorite(currentCanonicalId, title)
                FavoriteToggleResult.Added
            }
        }
    }

    fun removeFavorite(uniqueSongId: String) {
        val favorites = state.value.favorites
        val canonicalTarget = canonicalTrackId(uniqueSongId) ?: return
        val filtered = favorites.filterNot { canonicalTrackId(it.uniqueSongId) == canonicalTarget }
        if (filtered.size == favorites.size) return
        favoritesController.updateFavorites(filtered)
    }

    fun refreshTopSongs() {
        searchCoordinator.refreshTopSongs()
    }

    fun refreshRecentSongs() {
        searchCoordinator.refreshRecentSongs()
    }

    fun refreshTrendingSongs() {
        searchCoordinator.refreshTrendingSongs()
    }

    fun runSpotifySearch(query: String) {
        query.trim().takeIf { it.isNotEmpty() }?.let(analytics::logSearch)
        searchCoordinator.runSpotifySearch(query)
    }

    fun selectServerPlaylistTrack(
        trackId: String,
        title: String? = null,
        artist: String? = null,
        tuningParams: String? = null,
        playMode: FavoritePlayMode? = null
    ) {
        if (blockPlaybackChangeWhileLoading()) return
        analytics.logSelectTrack(analyticsSelectSource(state.value.topSongsTab), trackId, title)
        selectServerTrackInternal(trackId, title, artist, tuningParams, playMode)
    }

    private fun selectServerTrackInternal(
        trackId: String,
        title: String?,
        artist: String?,
        tuningParams: String?,
        playMode: FavoritePlayMode?
    ) {
        clearInactiveSavedPlaylistBeforeOutsideSelection()
        val track = playlistTrackForServerTrack(trackId, title, artist, tuningParams, playMode)
        if (track != null && state.value.playlist.isActive()) {
            updatePlaylistState { it.replaceCurrentTrackWith(track) }
        }
        loadTrackById(
            track?.id ?: trackId,
            track?.title ?: title,
            track?.artist ?: artist,
            track?.tuningParams ?: tuningParams
        )
    }

    fun selectFavoriteTrack(
        trackId: String,
        title: String? = null,
        artist: String? = null,
        tuningParams: String? = null,
        playMode: FavoritePlayMode? = null
    ) {
        if (blockPlaybackChangeWhileLoading()) return
        analytics.logSelectTrack("favorites", trackId, title)
        // Restore the play mode the track was favorited in before loading so the
        // track opens in jukebox or autocanonizer accordingly. Legacy favorites
        // (null playMode) fall back to jukebox.
        setPlaybackMode(playMode.toPlaybackMode())
        selectServerTrackInternal(trackId, title, artist, tuningParams, playMode)
    }

    fun addServerTrackToPlaylist(
        trackId: String,
        title: String? = null,
        artist: String? = null,
        tuningParams: String? = null
    ) {
        val track = playlistTrackForServerTrack(trackId, title, artist, tuningParams) ?: return
        addTrackToPlaylistFromLongPress(track)
    }

    fun addFavoriteTrackToPlaylist(
        trackId: String,
        title: String? = null,
        artist: String? = null,
        tuningParams: String? = null,
        playMode: FavoritePlayMode? = null
    ) {
        // Carry the favorite's play mode into the playlist entry so playback honors
        // it when this track is reached during advancement (see loadPlaylistTrack).
        val track = playlistTrackForServerTrack(trackId, title, artist, tuningParams, playMode) ?: return
        addTrackToPlaylistFromLongPress(track)
    }

    fun selectLocalCachedPlaylistTrack(localId: String) {
        if (blockPlaybackChangeWhileLoading()) return
        clearInactiveSavedPlaylistBeforeOutsideSelection()
        val track = playlistTrackForLocalCached(localId) ?: return
        if (state.value.playlist.isActive()) {
            updatePlaylistState { it.replaceCurrentTrackWith(track) }
        }
        openCachedLocalTrack(track.id)
    }

    fun addLocalCachedTrackToPlaylist(localId: String) {
        val track = playlistTrackForLocalCached(localId) ?: return
        addTrackToPlaylistFromLongPress(track)
    }

    fun selectSpotifyTrack(item: RemoteMusicSearchItem) {
        if (blockPlaybackChangeWhileLoading()) return
        clearInactiveSavedPlaylistBeforeOutsideSelection()
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        val name = item.name ?: "Untitled"
        val artist = item.artist ?: ""
        val duration = item.duration ?: return
        if (showTrackLengthLimitIfExceeded(duration)) {
            return
        }
        // Spotify picks have no YouTube id yet, so track_id is omitted (matches web).
        analytics.logSelectTrack("search", null, analyticsSearchResultTitle(name, artist))
        remoteTrackLoadCoordinator.launch {
            if (artist.isNotBlank()) {
                try {
                    val response = serverGateway.getJobByTrack(baseUrl, name, artist)
                    if (shouldReuseLookupJob(response)) {
                        val jobId = canonicalJobId(response!!.id) ?: return@launch
                        val trackId = jobId
                        if (state.value.playback.isCasting) {
                            clearSearchSelectionState()
                            maybeSelectPlaylistTrack(
                                playlistTrackForServerTrack(trackId, name, artist, null)
                            )
                            castPlaybackCoordinator.castTrackId(
                                jobId = jobId,
                                title = name,
                                artist = artist,
                                youtubeId = parseTrackId(trackId)?.youtubeId
                            )
                            applyActiveTab(TabId.Play, recordHistory = true)
                            return@launch
                        }
                        maybeSelectPlaylistTrack(
                            playlistTrackForServerTrack(trackId, name, artist, null)
                        )
                        loadExistingJob(
                            jobId,
                            trackId,
                            response,
                            name,
                            artist
                        )
                        return@launch
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: HttpStatusException) {
                    if (error.statusCode == 422) {
                        showServerTrackLengthLimitError()
                        return@launch
                    }
                    AppLog.warn(TAG, "Job lookup by track failed", error)
                    // Fall back to YouTube matches.
                } catch (error: IOException) {
                    AppLog.warn(TAG, "Job lookup by track failed", error)
                    // Fall back to YouTube matches.
                } catch (error: IllegalArgumentException) {
                    AppLog.warn(TAG, "Job lookup by track failed", error)
                    // Fall back to YouTube matches.
                } catch (error: IllegalStateException) {
                    AppLog.warn(TAG, "Job lookup by track failed", error)
                    // Fall back to YouTube matches.
                }
            }
            fetchYoutubeMatches(name, artist, duration)
        }
    }

    fun selectYoutubeTrack(item: RemoteVideoSearchItem) {
        if (blockPlaybackChangeWhileLoading()) return
        clearInactiveSavedPlaylistBeforeOutsideSelection()
        val selection = resolveRemoteVideoSelection(item, state.value.search) ?: return
        val duration = item.duration
        if (showTrackLengthLimitIfExceeded(duration)) {
            return
        }
        analytics.logSelectTrack(
            "search",
            selection.youtubeId,
            analyticsSearchResultTitle(selection.title, selection.artist)
        )
        startYoutubeAnalysis(selection.youtubeId, selection.title, selection.artist)
    }

    fun fetchYoutubeMatches(name: String, artist: String, duration: Double) {
        searchCoordinator.fetchYoutubeMatches(name, artist, duration)
    }

    fun startYoutubeAnalysis(youtubeId: String, title: String? = null, artist: String? = null) {
        if (blockPlaybackChangeWhileLoading()) return
        clearInactiveSavedPlaylistBeforeOutsideSelection()
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        val resolvedTitle = title.takeIfNotBlank()
            ?: state.value.search.pendingTrackName.takeIfNotBlank()
        val resolvedArtist = artist.takeIfNotBlank()
            ?: state.value.search.pendingTrackArtist.takeIfNotBlank()
        val trackId = youtubeId.trim()
        if (trackId.isBlank()) return
        if (state.value.playback.isCasting) {
            clearSearchSelectionState()
            applyActiveTab(TabId.Play, recordHistory = true)
            launchCastSelection {
                val knownJobId = resolveKnownJobIdForSource(
                    state = state.value,
                    sourceProvider = SOURCE_PROVIDER_YOUTUBE,
                    sourceId = youtubeId
                )
                if (!knownJobId.isNullOrBlank()) {
                    castPlaybackCoordinator.castTrackId(
                        jobId = knownJobId,
                        title = resolvedTitle,
                        artist = resolvedArtist,
                        youtubeId = trackId
                    )
                    return@launchCastSelection
                }
                try {
                    val existing = serverGateway.getJobBySource(baseUrl, SOURCE_PROVIDER_YOUTUBE, youtubeId)
                    val resolvedJobId = canonicalJobId(existing?.id)
                    if (!resolvedJobId.isNullOrBlank()) {
                        migrateLegacyServerTrackId(
                            trackId,
                            resolvedJobId,
                            resolvedTitle,
                            resolvedArtist,
                            null
                        )
                        castPlaybackCoordinator.castTrackId(
                            jobId = resolvedJobId,
                            title = resolvedTitle,
                            artist = resolvedArtist,
                            youtubeId = trackId
                        )
                        return@launchCastSelection
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: HttpStatusException) {
                    if (error.statusCode == 422) {
                        showServerTrackLengthLimitError()
                        return@launchCastSelection
                    }
                } catch (_: IOException) {
                } catch (_: IllegalArgumentException) {
                } catch (_: IllegalStateException) {
                }
                val resolvedJobId = queueYoutubeAnalysisForCast(
                    youtubeId = youtubeId,
                    title = resolvedTitle,
                    artist = resolvedArtist
                )
                if (resolvedJobId == null) {
                    return@launchCastSelection
                }
                migrateLegacyServerTrackId(trackId, resolvedJobId, resolvedTitle, resolvedArtist, null)
                castPlaybackCoordinator.castTrackId(
                    jobId = resolvedJobId,
                    title = resolvedTitle,
                    artist = resolvedArtist,
                    youtubeId = trackId
                )
            }
            return
        }
        prepareServerTrackLoad(
            tuningParams = null
        ) { current ->
            current.copy(
                search = resetSearchStateAfterTrackSelection(current.search),
                playback = current.playback.copy(
                    audioLoading = false,
                    lastYouTubeId = trackId,
                    trackTitle = resolvedTitle,
                    trackArtist = resolvedArtist
                )
            )
        }
        launchServerTrackLoadWithCache(
            cachedJobId = null,
            failureLogMessage = "Failed to start YouTube analysis"
        ) {
            val existing = serverGateway.getJobBySource(baseUrl, SOURCE_PROVIDER_YOUTUBE, trackId)
            if (existing != null) {
                val jobId = canonicalJobId(existing.id)
                    ?: return@launchServerTrackLoadWithCache false
                migrateLegacyServerTrackId(trackId, jobId, resolvedTitle, resolvedArtist, null)
                maybeSelectPlaylistTrack(
                    playlistTrackForServerTrack(jobId, resolvedTitle, resolvedArtist, null)
                )
                return@launchServerTrackLoadWithCache remoteTrackLoadCoordinator.loadOrPoll(
                    existing,
                    fallbackJobId = jobId
                )
            }
            val response = serverGateway.startVideoAnalysis(
                baseUrl,
                trackId,
                resolvedTitle,
                resolvedArtist
            )
            if (response.status == "failed") {
                playbackCoordinator.setAnalysisError(
                    ErrorDisplay.format(
                        raw = response.error,
                        errorCode = response.errorCode,
                        sourceProvider = response.sourceProvider ?: SOURCE_PROVIDER_YOUTUBE,
                        fallback = "Loading failed."
                    )
                )
                return@launchServerTrackLoadWithCache true
            }
            val responseId = canonicalJobId(response.id) ?: return@launchServerTrackLoadWithCache false
            migrateLegacyServerTrackId(trackId, responseId, resolvedTitle, resolvedArtist, null)
            maybeSelectPlaylistTrack(
                playlistTrackForServerTrack(responseId, resolvedTitle, resolvedArtist, null)
            )
            playbackCoordinator.setAnalysisQueued(response.progress?.roundToInt(), response.message)
            playbackCoordinator.setLastJobId(responseId)
            playbackCoordinator.startPoll(responseId)
            true
        }
    }

    fun submitTrackUrl(rawUrl: String) = submitTrackUrl(rawUrl, bypassConfigGate = false)

    private fun submitTrackUrl(rawUrl: String, bypassConfigGate: Boolean) {
        if (blockPlaybackChangeWhileLoading()) return
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank() || !(state.value.allowUserUrl || bypassConfigGate)) return
        val normalized = normalizeSupportedSourceUrl(rawUrl)
        if (normalized == null) {
            updateSearchState {
                it.copy(urlErrorMessage = "Enter a YouTube, SoundCloud, or Bandcamp link.")
            }
            return
        }
        clearInactiveSavedPlaylistBeforeOutsideSelection()
        analytics.logUpload("url")
        if (state.value.playback.isCasting) {
            updateSearchState(::resetSearchStateAfterTrackSelection)
            applyActiveTab(TabId.Play, recordHistory = true)
            launchCastSelection {
                val jobId = queueUrlAnalysisForCast(normalized) ?: return@launchCastSelection
                castPlaybackCoordinator.castTrackId(
                    jobId = jobId,
                    youtubeId = normalized.youtubeId
                )
            }
            return
        }
        prepareServerTrackLoad(tuningParams = null) { current ->
            current.copy(
                search = resetSearchStateAfterTrackSelection(current.search),
                playback = current.playback.copy(
                    audioLoading = false,
                    lastYouTubeId = normalized.youtubeId
                )
            )
        }
        launchServerTrackLoadWithCache(
            cachedJobId = null,
            failureLogMessage = "Failed to start URL analysis"
        ) {
            // Known-video fast path: skips the slow server-side source resolution when the job
            // already exists. Lookup failures fall through to the URL endpoint, which dedupes too.
            if (normalized.youtubeId != null) {
                val existing = try {
                    serverGateway.getJobBySource(baseUrl, SOURCE_PROVIDER_YOUTUBE, normalized.youtubeId)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: IllegalStateException) {
                    null
                }
                val existingJobId = canonicalJobId(existing?.id)
                if (existing != null && existingJobId != null) {
                    return@launchServerTrackLoadWithCache remoteTrackLoadCoordinator.loadOrPoll(
                        existing,
                        fallbackJobId = existingJobId
                    )
                }
            }
            val response = try {
                serverGateway.startUrlAnalysis(baseUrl, normalized.url, null, null)
            } catch (error: HttpStatusException) {
                val message = urlAnalysisHttpErrorMessage(
                    statusCode = error.statusCode,
                    responseBody = error.responseBody,
                    sourceProvider = normalized.provider
                ) ?: throw error
                playbackCoordinator.setAnalysisError(message)
                return@launchServerTrackLoadWithCache true
            }
            remoteTrackLoadCoordinator.loadOrPoll(response)
        }
    }

    fun uploadTrackFile(uri: Uri, displayName: String?) =
        uploadTrackFile(uri, displayName, bypassConfigGate = false)

    private fun uploadTrackFile(uri: Uri, displayName: String?, bypassConfigGate: Boolean) {
        if (blockPlaybackChangeWhileLoading()) return
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank() || !(state.value.allowUserUpload || bypassConfigGate)) return
        val resolver = getApplication<Application>().contentResolver
        val mimeType = runCatching { resolver.getType(uri) }.getOrNull()
        val resolvedDisplayName = displayName ?: queryDisplayName(uri)
        val fileName =
            resolveUploadFileName(resolvedDisplayName, mimeType, state.value.allowedUploadExts)
        if (fileName == null) {
            viewModelScope.launch {
                showToast(unsupportedUploadTypeMessage(state.value.allowedUploadExts))
            }
            return
        }
        val sizeBytes = queryContentSize(uri)
        val maxUploadSize = state.value.maxUploadSizeBytes
        if (maxUploadSize != null && sizeBytes != null && sizeBytes > maxUploadSize) {
            viewModelScope.launch {
                showToast(
                    "This file is larger than the server's " +
                        "${formatUploadSizeLimitMb(maxUploadSize)} upload limit."
                )
            }
            return
        }
        // Local duration probe saves a doomed upload; the server's 422 remains the authority.
        if (showTrackLengthLimitIfExceeded(probeContentDurationSeconds(uri))) {
            return
        }
        clearInactiveSavedPlaylistBeforeOutsideSelection()
        analytics.logUpload("file")
        val title = uploadTitleFromFileName(fileName)
        if (state.value.playback.isCasting) {
            applyActiveTab(TabId.Play, recordHistory = true)
            launchCastSelection {
                val jobId = queueUploadForCast(uri, fileName, sizeBytes, mimeType)
                    ?: return@launchCastSelection
                castPlaybackCoordinator.castTrackId(jobId = jobId, title = title)
            }
            return
        }
        prepareServerTrackLoad(tuningParams = null) { current ->
            current.copy(
                search = resetSearchStateAfterTrackSelection(current.search),
                playback = current.playback.copy(
                    audioLoading = false,
                    trackTitle = title
                )
            )
        }
        launchServerTrackLoadWithCache(
            cachedJobId = null,
            failureLogMessage = "Failed to upload track"
        ) {
            val response = try {
                uploadTrackToServer(baseUrl, uri, fileName, sizeBytes, mimeType)
            } catch (error: HttpStatusException) {
                val message = uploadHttpErrorMessage(error.statusCode, error.responseBody)
                    ?: throw error
                playbackCoordinator.setAnalysisError(message)
                return@launchServerTrackLoadWithCache true
            }
            remoteTrackLoadCoordinator.loadOrPoll(response)
        }
    }

    /**
     * Run the multipart upload with progress relayed into the loading UI. The progress callback
     * fires on OkHttp's IO thread and keeps firing if the surrounding coroutine is cancelled
     * mid-body (the blocking call can't be interrupted), so it re-checks the job before touching
     * state to keep a stale upload from stomping a newer track's loading screen.
     */
    private suspend fun uploadTrackToServer(
        baseUrl: String,
        uri: Uri,
        fileName: String,
        sizeBytes: Long?,
        mimeType: String?
    ): TrackAnalysisResult {
        val resolver = getApplication<Application>().contentResolver
        // Fail fast on an unreadable URI before any bytes hit the network.
        withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.close()
                ?: throw IOException("Unable to open $uri")
        }
        val requestJob = kotlin.coroutines.coroutineContext[Job]
        var lastPercent = -1
        val onBytesWritten: ((Long) -> Unit)? = if (sizeBytes != null && sizeBytes > 0) {
            { bytes ->
                val percent = ((bytes * 100) / sizeBytes).toInt().coerceIn(0, 100)
                if (percent != lastPercent && requestJob?.isActive != false) {
                    lastPercent = percent
                    playbackCoordinator.setAnalysisProgress(percent, "Uploading...")
                }
            }
        } else {
            null
        }
        return serverGateway.uploadTrack(
            baseUrl = baseUrl,
            fileName = fileName,
            sizeBytes = sizeBytes ?: -1L,
            contentType = mimeType,
            onBytesWritten = onBytesWritten
        ) {
            resolver.openInputStream(uri) ?: throw IOException("Unable to open $uri")
        }
    }

    private suspend fun queueUrlAnalysisForCast(normalized: NormalizedSourceUrl): String? {
        val baseUrl = state.value.baseUrl.trim()
        if (baseUrl.isBlank()) {
            showToast("Unable to queue this track for casting.")
            return null
        }
        return try {
            val response = serverGateway.startUrlAnalysis(baseUrl, normalized.url, null, null)
            if (response.status == "failed") {
                showToast(
                    ErrorDisplay.format(
                        raw = response.error,
                        errorCode = response.errorCode,
                        sourceProvider = response.sourceProvider ?: normalized.provider,
                        fallback = "Unable to queue this track for casting."
                    )
                )
                return null
            }
            canonicalJobId(response.id)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: HttpStatusException) {
            if (error.statusCode == 422) {
                showServerTrackLengthLimitError()
            } else {
                showToast(
                    urlAnalysisHttpErrorMessage(
                        statusCode = error.statusCode,
                        responseBody = error.responseBody,
                        sourceProvider = normalized.provider
                    ) ?: "Unable to queue this track for casting."
                )
            }
            null
        } catch (_: IOException) {
            showToast("Unable to queue this track for casting.")
            null
        } catch (_: IllegalArgumentException) {
            showToast("Unable to queue this track for casting.")
            null
        } catch (_: IllegalStateException) {
            showToast("Unable to queue this track for casting.")
            null
        }
    }

    private suspend fun queueUploadForCast(
        uri: Uri,
        fileName: String,
        sizeBytes: Long?,
        mimeType: String?
    ): String? {
        val baseUrl = state.value.baseUrl.trim()
        if (baseUrl.isBlank()) {
            showToast("Unable to queue this track for casting.")
            return null
        }
        return try {
            val response = uploadTrackToServer(baseUrl, uri, fileName, sizeBytes, mimeType)
            if (response.status == "failed") {
                showToast(
                    ErrorDisplay.format(
                        raw = response.error,
                        errorCode = response.errorCode,
                        fallback = "Unable to queue this track for casting."
                    )
                )
                return null
            }
            canonicalJobId(response.id)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: HttpStatusException) {
            if (error.statusCode == 422) {
                showServerTrackLengthLimitError()
            } else {
                showToast(
                    uploadHttpErrorMessage(error.statusCode, error.responseBody)
                        ?: "Unable to queue this track for casting."
                )
            }
            null
        } catch (_: IOException) {
            showToast("Unable to queue this track for casting.")
            null
        } catch (_: IllegalArgumentException) {
            showToast("Unable to queue this track for casting.")
            null
        } catch (_: IllegalStateException) {
            showToast("Unable to queue this track for casting.")
            null
        }
    }

    private fun probeContentDurationSeconds(uri: Uri): Double? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(getApplication(), uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.let { it / 1000.0 }
        } finally {
            retriever.release()
        }
    }.getOrNull()

    fun clearUrlErrorMessage() {
        if (state.value.search.urlErrorMessage == null) return
        updateSearchState { it.copy(urlErrorMessage = null) }
    }

    fun loadTrackById(
        trackId: String,
        title: String? = null,
        artist: String? = null,
        tuningParams: String? = null,
        playAfterLoaded: Boolean = false
    ) {
        if (blockPlaybackChangeWhileLoading()) return
        clearInactiveSavedPlaylistBeforeOutsideSelection()
        loadTrackByIdInternal(
            trackId,
            title,
            artist,
            tuningParams,
            playAfterLoaded = playAfterLoaded,
            ignoreLoadingLock = true
        )
    }

    private fun loadTrackByIdInternal(
        trackId: String,
        title: String? = null,
        artist: String? = null,
        tuningParams: String? = null,
        playAfterLoaded: Boolean = false,
        ignoreLoadingLock: Boolean = false
    ) {
        if (!ignoreLoadingLock && blockPlaybackChangeWhileLoading()) return
        val parsed = parseTrackId(trackId) ?: return
        when {
            parsed.youtubeId != null -> {
                if (ignoreLoadingLock) {
                    loadTrackBySource(
                        sourceProvider = SOURCE_PROVIDER_YOUTUBE,
                        sourceId = parsed.youtubeId,
                        title = title,
                        artist = artist,
                        tuningParams = tuningParams,
                        playAfterLoaded = playAfterLoaded,
                        ignoreLoadingLock = true
                    )
                } else {
                    loadTrackByYoutubeId(
                        youtubeId = parsed.youtubeId,
                        title = title,
                        artist = artist,
                        tuningParams = tuningParams,
                        playAfterLoaded = playAfterLoaded
                    )
                }
            }
            parsed.jobId != null -> {
                if (ignoreLoadingLock) {
                    loadTrackByJobIdInternal(
                        jobId = parsed.jobId,
                        title = title,
                        artist = artist,
                        tuningParams = tuningParams,
                        playAfterLoaded = playAfterLoaded,
                        ignoreLoadingLock = true
                    )
                } else {
                    loadTrackByJobId(
                        jobId = parsed.jobId,
                        title = title,
                        artist = artist,
                        tuningParams = tuningParams,
                        playAfterLoaded = playAfterLoaded
                    )
                }
            }
        }
    }

    fun loadTrackByYoutubeId(
        youtubeId: String,
        title: String? = null,
        artist: String? = null,
        tuningParams: String? = null,
        playAfterLoaded: Boolean = false
    ) {
        if (blockPlaybackChangeWhileLoading()) return
        clearInactiveSavedPlaylistBeforeOutsideSelection()
        loadTrackBySource(
            sourceProvider = SOURCE_PROVIDER_YOUTUBE,
            sourceId = youtubeId,
            title = title,
            artist = artist,
            tuningParams = tuningParams,
            playAfterLoaded = playAfterLoaded,
            ignoreLoadingLock = true
        )
    }

    private fun loadTrackBySource(
        sourceProvider: String,
        sourceId: String,
        title: String? = null,
        artist: String? = null,
        tuningParams: String? = null,
        playAfterLoaded: Boolean = false,
        ignoreLoadingLock: Boolean = false
    ) {
        if (!ignoreLoadingLock && blockPlaybackChangeWhileLoading()) return
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        val provider = sourceProviderFromRaw(sourceProvider) ?: return
        val normalizedSourceId = sourceId.trim()
        if (normalizedSourceId.isBlank()) return
        val youtubeId = if (provider == SOURCE_PROVIDER_YOUTUBE) normalizedSourceId else null
        val metadata = resolveTrackLoadMetadata(
            trackId = youtubeId ?: normalizedSourceId,
            title = title,
            artist = artist,
            search = state.value.search,
            favorites = state.value.favorites
        )
        val resolvedTitle = metadata.title
        val resolvedArtist = metadata.artist
        if (state.value.playback.isCasting) {
            applyActiveTab(TabId.Play, recordHistory = true)
            launchCastSelection {
                val knownJobId = resolveKnownJobIdForSource(
                    state = state.value,
                    sourceProvider = provider,
                    sourceId = normalizedSourceId
                )
                if (!knownJobId.isNullOrBlank()) {
                    castPlaybackCoordinator.castTrackId(
                        jobId = knownJobId,
                        title = resolvedTitle,
                        artist = resolvedArtist,
                        youtubeId = youtubeId,
                        tuningParams = tuningParams
                    )
                    return@launchCastSelection
                }

                if (provider == SOURCE_PROVIDER_YOUTUBE) {
                    try {
                        val existing = serverGateway.getJobBySource(baseUrl, provider, normalizedSourceId)
                        val resolvedJobId = canonicalJobId(existing?.id)
                        if (!resolvedJobId.isNullOrBlank()) {
                            migrateLegacyServerTrackId(
                                normalizedSourceId,
                                resolvedJobId,
                                resolvedTitle,
                                resolvedArtist,
                                tuningParams
                            )
                            castPlaybackCoordinator.castTrackId(
                                jobId = resolvedJobId,
                                title = resolvedTitle,
                                artist = resolvedArtist,
                                youtubeId = youtubeId,
                                tuningParams = tuningParams
                            )
                            return@launchCastSelection
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: HttpStatusException) {
                        if (error.statusCode == 422) {
                            showServerTrackLengthLimitError()
                            return@launchCastSelection
                        }
                    } catch (_: IOException) {
                    } catch (_: IllegalArgumentException) {
                    } catch (_: IllegalStateException) {
                    }
                    val resolvedJobId = queueYoutubeAnalysisForCast(
                        youtubeId = normalizedSourceId,
                        title = resolvedTitle,
                        artist = resolvedArtist
                    )
                    if (resolvedJobId == null) {
                        return@launchCastSelection
                    }
                    migrateLegacyServerTrackId(
                        normalizedSourceId,
                        resolvedJobId,
                        resolvedTitle,
                        resolvedArtist,
                        tuningParams
                    )
                    castPlaybackCoordinator.castTrackId(
                        jobId = resolvedJobId,
                        title = resolvedTitle,
                        artist = resolvedArtist,
                        youtubeId = youtubeId,
                        tuningParams = tuningParams
                    )
                    return@launchCastSelection
                }

                try {
                    val existing = serverGateway.getJobBySource(baseUrl, provider, normalizedSourceId)
                    val resolvedJobId = canonicalJobId(existing?.id)
                    if (resolvedJobId.isNullOrBlank()) {
                        showToast("Unable to queue this track for casting.")
                        return@launchCastSelection
                    }
                    migrateLegacyServerTrackId(
                        normalizedSourceId,
                        resolvedJobId,
                        resolvedTitle,
                        resolvedArtist,
                        tuningParams
                    )
                    castPlaybackCoordinator.castTrackId(
                        jobId = resolvedJobId,
                        title = resolvedTitle,
                        artist = resolvedArtist,
                        tuningParams = tuningParams
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: HttpStatusException) {
                    if (error.statusCode == 422) {
                        showServerTrackLengthLimitError()
                        return@launchCastSelection
                    }
                    AppLog.error(TAG, "Failed to resolve source for cast", error)
                    showToast("Unable to queue this track for casting.")
                } catch (error: IOException) {
                    AppLog.error(TAG, "Failed to resolve source for cast", error)
                    showToast("Unable to queue this track for casting.")
                } catch (error: IllegalArgumentException) {
                    AppLog.error(TAG, "Failed to resolve source for cast", error)
                    showToast("Unable to queue this track for casting.")
                } catch (error: IllegalStateException) {
                    AppLog.error(TAG, "Failed to resolve source for cast", error)
                    showToast("Unable to queue this track for casting.")
                }
            }
            return
        }
        prepareServerTrackLoad(tuningParams = tuningParams) { current ->
            current.copy(
                playback = current.playback.copy(
                    audioLoading = false,
                    lastYouTubeId = youtubeId,
                    trackTitle = resolvedTitle,
                    trackArtist = resolvedArtist,
                    playAfterLoaded = playAfterLoaded
                )
            )
        }
        launchServerTrackLoadWithCache(
            cachedJobId = null,
            failureLogMessage = "Failed to load track by source"
        ) {
            val existing = serverGateway.getJobBySource(baseUrl, provider, normalizedSourceId)
            if (existing != null) {
                val jobId = canonicalJobId(existing.id)
                    ?: return@launchServerTrackLoadWithCache false
                migrateLegacyServerTrackId(
                    normalizedSourceId,
                    jobId,
                    resolvedTitle,
                    resolvedArtist,
                    tuningParams
                )
                maybeSelectPlaylistTrack(
                    playlistTrackForServerTrack(jobId, resolvedTitle, resolvedArtist, tuningParams)
                )
                return@launchServerTrackLoadWithCache remoteTrackLoadCoordinator.loadOrPoll(
                    existing,
                    fallbackJobId = jobId
                )
            }
            if (provider != SOURCE_PROVIDER_YOUTUBE) {
                return@launchServerTrackLoadWithCache false
            }
            val started = serverGateway.startVideoAnalysis(
                baseUrl = baseUrl,
                videoId = normalizedSourceId,
                title = resolvedTitle,
                artist = resolvedArtist
            )
            if (started.status == "failed") {
                playbackCoordinator.setAnalysisError(
                    ErrorDisplay.format(
                        raw = started.error,
                        errorCode = started.errorCode,
                        sourceProvider = started.sourceProvider ?: provider,
                        fallback = "Loading failed."
                    )
                )
                return@launchServerTrackLoadWithCache true
            }
            val responseId = canonicalJobId(started.id) ?: return@launchServerTrackLoadWithCache false
            migrateLegacyServerTrackId(
                normalizedSourceId,
                responseId,
                resolvedTitle,
                resolvedArtist,
                tuningParams
            )
            maybeSelectPlaylistTrack(
                playlistTrackForServerTrack(responseId, resolvedTitle, resolvedArtist, tuningParams)
            )
            playbackCoordinator.setAnalysisQueued(started.progress?.roundToInt(), started.message)
            playbackCoordinator.setLastJobId(responseId)
            playbackCoordinator.startPoll(responseId)
            true
        }
    }

    fun loadTrackByJobId(
        jobId: String,
        title: String? = null,
        artist: String? = null,
        tuningParams: String? = null,
        playAfterLoaded: Boolean = false
    ) {
        if (blockPlaybackChangeWhileLoading()) return
        loadTrackByJobIdInternal(
            jobId = jobId,
            title = title,
            artist = artist,
            tuningParams = tuningParams,
            playAfterLoaded = playAfterLoaded,
            ignoreLoadingLock = true
        )
    }

    private fun loadTrackByJobIdInternal(
        jobId: String,
        title: String? = null,
        artist: String? = null,
        tuningParams: String? = null,
        playAfterLoaded: Boolean = false,
        ignoreLoadingLock: Boolean = false
    ) {
        if (!ignoreLoadingLock && blockPlaybackChangeWhileLoading()) return
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        val normalizedJobId = canonicalJobId(jobId) ?: return
        val metadata = resolveTrackLoadMetadata(
            trackId = normalizedJobId,
            title = title,
            artist = artist,
            search = state.value.search,
            favorites = state.value.favorites
        )
        val resolvedTitle = metadata.title
        val resolvedArtist = metadata.artist
        if (state.value.playback.isCasting) {
            castPlaybackCoordinator.castTrackId(
                jobId = normalizedJobId,
                title = resolvedTitle,
                artist = resolvedArtist,
                tuningParams = tuningParams
            )
            applyActiveTab(TabId.Play, recordHistory = true)
            return
        }
        prepareServerTrackLoad(tuningParams = tuningParams) { current ->
            current.copy(
                playback = current.playback.copy(
                    audioLoading = false,
                    lastYouTubeId = null,
                    lastJobId = normalizedJobId,
                    trackTitle = resolvedTitle,
                    trackArtist = resolvedArtist,
                    playAfterLoaded = playAfterLoaded
                )
            )
        }
        launchServerTrackLoadWithCache(
            cachedJobId = normalizedJobId,
            failureLogMessage = "Failed to load track by job id"
        ) {
            val response = loadRemoteExplicitJobInitialResponse(
                fetchJob = {
                    serverGateway.getAnalysis(baseUrl, normalizedJobId)
                },
                retryJob = {
                    serverGateway.retryJob(baseUrl, normalizedJobId)
                }
            )
            remoteTrackLoadCoordinator.loadOrPoll(response, fallbackJobId = normalizedJobId)
        }
    }

    private suspend fun loadExistingJob(
        jobId: String,
        trackId: String,
        response: TrackAnalysisResult,
        title: String? = null,
        artist: String? = null
    ) {
        val canonicalJobTrackId = canonicalJobId(jobId) ?: return
        val youtubeId = parseTrackId(trackId)?.youtubeId
        if (blockPlaybackChangeWhileLoading(showToast = false)) return
        if (state.value.playback.isCasting) {
            castPlaybackCoordinator.castTrackId(
                jobId = canonicalJobTrackId,
                title = title,
                artist = artist,
                youtubeId = youtubeId
            )
            applyActiveTab(TabId.Play, recordHistory = true)
            return
        }
        playbackCoordinator.resetForNewTrack(stopPlaybackService = false)
        _state.update {
            it.copy(
                search = resetSearchStateAfterTrackSelection(it.search),
                playback = it.playback.copy(
                    lastJobId = canonicalJobTrackId,
                    lastYouTubeId = youtubeId,
                    trackTitle = title,
                    trackArtist = artist
                )
            )
        }
        applyActiveTab(TabId.Play, recordHistory = true)
        playbackCoordinator.setAnalysisQueued(null, response.message)
        try {
            val handled = remoteTrackLoadCoordinator.loadOrPoll(
                response,
                fallbackJobId = canonicalJobTrackId
            )
            if (handled) {
                return
            }
            playbackCoordinator.setAnalysisError("Loading failed.")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: IOException) {
            AppLog.warn(TAG, "Failed to load existing job", error)
            playbackCoordinator.setAnalysisError("Loading failed.")
        } catch (error: IllegalArgumentException) {
            AppLog.warn(TAG, "Failed to load existing job", error)
            playbackCoordinator.setAnalysisError("Loading failed.")
        } catch (error: IllegalStateException) {
            AppLog.warn(TAG, "Failed to load existing job", error)
            playbackCoordinator.setAnalysisError("Loading failed.")
        }
    }

    private fun prepareServerTrackLoad(
        tuningParams: String?,
        stateUpdate: (UiState) -> UiState
    ) {
        remoteTrackLoadCoordinator.cancel()
        playbackCoordinator.resetForNewTrack(stopPlaybackService = false)
        playbackCoordinator.setPendingTuningParams(tuningParams)
        _state.update(stateUpdate)
        applyActiveTab(TabId.Play, recordHistory = true)
    }

    private fun launchServerTrackLoadWithCache(
        cachedJobId: String?,
        failureLogMessage: String,
        request: suspend () -> Boolean
    ) {
        remoteTrackLoadCoordinator.launch {
            if (cachedJobId != null && playbackCoordinator.tryLoadCachedTrack(cachedJobId)) {
                return@launch
            }
            diagnostics.logAnalysisStarted("server")
            playbackCoordinator.setAnalysisQueued(null, "Fetching audio...")
            var attempt = 1
            while (true) {
                try {
                    val handled = request()
                    if (handled) {
                        diagnostics.logAnalysisCompleted("server")
                    } else {
                        diagnostics.logAnalysisFailed("server", "unhandled")
                        playbackCoordinator.setAnalysisError("Loading failed.")
                    }
                    return@launch
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: HttpStatusException) {
                    if (error.statusCode == 422) {
                        diagnostics.logAnalysisFailed("server", "track_length_limit")
                        showServerTrackLengthLimitError()
                    } else {
                        diagnostics.logAnalysisFailed("server", error.javaClass.simpleName)
                        AppLog.warn(TAG, failureLogMessage, error)
                        playbackCoordinator.setAnalysisError("Loading failed.")
                    }
                    return@launch
                } catch (error: IOException) {
                    // UnknownHostException/ConnectException here usually means Android is
                    // gating the app's network (doze/app standby, network transition), not
                    // that the host is down — the same restriction that transiently blocks
                    // MediaCodec. Both clear on their own, so back off and retry, preferring
                    // the cached copy over re-downloading.
                    if (isRetryableNetworkError(error) && attempt < SERVER_LOAD_NETWORK_MAX_ATTEMPTS) {
                        AppLog.warn(TAG, "$failureLogMessage (attempt $attempt); retrying", error)
                        delay(SERVER_LOAD_NETWORK_BASE_RETRY_DELAY_MS shl (attempt - 1))
                        attempt += 1
                        if (cachedJobId != null && playbackCoordinator.tryLoadCachedTrack(cachedJobId)) {
                            diagnostics.logAnalysisCompleted("server")
                            return@launch
                        }
                        playbackCoordinator.setAnalysisQueued(null, "Fetching audio...")
                        continue
                    }
                    diagnostics.logAnalysisFailed("server", error.javaClass.simpleName)
                    AppLog.warn(TAG, failureLogMessage, error)
                    if (isRetryableNetworkError(error)) {
                        playbackCoordinator.setAnalysisError("Network error.", expected = true)
                    } else {
                        playbackCoordinator.setAnalysisError("Loading failed.")
                    }
                    return@launch
                } catch (error: IllegalArgumentException) {
                    diagnostics.logAnalysisFailed("server", error.javaClass.simpleName)
                    AppLog.warn(TAG, failureLogMessage, error)
                    playbackCoordinator.setAnalysisError("Loading failed.")
                    return@launch
                } catch (error: IllegalStateException) {
                    diagnostics.logAnalysisFailed("server", error.javaClass.simpleName)
                    AppLog.warn(TAG, failureLogMessage, error)
                    playbackCoordinator.setAnalysisError("Loading failed.")
                    return@launch
                }
            }
        }
    }

    private suspend fun ensureJukeboxRuntimeReady(current: PlaybackState): Boolean {
        if (!current.audioLoaded || !controller.player.hasAudio()) {
            val audioReady = playbackCoordinator.ensureAudioReady()
            if (!audioReady) {
                playbackCoordinator.setAnalysisError("Audio unavailable. Reload the track.")
                return false
            }
        }
        val hasAnalysis = controller.engine.getGraphState() != null
        if (hasAnalysis) {
            return true
        }
        val trackId = current.shareTrackIdOrNull()
        if (!trackId.isNullOrBlank()) {
            playbackCoordinator.setAnalysisQueued(null, "Restoring track...")
            loadTrackByIdInternal(
                trackId = trackId,
                title = current.trackTitle,
                artist = current.trackArtist,
                ignoreLoadingLock = true
            )
            return false
        }
        playbackCoordinator.setAnalysisError("Analysis unavailable. Reload the track.")
        return false
    }

    // Refires only when the mode or track changes, never on pause/resume,
    // matching the web app's play-event dedup.
    private var lastLoggedPlayKey: String? = null

    private fun logPlayStarted() {
        val playback = state.value.playback
        val trackId = playback.analyticsPlayTrackId()
        if (trackId == null) {
            // A track with no analytics id is now playing. Clear the dedup key so a later
            // replay of a previously-logged track is not mistaken for a resume.
            lastLoggedPlayKey = null
            return
        }
        val mode = analyticsPlayMode(playback.playMode)
        val key = "$mode:$trackId"
        if (lastLoggedPlayKey == key) return
        lastLoggedPlayKey = key
        analytics.logPlay(mode, trackId, analyticsPlayTrackTitle(trackId, playback.trackTitle))
    }

    fun togglePlayback() {
        val current = state.value.playback
        if (blockPlaybackChangeWhileLoading(showToast = false)) return
        if (
            shouldRetryFailedLoadFromTransport(state.value) &&
            !canPlayLoadedTrackFromMemory(current)
        ) {
            retryFailedLoad()
            return
        }
        if (current.isCasting) {
            toggleCastPlayback(current)
            return
        }
        if (!current.analysisLoaded) return
        if (current.playMode == PlaybackMode.Autocanonizer) {
            toggleAutocanonizerPlayback(current)
            return
        }
        if (current.isRunning) {
            pauseJukeboxPlayback()
            return
        }
        startOrResumeJukeboxPlayback(current)
    }

    private fun toggleCastPlayback(current: PlaybackState) {
        if (!state.value.castEnabled) {
            notifyCastUnavailable()
            return
        }
        if (current.lastJobId.isNullOrBlank()) {
            viewModelScope.launch { showToast("Select a track before playing.") }
            return
        }
        val command = if (current.isRunning) "pause" else "play"
        val sent = sendCastCommand(command)
        if (!sent) {
            viewModelScope.launch { showToast("Connect to a Cast device first.") }
            return
        }
        if (command == "play") {
            logPlayStarted()
        }
        _state.update {
            it.copy(
                playback = it.playback.copy(
                    isRunning = !current.isRunning,
                    isPaused = current.isRunning
                )
            )
        }
        syncCastNotification()
    }

    private fun startOrResumeJukeboxPlayback(current: PlaybackState) {
        viewModelScope.launch {
            if (!ensureJukeboxRuntimeReady(current)) {
                return@launch
            }
            try {
                if (controller.getTrackTitle().isNullOrBlank() && !current.trackTitle.isNullOrBlank()) {
                    controller.setTrackMeta(current.trackTitle, current.trackArtist)
                }
                val wasPaused = current.isPaused
                if (!wasPaused) {
                    lastCowbellBeatsPlayed = -1
                }
                val result = controller.playOrResumePlaybackResult()
                val running = result == PlaybackStartResult.Started
                val paused = controller.isPaused()
                playbackCoordinator.updateListenTimeDisplay()
                _state.update {
                    it.copy(
                        playback = it.playback.copy(
                            beatsPlayed = if (wasPaused) it.playback.beatsPlayed else 0,
                            currentBeatIndex = if (wasPaused) it.playback.currentBeatIndex else -1,
                            canonizerOtherIndex = null,
                            isRunning = running,
                            isPaused = paused
                        )
                    )
                }
                when {
                    running -> {
                        logPlayStarted()
                        playbackCoordinator.clearAnalysisErrorForPlaybackStart()
                        playbackCoordinator.startListenTimer()
                        syncPlaybackServiceSession()
                    }
                    paused -> {
                        playbackCoordinator.stopListenTimer()
                        syncPlaybackServiceSession()
                    }
                    else -> handleJukeboxPlaybackFailure(reason = result.toString())
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: IllegalArgumentException) {
                AppLog.error(TAG, "Playback toggle failed", error)
                handleJukeboxPlaybackFailure(reason = "togglePlayback threw ${error::class.simpleName}")
            } catch (error: IllegalStateException) {
                AppLog.error(TAG, "Playback toggle failed", error)
                handleJukeboxPlaybackFailure(reason = "togglePlayback threw ${error::class.simpleName}")
            }
        }
    }

    private fun pauseJukeboxPlayback() {
        controller.pausePlayback()
        playbackCoordinator.stopListenTimer()
        playbackCoordinator.updateListenTimeDisplay()
        _state.update {
            it.copy(
                playback = it.playback.copy(
                    isRunning = false,
                    isPaused = true,
                    canonizerOtherIndex = null
                )
            )
        }
        syncPlaybackServiceSession()
    }

    private fun handleJukeboxPlaybackFailure(reason: String) {
        AppLog.error(TAG, "Jukebox playback failure surfaced to UI: $reason")
        playbackCoordinator.stopListenTimer()
        hardStopPlaybackServiceSession()
        playbackCoordinator.setAnalysisError("Playback failed.")
    }

    private fun toggleAutocanonizerPlayback(current: PlaybackState) {
        if (current.isRunning) {
            controller.autocanonizer.pause()
            controller.pauseExternalPlayback()
            playbackCoordinator.stopListenTimer()
            playbackCoordinator.updateListenTimeDisplay()
            _state.update {
                it.copy(
                    playback = playbackStateAfterAutocanonizerPause(it.playback)
                )
            }
            syncPlaybackServiceSession()
        } else if (current.isPaused) {
            resumeAutocanonizerPlayback()
        } else {
            startAutocanonizerPlayback(0)
        }
    }

    private fun resumeAutocanonizerPlayback() {
        val current = state.value.playback
        viewModelScope.launch {
            if (!ensureAutocanonizerReady(current)) {
                return@launch
            }
            val resumed = controller.requestAudioFocusForLocalPlayback() &&
                controller.autocanonizer.resume()
            if (!resumed) {
                val fallbackIndex = current.currentBeatIndex.takeIf { it >= 0 } ?: 0
                val started = startAutocanonizerTransport(
                    controller = controller,
                    index = fallbackIndex,
                    resetTimers = false
                )
                if (!started) {
                    playbackCoordinator.setAnalysisError("Autocanonizer not ready.")
                    return@launch
                }
            } else {
                controller.startExternalPlayback(resetTimers = false)
            }
            playbackCoordinator.updateListenTimeDisplay()
            _state.update {
                it.copy(
                    playback = it.playback.copy(
                        isRunning = true,
                        isPaused = false,
                        canonizerOtherIndex = controller.autocanonizer.getForcedOtherIndex(),
                        canonizerTileColorOverrides = controller.autocanonizer.getTileColorOverrides()
                    )
                )
            }
            playbackCoordinator.startListenTimer()
            syncPlaybackServiceSession()
        }
    }

    private fun startAutocanonizerPlayback(index: Int) {
        val current = state.value.playback
        viewModelScope.launch {
            if (!ensureAutocanonizerReady(current)) {
                return@launch
            }
            _state.update {
                it.copy(
                    playback = playbackStateAfterAutocanonizerStart(it.playback)
                )
            }
            val started = startAutocanonizerTransport(
                controller = controller,
                index = index,
                resetTimers = true
            )
            if (!started) {
                playbackCoordinator.setAnalysisError("Autocanonizer not ready.")
                return@launch
            }
            playbackCoordinator.updateListenTimeDisplay()
            _state.update {
                it.copy(
                    playback = it.playback.copy(
                        canonizerTileColorOverrides = controller.autocanonizer.getTileColorOverrides(),
                        isRunning = true,
                        isPaused = false
                    )
                )
            }
            logPlayStarted()
            playbackCoordinator.startListenTimer()
            syncPlaybackServiceSession()
        }
    }

    private suspend fun ensureAutocanonizerReady(current: PlaybackState): Boolean {
        if (!current.audioLoaded || !controller.player.hasAudio()) {
            val ready = playbackCoordinator.ensureAudioReady()
            if (!ready) {
                playbackCoordinator.setAnalysisError("Audio unavailable. Reload the track.")
                return false
            }
        }
        if (!controller.autocanonizer.isReady()) {
            controller.syncAutocanonizerAudio()
        }
        if (!controller.autocanonizer.isReady()) {
            playbackCoordinator.setAnalysisError("Autocanonizer not ready.")
            return false
        }
        return true
    }

    fun setCastingConnected(isConnected: Boolean, deviceName: String? = null) {
        // Mirrors handleCastingConnected's own already-casting guard against the same state,
        // so reconnects and session resumes report one cast_start per session. The play mode
        // is read before the coordinator forces autocanonizer sessions over to jukebox.
        val playback = state.value.playback
        if (isConnected && !playback.isCasting) {
            analytics.logCastStart(analyticsPlayMode(playback.playMode))
            diagnostics.logCastConnected(analyticsPlayMode(playback.playMode))
        }
        if (!isConnected && playback.isCasting) {
            diagnostics.logCastDisconnected()
        }
        castSessionCoordinator.setCastingConnected(isConnected, deviceName)
    }

    fun stopCasting() {
        cancelCastSelection()
        castSessionCoordinator.stopCasting()
    }

    fun requestCastStatus() {
        castSessionCoordinator.requestCastStatus()
    }

    private fun sendCastCommand(command: String): Boolean {
        return castPlaybackCoordinator.sendCastCommand(command)
    }

    private fun launchCastSelection(block: suspend () -> Unit) {
        cancelCastSelection()
        castSelectionJob = viewModelScope.launch {
            block()
        }
    }

    private fun cancelCastSelection() {
        castSelectionJob?.cancel()
        castSelectionJob = null
    }

    private suspend fun queueYoutubeAnalysisForCast(
        youtubeId: String,
        title: String?,
        artist: String?
    ): String? {
        val normalizedBaseUrl = state.value.baseUrl.trim()
        if (normalizedBaseUrl.isBlank()) {
            showToast("Unable to queue this track for casting.")
            return null
        }
        return try {
            val started = serverGateway.startVideoAnalysis(
                baseUrl = normalizedBaseUrl,
                videoId = youtubeId,
                title = title,
                artist = artist
            )
            if (started.status == "failed") {
                showToast(
                    ErrorDisplay.format(
                        raw = started.error,
                        errorCode = started.errorCode,
                        sourceProvider = started.sourceProvider ?: SOURCE_PROVIDER_YOUTUBE,
                        fallback = "Unable to queue this track for casting."
                    )
                )
                return null
            }
            canonicalJobId(started.id)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: HttpStatusException) {
            if (error.statusCode == 422) {
                showServerTrackLengthLimitError()
            } else {
                showToast("Unable to queue this track for casting.")
            }
            null
        } catch (_: IOException) {
            showToast("Unable to queue this track for casting.")
            null
        } catch (_: IllegalArgumentException) {
            showToast("Unable to queue this track for casting.")
            null
        } catch (_: IllegalStateException) {
            showToast("Unable to queue this track for casting.")
            null
        }
    }

    private fun sendCastVisualizationIndex(index: Int) {
        castPlaybackCoordinator.sendCastVisualizationIndex(index)
    }

    private fun notifyCastUnavailable() {
        viewModelScope.launch {
            showToast("Casting isn't available right now.")
        }
    }

    /**
     * Resolve the upload bodies for a Local-mode cast. Probes the content URI (a persisted permission
     * can lapse on reinstall/move/grant-cap) and requires the cached analysis JSON to exist, throwing
     * [CastSourceUnavailableException] otherwise so the coordinator surfaces the "re-pick the file"
     * error. The audio streams from the content URI without buffering the whole file in memory.
     */
    private fun buildCastLocalUploadSource(
        sourceUri: String,
        cacheKey: String,
        onAudioProgress: (bytesSent: Long, totalBytes: Long?) -> Unit
    ): CastLocalUploadSource {
        val uri = sourceUri.toUri()
        val resolver = getApplication<Application>().contentResolver
        val sizeBytes = queryContentSize(uri)
        try {
            resolver.openInputStream(uri)?.close()
                ?: throw CastSourceUnavailableException("Unable to open $uri")
        } catch (error: SecurityException) {
            throw CastSourceUnavailableException(error.message ?: "Permission lost for $uri", error)
        } catch (error: FileNotFoundException) {
            throw CastSourceUnavailableException(error.message ?: "File not found for $uri", error)
        }
        val analysisFile = localAnalysisService.analysisCacheFile(cacheKey)
        if (!analysisFile.exists()) {
            throw CastSourceUnavailableException("Cached analysis missing for $cacheKey")
        }
        // The relay echoes the audio Content-Type to the receiver verbatim and requires audio/*, so
        // never send a missing or non-audio type.
        val contentType = (resolver.getType(uri)?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg")
            .toMediaTypeOrNull()
        val audioBody = CastRelayClient.streamingBody(
            contentType = contentType,
            sizeBytes = sizeBytes ?: -1L,
            onBytesWritten = { bytes -> onAudioProgress(bytes, sizeBytes) }
        ) {
            resolver.openInputStream(uri) ?: throw IOException("Unable to open $uri")
        }
        val analysisBody = analysisFile.asRequestBody("application/json".toMediaTypeOrNull())
        return CastLocalUploadSource(sizeBytes, audioBody, analysisBody)
    }

    private fun queryContentSize(uri: Uri): Long? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getLong(index)
                } else {
                    null
                }
            }
    }.getOrNull()

    /**
     * The provider's name for a content URI. The server validates uploads by filename suffix and
     * derives the track title from the stem, so a share that arrives without a name still gets one.
     */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getString(index)
                } else {
                    null
                }
            }
    }.getOrNull()

    fun retryFailedLoad() {
        if (blockPlaybackChangeWhileLoading()) return
        val baseUrl = state.value.baseUrl.trim()
        if (baseUrl.isBlank()) {
            viewModelScope.launch { showToast("Set a base URL first.") }
            return
        }
        val retryRequest = failedLoadRetryRequest(state.value.playback)
        if (retryRequest == null) {
            viewModelScope.launch { showToast("Nothing to retry.") }
            return
        }
        remoteTrackLoadCoordinator.cancel()
        playbackCoordinator.resetForNewTrack()
        loadTrackById(
            trackId = retryRequest.trackId,
            title = retryRequest.title,
            artist = retryRequest.artist,
            playAfterLoaded = retryRequest.playAfterLoaded
        )
    }

    suspend fun deleteCurrentJob(): Boolean {
        if (state.value.playback.deleteInFlight) {
            return false
        }
        val jobId = playbackCoordinator.getLastJobId() ?: return false
        val baseUrl = state.value.baseUrl
        val adminKey = state.value.adminKey
        val playback = state.value.playback
        val trackIdsForRemoval = favoriteRemovalTrackIdsForDeletion(
            playback = playback,
            fallbackJobId = jobId
        )
        if (baseUrl.isBlank()) {
            return false
        }
        updatePlaybackState { it.copy(deleteInFlight = true) }
        return try {
            serverGateway.deleteJob(baseUrl, jobId, adminKey)
            if (playback.isCasting) {
                sendCastCommand("reset")
            }
            if (trackIdsForRemoval.isNotEmpty()) {
                playbackCoordinator.clearCachedTrack(jobId)
                val favorites = state.value.favorites
                val updatedFavorites = removeFavoritesForTrackIds(favorites, trackIdsForRemoval)
                if (updatedFavorites.size != favorites.size) {
                    favoritesController.updateFavorites(updatedFavorites)
                }
            }
            playbackCoordinator.resetForNewTrack()
            _state.update {
                val nextTab = defaultTabForMode(it.appMode)
                it.copy(
                    activeTab = nextTab,
                    topSongsTab = TopSongsTab.TopSongs,
                    searchPanelTab = SearchPanelTab.Search
                )
            }
            tabHistory.removeLastOrNull()?.let { last ->
                if (last != TabId.Play) {
                    tabHistory.addLast(last)
                }
            }
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: HttpStatusException) {
            handleDeleteCurrentJobFailure(error, jobId, adminKey)
            false
        } catch (error: IOException) {
            handleDeleteCurrentJobFailure(error, jobId, adminKey)
            false
        } catch (error: IllegalArgumentException) {
            handleDeleteCurrentJobFailure(error, jobId, adminKey)
            false
        } catch (error: IllegalStateException) {
            handleDeleteCurrentJobFailure(error, jobId, adminKey)
            false
        } finally {
            updatePlaybackState { it.copy(deleteInFlight = false) }
        }
    }

    private fun handleDeleteCurrentJobFailure(
        error: Exception,
        jobId: String,
        adminKey: String
    ) {
        AppLog.warn(TAG, "Failed to delete current job", error)
        if (adminKey.isBlank()) {
            playbackCoordinator.markDeleteEligibilityFailed(jobId)
        }
    }

    fun dismissTrackLengthLimitErrorDialog() {
        _state.update { it.copy(trackLengthLimitErrorMessage = null) }
    }

    fun setSleepTimer(option: SleepTimerOption) {
        ForegroundPlaybackService.setSleepTimer(
            context = getApplication(),
            durationMs = option.durationMs
        )
    }

    private fun formatMinutes(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            ((value * 100).roundToInt() / 100.0).toString()
        }
    }

    private fun castTrackLengthLimitErrorMessage(): String {
        return "Sorry, tracks longer than ${CAST_MAX_TRACK_DURATION_MINUTES.toInt()} minutes " +
            "cannot be cast due to Chromecast memory limitations."
    }

    private fun showServerTrackLengthLimitError() {
        val maxTrackLengthMinutes = state.value.maxTrackLengthMinutes
        val message = if (maxTrackLengthMinutes != null && maxTrackLengthMinutes > 0) {
            "The maximum track length for this server is ${formatMinutes(maxTrackLengthMinutes)} minutes."
        } else {
            "This track exceeds the server's maximum allowed length."
        }
        playbackCoordinator.setAnalysisError("Loading failed.")
        _state.update { it.copy(trackLengthLimitErrorMessage = message) }
    }

    private fun showTrackLengthLimitIfExceeded(durationSeconds: Double?): Boolean {
        if (
            durationSeconds == null ||
            durationSeconds.isNaN() ||
            durationSeconds.isInfinite() ||
            durationSeconds <= 0
        ) {
            return false
        }
        if (state.value.playback.isCasting &&
            durationSeconds > CAST_MAX_TRACK_DURATION_MINUTES * 60
        ) {
            _state.update {
                it.copy(
                    trackLengthLimitErrorMessage = castTrackLengthLimitErrorMessage()
                )
            }
            return true
        }
        val maxTrackLengthMinutes = state.value.maxTrackLengthMinutes
        if (maxTrackLengthMinutes != null &&
            maxTrackLengthMinutes > 0 &&
            durationSeconds > maxTrackLengthMinutes * 60
        ) {
            _state.update {
                it.copy(
                    trackLengthLimitErrorMessage =
                        "The maximum track length for this server is " +
                            "${formatMinutes(maxTrackLengthMinutes)} minutes."
                )
            }
            return true
        }
        return false
    }

    fun prepareForExit() {
        appLifecycleCoordinator.prepareForExit()
    }

    fun selectPlaylistTrack(
        index: Int,
        playAfterLoaded: Boolean = false
    ) {
        val playlist = state.value.playlist
        if (!playlist.canSelectTrackAt(index)) return
        if (blockPlaybackChangeWhileLoading()) return
        val track = playlist.tracks[index]
        diagnostics.logPlaylistTrackSelected(index, track.id, track.title)
        _state.update {
            it.copy(playlist = it.playlist.selectTrackAt(index))
        }
        loadPlaylistTrack(
            track = track,
            playAfterLoaded = playAfterLoaded
        )
    }

    fun selectPlaylistDialogTrack(index: Int) {
        selectPlaylistTrack(
            index = index,
            playAfterLoaded = shouldEnablePlayAfterLoadedForPlaylistSkip(state.value)
        )
    }

    fun skipToPreviousPlaylistTrack() {
        val current = state.value
        if (blockPlaybackChangeWhileLoading(showToast = false)) return
        val playlist = current.playlist
        if (!playlist.canSkipPrevious()) return
        selectPlaylistTrack(
            index = playlist.currentIndex - 1,
            playAfterLoaded = shouldEnablePlayAfterLoadedForPlaylistSkip(current)
        )
    }

    fun skipToNextPlaylistTrack() {
        val current = state.value
        if (blockPlaybackChangeWhileLoading(showToast = false)) return
        val playlist = current.playlist
        if (!playlist.canSkipNext()) return
        selectPlaylistTrack(
            index = playlist.currentIndex + 1,
            playAfterLoaded = shouldEnablePlayAfterLoadedForPlaylistSkip(current)
        )
    }

    fun removePlaylistTrack(index: Int) {
        updatePlaylistState { it.removeTrackAt(index) }
        syncPlaybackServiceSession()
    }

    fun clearPlaylist() {
        clearPlaylistState()
        syncPlaybackServiceSession()
    }

    fun selectBeat(index: Int) {
        if (state.value.playback.playMode == PlaybackMode.Autocanonizer) {
            startAutocanonizerPlayback(index)
            return
        }
        val data = state.value.playback.vizData
        val selection = seekOrStartJukeboxAtBeat(controller, index, data)
        if (!selection.success) return
        if (selection.startedPlayback) {
            logPlayStarted()
            playbackCoordinator.startListenTimer()
            playbackCoordinator.updateListenTimeDisplay()
            syncPlaybackServiceSession()
        }
        _state.update {
            it.copy(
                playback = it.playback.copy(
                    currentBeatIndex = index,
                    isRunning = controller.isPlaying(),
                    isPaused = controller.isPaused(),
                    canonizerOtherIndex = null
                )
            )
        }
    }

    fun setFavoritesSort(sortKey: FavoriteSortKey, sortDirection: FavoriteSortDirection) {
        if (
            state.value.favoritesSortKey == sortKey &&
            state.value.favoritesSortDirection == sortDirection
        ) {
            return
        }
        _state.update {
            it.copy(favoritesSortKey = sortKey, favoritesSortDirection = sortDirection)
        }
        viewModelScope.launch {
            preferences.setFavoritesSort(sortKey.name, sortDirection.name)
        }
    }

    fun setLocalAnalysisSort(sortKey: FavoriteSortKey, sortDirection: FavoriteSortDirection) {
        if (
            state.value.localAnalysisSortKey == sortKey &&
            state.value.localAnalysisSortDirection == sortDirection
        ) {
            return
        }
        _state.update {
            it.copy(localAnalysisSortKey = sortKey, localAnalysisSortDirection = sortDirection)
        }
        viewModelScope.launch {
            preferences.setLocalAnalysisSort(sortKey.name, sortDirection.name)
        }
    }

    fun setActiveVisualization(index: Int) {
        if (index !in 0 until visualizationCount) {
            return
        }
        // Only the picker's onClick handlers call this. The saved-preference collector and
        // cast status sync write activeVizIndex straight to state, so restores, initial
        // render, and rotation never log. Re-picking the active entry isn't a change.
        if (index != state.value.playback.activeVizIndex) {
            visualizationLabels.getOrNull(index)?.let(analytics::logSelectViz)
        }
        _state.update { it.copy(playback = it.playback.copy(activeVizIndex = index)) }
        viewModelScope.launch {
            preferences.setActiveVizIndex(index)
        }
        if (state.value.playback.isCasting) {
            sendCastVisualizationIndex(index)
        }
    }

    fun openFullscreenVisualization() {
        _state.update(::stateAfterFullscreenVisualizationOpen)
    }

    fun closeFullscreenVisualization() {
        _state.update(::stateAfterFullscreenVisualizationClose)
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        val current = state.value.playback
        if (current.playMode == mode) {
            return
        }
        if (mode == PlaybackMode.Autocanonizer) {
            controller.setJukeboxAudioMode(JukeboxAudioMode.Off)
        }
        if (!current.isCasting) {
            val transportPlan = stopTransportForModeChange(
                controller = controller,
                previousMode = current.playMode,
                targetMode = mode,
                isRunning = current.isRunning || current.isPaused,
                onStopped = {
                    playbackCoordinator.stopListenTimer()
                    playbackCoordinator.updateListenTimeDisplay()
                }
            )
            if (transportPlan.clearAutocanonizerAudio) {
                controller.autocanonizer.clearSyncedAudio()
            }
        }
        lastCowbellBeatsPlayed = -1
        playbackCoordinator.applyPlaybackMode(mode)
        _state.update {
            it.copy(
                playback = playbackStateAfterModeChange(
                    playback = it.playback,
                    preserveTransportState = current.isCasting
                )
            )
        }
        if (current.isCasting) {
            syncCastNotification()
        } else {
            syncPlaybackServiceSession()
        }
    }

    fun setCanonizerFinishOutSong(enabled: Boolean) {
        controller.autocanonizer.setFinishOutSong(enabled)
        _state.update {
            it.copy(
                playback = it.playback.copy(
                    canonizerFinishOutSong = enabled
                )
            )
        }
        viewModelScope.launch {
            preferences.setCanonizerFinishOutSong(enabled)
        }
    }

    fun buildShareUrl(): String? {
        val url = listenLinkCoordinator.buildShareUrl() ?: return null
        // Web logs share on clipboard copy; the Android equivalent is handing
        // the link to the share sheet, which the sole caller does with this url.
        state.value.playback.shareTrackIdOrNull()?.let(analytics::logShare)
        return url
    }

    fun applyTuning(
        threshold: Int,
        minProb: Double,
        maxProb: Double,
        ramp: Double,
        highlightAnchorBranch: Boolean,
        justBackwards: Boolean,
        minJumpDistancePercent: Int,
        removeSequentialBranches: Boolean,
        audioModeWireValue: String? = null,
        audioModeIntensity: Int? = null
    ) {
        viewModelScope.launch {
            val currentPlayback = state.value.playback
            val currentTuning = state.value.tuning
            val requestedAudioModeWireValue = audioModeWireValue
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: if (currentPlayback.isCasting) {
                    currentPlayback.castAudioModeWireValue
                } else {
                    currentPlayback.jukeboxAudioMode.wireValue
                }
            val requestedAudioMode = when (currentPlayback.playMode) {
                PlaybackMode.Jukebox -> JukeboxAudioMode.fromWireValue(requestedAudioModeWireValue)
                    ?: currentPlayback.jukeboxAudioMode
                PlaybackMode.Autocanonizer -> JukeboxAudioMode.Off
            }
            val requestedIntensity = if (requestedAudioMode.supportsIntensity) {
                AudioModeIntensity.clamp(
                    audioModeIntensity
                        ?: if (currentPlayback.isCasting) {
                            currentPlayback.castAudioModeIntensity
                        } else {
                            currentPlayback.jukeboxAudioModeIntensity
                        }
                )
            } else {
                AudioModeIntensity.DEFAULT
            }
            // While casting, the receiver owns the audio mode and the local engine sits idle,
            // so the comparison base is the cast state the receiver last reported. Matching
            // buildCastTuningUpdate's own change test keeps "we sent it" and "we logged it"
            // from ever disagreeing.
            val audioModeChanged = currentPlayback.playMode == PlaybackMode.Jukebox && (
                if (currentPlayback.isCasting) {
                    currentPlayback.castAudioModeWireValue.trim() !=
                        requestedAudioModeWireValue.trim()
                } else {
                    currentPlayback.jukeboxAudioMode != requestedAudioMode
                }
                )
            val currentAudioModeIntensity = if (currentPlayback.isCasting) {
                currentPlayback.castAudioModeIntensity
            } else {
                currentPlayback.jukeboxAudioModeIntensity
            }
            val audioSettingsChanged = audioModeChanged ||
                (
                    currentPlayback.playMode == PlaybackMode.Jukebox &&
                        requestedAudioMode.supportsIntensity &&
                        currentAudioModeIntensity != requestedIntensity
                    )
            if (audioSettingsChanged && !currentPlayback.isCasting) {
                lastCowbellBeatsPlayed = -1
                controller.setJukeboxAudioMode(requestedAudioMode, requestedIntensity)
            }
            tuningCoordinator.applyTuning(
                threshold = threshold,
                minProb = minProb,
                maxProb = maxProb,
                ramp = ramp,
                highlightAnchorBranch = highlightAnchorBranch,
                justBackwards = justBackwards,
                minJumpDistancePercent = minJumpDistancePercent,
                removeSequentialBranches = removeSequentialBranches,
                audioMode = requestedAudioMode,
                audioModeWireValue = requestedAudioModeWireValue,
                audioModeIntensity = requestedIntensity
            )
            // Logged after the apply so a failed apply reports nothing. Casting reports the
            // same events: the phone is the only controller, and the cast status sync keeps
            // state.tuning matching the receiver, so the diff below stays authoritative.
            logTuningAnalytics(
                audioSettingsChanged = audioSettingsChanged,
                audioModeWireValue = requestedAudioModeWireValue,
                audioModeIntensity = requestedIntensity,
                previousTuning = currentTuning,
                nextTuning = currentTuning.withAppliedTuning(
                    threshold = threshold,
                    minProb = minProb,
                    maxProb = maxProb,
                    ramp = ramp,
                    highlightAnchorBranch = highlightAnchorBranch,
                    justBackwards = justBackwards,
                    minJumpDistancePercent = minJumpDistancePercent,
                    removeSequentialBranches = removeSequentialBranches,
                    randomBranchDeltaPercentScale = RANDOM_BRANCH_DELTA_PERCENT_SCALE
                )
            )
            if (audioSettingsChanged && !currentPlayback.isCasting &&
                (currentPlayback.isRunning || currentPlayback.isPaused)
            ) {
                engine.syncToPlaybackPosition()
            }
            if (audioSettingsChanged && !currentPlayback.isCasting) {
                _state.update {
                    it.copy(
                        playback = it.playback.copy(
                            jukeboxAudioMode = requestedAudioMode,
                            jukeboxAudioModeIntensity = requestedIntensity,
                            playTitle = buildPlaybackTitle(
                                title = it.playback.trackTitle,
                                artist = it.playback.trackArtist,
                                playMode = it.playback.playMode,
                                audioMode = requestedAudioMode
                            )
                        )
                    )
                }
                syncPlaybackServiceSession()
            }
        }
    }

    // audioSettingsChanged is already the web app's audio_mode firing rule: the mode changed,
    // or the intensity changed on a mode that has the slider.
    private fun logTuningAnalytics(
        audioSettingsChanged: Boolean,
        audioModeWireValue: String,
        audioModeIntensity: Int,
        previousTuning: TuningState,
        nextTuning: TuningState
    ) {
        if (audioSettingsChanged) {
            // The wire value actually applied is authoritative: while casting the picker
            // offers the receiver's own mode list, which can name modes the local engine
            // has no enum for.
            analytics.logAudioMode(
                audioMode = audioModeWireValue,
                intensity = analyticsAudioModeIntensity(audioModeWireValue, audioModeIntensity)
            )
        }
        analyticsChangedTuneControls(previousTuning, nextTuning).forEach(analytics::logTune)
    }

    fun resetBranchTuningDefaults() {
        viewModelScope.launch {
            // No analytics here: the web app fires `tune` only from the apply path, so a
            // reset that changes six controls deliberately reports nothing.
            tuningCoordinator.resetBranchTuningDefaults()
        }
    }

    fun resetAudioModeDefaults() {
        viewModelScope.launch {
            val currentPlayback = state.value.playback
            if (currentPlayback.isCasting) {
                // Reuses the reset builder's already-off test so a no-op reset reports
                // nothing, matching the narrower guard on the local branch below.
                if (buildCastAudioModeResetParams(currentPlayback.castAudioModeWireValue) != null) {
                    analytics.logAudioMode(JukeboxAudioMode.Off.wireValue, intensity = null)
                }
                tuningCoordinator.resetCastAudioModeDefaults()
                return@launch
            }
            val resetAudioMode = currentPlayback.playMode == PlaybackMode.Jukebox &&
                (
                    currentPlayback.jukeboxAudioMode != JukeboxAudioMode.Off ||
                        currentPlayback.jukeboxAudioModeIntensity != AudioModeIntensity.DEFAULT
                    )
            if (!resetAudioMode) {
                return@launch
            }
            // Narrower than the resetAudioMode guard on purpose: that one also covers a
            // stale non-default intensity on an already-off mode, where nothing is turned off.
            if (currentPlayback.jukeboxAudioMode != JukeboxAudioMode.Off) {
                analytics.logAudioMode(JukeboxAudioMode.Off.wireValue, intensity = null)
            }
            lastCowbellBeatsPlayed = -1
            controller.setJukeboxAudioMode(JukeboxAudioMode.Off)
            if (currentPlayback.isRunning || currentPlayback.isPaused) {
                engine.syncToPlaybackPosition()
            }
            _state.update {
                it.copy(
                    playback = it.playback.copy(
                        jukeboxAudioMode = JukeboxAudioMode.Off,
                        jukeboxAudioModeIntensity = AudioModeIntensity.DEFAULT,
                        playTitle = buildPlaybackTitle(
                            title = it.playback.trackTitle,
                            artist = it.playback.trackArtist,
                            playMode = it.playback.playMode,
                            audioMode = JukeboxAudioMode.Off
                        )
                    )
                )
            }
            syncPlaybackServiceSession()
            // The auto-saved tuning bundles am/ai; re-persist after the state update so a
            // reload doesn't restore the just-reset audio mode.
            tuningCoordinator.persistCurrentLocalTuning()
        }
    }

    fun handleDeepLink(uri: Uri?) {
        pendingDeepLinkUriString = uri?.toString()
        consumePendingDeepLinkIfReady()
    }

    /** Entry point for text sent to the app by the share sheet. */
    fun handleSharedText(sharedText: String?, sharedSubject: String? = null) {
        if (sharedText.isNullOrBlank() && sharedSubject.isNullOrBlank()) return
        pendingShare = PendingShare.Text(sharedText, sharedSubject)
        consumePendingShareIfReady()
    }

    /** Entry point for an audio file sent to the app by the share sheet. */
    fun handleSharedAudio(uri: Uri) {
        pendingShare = PendingShare.Audio(uri)
        consumePendingShareIfReady()
    }

    fun refreshCacheSize() {
        playbackCoordinator.refreshCacheSize()
    }

    fun clearCache() {
        appLifecycleCoordinator.clearCache()
    }

    fun openListenTab() {
        applyActiveTab(TabId.Play, recordHistory = true)
    }

    fun dismissVersionUpdatePrompt() {
        _state.update { it.copy(versionUpdatePrompt = null) }
    }

    fun showWhatsNewFromSettings() {
        _state.update {
            it.copy(
                whatsNewPrompt = buildWhatsNewPrompt(
                    versionCode = BuildConfig.VERSION_CODE,
                    versionName = BuildConfig.VERSION_NAME
                )
            )
        }
    }

    fun dismissWhatsNew() {
        val dismissedVersionCode = state.value.whatsNewPrompt?.versionCode ?: BuildConfig.VERSION_CODE
        _state.update {
            stateAfterWhatsNewDismissed(
                state = it,
                dismissedVersionCode = dismissedVersionCode
            )
        }
        viewModelScope.launch {
            preferences.setWhatsNewVersionCode(dismissedVersionCode)
        }
    }

    private fun recoverLoadingStateOnForeground() {
        val current = state.value
        val playback = current.playback
        val isTrackLoadInProgress =
            playback.analysisInFlight || playback.analysisCalculating || playback.audioLoading
        if (!isTrackLoadInProgress) {
            return
        }
        when (current.appMode) {
            AppMode.Server -> recoverServerLoadingOnForeground(current, playback)
            AppMode.Local -> recoverLocalLoadingOnForeground()
            null -> Unit
        }
    }

    private fun consumePendingDeepLinkIfReady() {
        val pending = pendingDeepLinkUriString ?: return
        if (state.value.baseUrl.isBlank()) {
            return
        }
        pendingDeepLinkUriString = null
        listenLinkCoordinator.handleDeepLink(pending)
    }

    /**
     * Act on a shared track once the app mode, server URL, and server config have settled. A share
     * can land before any of the preference flows have emitted, so anything unsettled holds the
     * share rather than rejecting it with the wrong reason.
     */
    private fun consumePendingShareIfReady() {
        val pending = pendingShare ?: return
        val current = state.value
        val readiness = resolveShareReadiness(
            showAppModeGate = current.showAppModeGate,
            appMode = current.appMode,
            baseUrlLoaded = baseUrlLoaded,
            baseUrl = current.baseUrl,
            serverConfigPending = serverConfigState == ServerConfigState.Pending
        )
        when (readiness) {
            ShareReadiness.Wait -> return
            ShareReadiness.NotServerMode -> {
                pendingShare = null
                viewModelScope.launch { showToast(SHARE_NEEDS_SERVER_MODE_MESSAGE) }
                return
            }
            ShareReadiness.NoServer -> {
                pendingShare = null
                viewModelScope.launch { showToast(SHARE_NO_SERVER_MESSAGE) }
                return
            }
            ShareReadiness.Ready -> Unit
        }
        pendingShare = null
        when (pending) {
            is PendingShare.Text -> dispatchSharedText(pending)
            is PendingShare.Audio -> dispatchSharedAudio(pending.uri)
        }
    }

    private fun dispatchSharedText(pending: PendingShare.Text) {
        val candidates = sharedSourceCandidates(pending.text, pending.subject)
        // A listen link carries a track id rather than a user-supplied source, so it takes the
        // deep-link path and is not subject to the server's user-URL setting.
        if (candidates.any { listenLinkCoordinator.handleDeepLink(it) }) {
            return
        }
        val sourceUrl = candidates.firstNotNullOfOrNull { normalizeSupportedSourceUrl(it) }
        if (sourceUrl == null) {
            viewModelScope.launch { showToast(SHARE_UNSUPPORTED_MESSAGE) }
            return
        }
        val bypassConfigGate = shareBypassesConfigGate(state.value.allowUserUrl) ?: run {
            viewModelScope.launch { showToast(SHARE_URL_NOT_ALLOWED_MESSAGE) }
            return
        }
        submitTrackUrl(sourceUrl.url, bypassConfigGate)
    }

    private fun dispatchSharedAudio(uri: Uri) {
        val bypassConfigGate = shareBypassesConfigGate(state.value.allowUserUpload) ?: run {
            viewModelScope.launch { showToast(SHARE_UPLOAD_NOT_ALLOWED_MESSAGE) }
            return
        }
        uploadTrackFile(uri, displayName = null, bypassConfigGate = bypassConfigGate)
    }

    /**
     * Whether a share may skip the local user-source gate, or null when the server has refused it.
     * With no config to consult the request goes out anyway so the server itself answers: its 403
     * carries the same message, and an unreachable server reports a connection failure rather than
     * a permission it never expressed.
     */
    private fun shareBypassesConfigGate(allowed: Boolean): Boolean? = when {
        allowed -> false
        serverConfigState == ServerConfigState.Missing -> true
        else -> null
    }

    private fun recoverServerLoadingOnForeground(current: UiState, playback: PlaybackState) {
        val baseUrl = current.baseUrl.trim()
        val jobId = playback.lastJobId ?: return
        if (baseUrl.isBlank()) {
            return
        }
        if (remoteTrackLoadCoordinator.isRunning() || playbackCoordinator.hasActiveServerLoadWork()) {
            return
        }
        remoteTrackLoadCoordinator.launch {
            playbackCoordinator.setAnalysisQueued(
                playback.analysisProgress,
                playback.analysisMessage ?: "Resuming load..."
            )
            try {
                val response = serverGateway.getAnalysis(baseUrl, jobId)
                val handled = remoteTrackLoadCoordinator.loadOrPoll(response, fallbackJobId = jobId)
                if (!handled) {
                    playbackCoordinator.setAnalysisError("Loading failed.")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: IOException) {
                AppLog.warn(TAG, "Failed to recover server load state", error)
                playbackCoordinator.setAnalysisError("Loading failed.")
            } catch (error: IllegalArgumentException) {
                AppLog.warn(TAG, "Failed to recover server load state", error)
                playbackCoordinator.setAnalysisError("Loading failed.")
            } catch (error: IllegalStateException) {
                AppLog.warn(TAG, "Failed to recover server load state", error)
                playbackCoordinator.setAnalysisError("Loading failed.")
            }
        }
    }

    private fun recoverLocalLoadingOnForeground() {
        if (localAnalysisCoordinator.isAnalysisRunning()) {
            return
        }
        playbackCoordinator.setAnalysisError("Analysis interrupted. Please retry.")
    }

    private fun checkForAppUpdateOnce() {
        appLifecycleCoordinator.checkForAppUpdateOnce()
    }

    private fun maybeShowAutomaticWhatsNew() {
        val prompt = buildWhatsNewPrompt(
            versionCode = BuildConfig.VERSION_CODE,
            versionName = BuildConfig.VERSION_NAME
        )
        _state.update { current ->
            if (
                shouldShowAutomaticWhatsNew(
                    showAppModeGate = current.showAppModeGate,
                    whatsNewVersionCodeLoaded = current.whatsNewVersionCodeLoaded,
                    lastShownVersionCode = current.lastShownWhatsNewVersionCode,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    currentPrompt = current.whatsNewPrompt
                )
            ) {
                current.copy(whatsNewPrompt = prompt)
            } else {
                current
            }
        }
    }

    private fun syncPlaybackServiceSession() {
        playbackCoordinator.syncPlaybackServiceSession(PlaybackServiceSyncReason.StateChanged)
    }

    private fun hardStopPlaybackServiceSession() {
        playbackCoordinator.syncPlaybackServiceSession(PlaybackServiceSyncReason.HardStop)
    }

    private fun blockPlaybackChangeWhileLoading(showToast: Boolean = true): Boolean {
        if (!shouldBlockPlaybackChangeWhileLoading(state.value.playback)) {
            return false
        }
        if (showToast) {
            viewModelScope.launch { showToast(LOADING_LOCK_MESSAGE) }
        }
        return true
    }

    private fun syncCastNotification() {
        syncPlaybackServiceSession()
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(getApplication(), message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
        private const val CAST_MAX_TRACK_DURATION_MINUTES = 7.0
        private const val GITHUB_REPO_OWNER = "creightonlinza"
        private const val GITHUB_REPO_NAME = "forever-jukebox-android"
        private const val LOADING_LOCK_MESSAGE = "Please wait for the current track to finish loading."
        private const val SERVER_LOAD_NETWORK_MAX_ATTEMPTS = 3
        private const val SERVER_LOAD_NETWORK_BASE_RETRY_DELAY_MS = 2000L
        private const val RANDOM_BRANCH_DELTA_PERCENT_SCALE = 500.0
        private const val SHARE_NEEDS_SERVER_MODE_MESSAGE = "Switch to server mode to add tracks."
        private const val SHARE_NO_SERVER_MESSAGE = "Set up a Forever Jukebox server first."
        private const val SHARE_URL_NOT_ALLOWED_MESSAGE =
            "This server doesn't allow adding tracks by link."
        private const val SHARE_UPLOAD_NOT_ALLOWED_MESSAGE = "This server doesn't allow uploads."
        private const val SHARE_UNSUPPORTED_MESSAGE =
            "Share a YouTube, SoundCloud, or Bandcamp link."
    }
}

// DNS and connect failures on Android frequently mean the OS is restricting the
// app's network access (doze, app standby, wifi/cellular handoff) rather than a
// server or connectivity outage, so they are worth retrying before surfacing.
internal fun isRetryableNetworkError(error: IOException): Boolean =
    error is UnknownHostException || error is ConnectException

/** A track handed over by the share sheet, held until the app state can act on it. */
private sealed interface PendingShare {
    data class Text(val text: String?, val subject: String?) : PendingShare

    data class Audio(val uri: Uri) : PendingShare
}

/**
 * How much is known about the server's config. An `allowUserUrl` of false cannot by itself
 * distinguish a server that refuses user sources from one that has not been asked yet, and a
 * shared track needs that distinction to report the right reason.
 */
private enum class ServerConfigState {
    Pending,

    /** The config fetch finished without producing a config; let the server answer directly. */
    Missing,
    Loaded
}
