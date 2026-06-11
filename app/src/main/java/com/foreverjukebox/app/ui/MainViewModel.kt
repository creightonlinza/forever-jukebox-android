package com.foreverjukebox.app.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.foreverjukebox.app.BuildConfig
import com.foreverjukebox.app.data.ApiClient
import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.data.AppPreferences
import com.foreverjukebox.app.data.AnalysisResponse
import com.foreverjukebox.app.data.AnalysisStartResponse
import com.foreverjukebox.app.data.FavoriteSourceType
import com.foreverjukebox.app.data.FavoriteTrack
import com.foreverjukebox.app.data.HttpStatusException
import com.foreverjukebox.app.data.SOURCE_PROVIDER_YOUTUBE
import com.foreverjukebox.app.data.SavedPlaylistTrack
import com.foreverjukebox.app.data.SpotifySearchItem
import com.foreverjukebox.app.data.ThemeMode
import com.foreverjukebox.app.data.YoutubeSearchItem
import com.foreverjukebox.app.data.buildJobTrackId
import com.foreverjukebox.app.data.canonicalTrackId
import com.foreverjukebox.app.data.parseTrackId
import com.foreverjukebox.app.data.sanitizeMaxFavorites
import com.foreverjukebox.app.data.trackIdFromAnalysis
import com.foreverjukebox.app.data.trackIdFromTopSong
import com.foreverjukebox.app.data.youtubeTrackIdFromTopSong
import com.foreverjukebox.app.data.sourceProviderFromRaw
import com.foreverjukebox.app.audio.LoadingAudioFeedbackController
import com.foreverjukebox.app.audio.SoundPoolLoadingAudioFeedbackPlayer
import com.foreverjukebox.app.local.LocalAnalysisService
import com.foreverjukebox.app.playback.ForegroundPlaybackService
import com.foreverjukebox.app.playback.PlaybackControllerHolder
import com.foreverjukebox.app.visualization.JumpLine
import com.foreverjukebox.app.visualization.defaultVisualizationIndex
import com.foreverjukebox.app.visualization.visualizationCount
import com.foreverjukebox.app.cast.CastAppIdResolver
import java.io.IOException
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

internal suspend fun tryQueueYoutubeAnalysisForCast(
    baseUrl: String,
    youtubeId: String,
    title: String?,
    artist: String?,
    startAnalysis: suspend (baseUrl: String, youtubeId: String, title: String?, artist: String?) -> AnalysisStartResponse
): String? {
    val normalizedBaseUrl = baseUrl.trim()
    if (normalizedBaseUrl.isBlank()) {
        return null
    }
    return runCatching {
        startAnalysis(normalizedBaseUrl, youtubeId, title, artist)
            .id
            ?.trim()
            ?.ifBlank { null }
    }.getOrNull()
}

internal fun resetSearchStateAfterTrackSelection(search: SearchState): SearchState {
    return search.copy(
        query = "",
        spotifyResults = emptyList(),
        youtubeMatches = emptyList(),
        youtubeLoading = false,
        pendingTrackName = null,
        pendingTrackArtist = null
    )
}

internal data class YoutubeTrackSelection(
    val youtubeId: String,
    val title: String?,
    val artist: String?
)

internal data class TrackMetadata(
    val title: String? = null,
    val artist: String? = null
)

internal fun resolveYoutubeTrackSelection(
    item: YoutubeSearchItem,
    search: SearchState
): YoutubeTrackSelection? {
    val youtubeId = item.id.takeIfNotBlank() ?: return null
    val pendingTitle = search.pendingTrackName.takeIfNotBlank()
    val pendingArtist = search.pendingTrackArtist.takeIfNotBlank()
    val hasPendingTrackMeta = pendingTitle != null || pendingArtist != null
    val title = if (hasPendingTrackMeta) {
        pendingTitle
    } else {
        item.title.takeIfNotBlank()
    }
    val artist = if (hasPendingTrackMeta) pendingArtist else null
    return YoutubeTrackSelection(
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
            canonicalTrackId(trackIdFromTopSong(item)) == canonicalTarget ||
                canonicalTrackId(youtubeTrackIdFromTopSong(item)) == canonicalTarget
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

internal fun shouldReuseLookupJob(response: AnalysisResponse?): Boolean {
    val jobId = response?.id
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
    return matched.id?.trim().orEmpty().ifBlank { null }
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
        addCanonical(buildJobTrackId(fallback))
    }
    val lastJobId = playback.lastJobId?.trim().orEmpty()
    if (lastJobId.isNotBlank()) {
        addCanonical(buildJobTrackId(lastJobId))
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val api = ApiClient()
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
    private var savedPlaylistTracks: List<SavedPlaylistTrack> = emptyList()
    private var lastCowbellBeatsPlayed = -1
    private val tabHistory = ArrayDeque<TabId>()
    private val castController = CastController(getApplication())
    private val castPlaybackCoordinator = CastPlaybackCoordinator(
        castController = castController,
        getState = { state.value },
        updateState = { updater -> _state.update(updater) },
        onCastUnavailable = ::notifyCastUnavailable,
        onSyncCastNotification = ::syncCastNotification,
        castTrackLengthLimitErrorMessage = ::castTrackLengthLimitErrorMessage
    )
    private val searchCoordinator = SearchCoordinator(
        scope = viewModelScope,
        api = api,
        getState = { state.value },
        updateSearchState = ::updateSearchState,
        setSearchQuery = ::setSearchQuery,
        logError = { message, error -> Log.e(TAG, message, error) }
    )
    private val favoritesController = FavoritesController(
        scope = viewModelScope,
        api = api,
        preferences = preferences,
        getState = { state.value },
        updateState = { updater -> _state.update(updater) },
        showToast = ::showToast
    )
    private val playbackCoordinator = PlaybackCoordinator(
        application = getApplication(),
        scope = viewModelScope,
        api = api,
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
    private val serverTrackLoadCoordinator = ServerTrackLoadCoordinator(
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
        getState = { state.value },
        updateState = { updater ->
            _state.update(updater)
            hydrateSavedPlaylistIfInactive()
        },
        applyActiveTab = ::applyActiveTab,
        logError = { message, error -> Log.e(TAG, message, error) }
    )
    private val tuningCoordinator = TuningCoordinator(
        engine = engine,
        defaultConfig = defaultConfig,
        preferences = preferences,
        playbackCoordinator = playbackCoordinator,
        castPlaybackCoordinator = castPlaybackCoordinator,
        getState = { state.value },
        updateState = { updater -> _state.update(updater) },
        randomBranchDeltaPercentScale = RANDOM_BRANCH_DELTA_PERCENT_SCALE
    )
    private val castSessionCoordinator = CastSessionCoordinator(
        controller = controller,
        castPlaybackCoordinator = castPlaybackCoordinator,
        playbackCoordinator = playbackCoordinator,
        serverTrackLoadCoordinator = serverTrackLoadCoordinator,
        getState = { state.value },
        updateState = { updater -> _state.update(updater) },
        applyActiveTab = ::applyActiveTab,
        syncCastNotification = ::syncCastNotification
    )
    private val listenLinkCoordinator = ListenLinkCoordinator(
        buildTuningParamsString = playbackCoordinator::buildTuningParamsString,
        getState = { state.value },
        setPlaybackMode = ::setPlaybackMode,
        loadTrackById = ::loadTrackById
    )
    private val appLifecycleCoordinator = AppLifecycleCoordinator(
        scope = viewModelScope,
        api = api,
        controller = controller,
        playbackCoordinator = playbackCoordinator,
        localAnalysisCoordinator = localAnalysisCoordinator,
        serverTrackLoadCoordinator = serverTrackLoadCoordinator,
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
                    retryFailedLoad()
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
        viewModelScope.launch {
            preferences.appMode.collect { mode ->
                _state.update { current ->
                    val resolvedAppId = CastAppIdResolver.resolve(getApplication(), current.baseUrl)
                    val nextActiveTab = coerceTabForMode(mode, current.activeTab)
                    current.copy(
                        appMode = mode,
                        activeTab = nextActiveTab,
                        showAppModeGate = shouldShowAppModeGate(mode),
                        showBaseUrlPrompt = shouldShowBaseUrlPrompt(mode, current.baseUrl),
                        castEnabled = mode == AppMode.Server && !resolvedAppId.isNullOrBlank()
                    )
                }
                hydrateSavedPlaylistIfInactive()
                maybeRefreshServerDataForCurrentState()
                maybeShowAutomaticWhatsNew()
            }
        }
        viewModelScope.launch {
            preferences.baseUrl.collect { url ->
                val resolvedAppId = CastAppIdResolver.resolve(getApplication(), url)
                _state.update { current ->
                    val mode = current.appMode
                    current.copy(
                        baseUrl = url.orEmpty(),
                        showBaseUrlPrompt = shouldShowBaseUrlPrompt(mode, url.orEmpty()),
                        castEnabled = mode == AppMode.Server && !resolvedAppId.isNullOrBlank()
                    )
                }
                maybeRefreshServerDataForCurrentState()
                consumePendingDeepLinkIfReady()
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
                            maxTrackLengthMinutes = config.maxTrackLength
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
                            maxTrackLengthMinutes = null
                        )
                    }
                    if (normalizedFavorites != currentFavorites) {
                        favoritesController.updateFavorites(normalizedFavorites, sync = false)
                    }
                }
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
            val jumpLine = if (engineState.lastJumped && lastJumpFrom != null) {
                JumpLine(lastJumpFrom, currentBeatIndex, SystemClock.elapsedRealtime())
            } else {
                null
            }
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
        controller.autocanonizer.setOnBeat { index, _, forcedOtherIndex ->
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
            val current = state.value
            if (shouldAdvancePlaylistOnAutocanonizerEnd(current)) {
                selectPlaylistTrack(
                    index = current.playlist.currentIndex + 1,
                    playAfterLoaded = true
                )
                return@setOnEnded
            }
            _state.update {
                it.copy(
                    playback = it.playback.copy(
                        isRunning = false,
                        isPaused = false
                    )
                )
            }
            syncPlaybackServiceSession()
        }

        playbackCoordinator.restorePlaybackState()
        localAnalysisCoordinator.refreshLocalCachedTracks()
        checkForAppUpdateOnce()
    }

    override fun onCleared() {
        serverTrackLoadCoordinator.cancel()
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
                playback = it.playback.copy(
                    isRunning = false,
                    isPaused = false,
                    canonizerOtherIndex = null
                )
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
            playbackCoordinator.startListenTimer()
        } else {
            playbackCoordinator.stopListenTimer()
        }
        playbackCoordinator.updateListenTimeDisplay()
        _state.update {
            it.copy(
                playback = it.playback.copy(
                    isRunning = isRunning,
                    isPaused = isPaused,
                    canonizerOtherIndex = if (isRunning || isPaused) {
                        it.playback.canonizerOtherIndex
                    } else {
                        null
                    }
                )
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
            val resolvedAppId = CastAppIdResolver.resolve(getApplication(), trimmedUrl)
            _state.update {
                it.copy(
                    baseUrl = trimmedUrl,
                    showBaseUrlPrompt = shouldShowBaseUrlPrompt(it.appMode, trimmedUrl),
                    castEnabled = mode == AppMode.Server && !resolvedAppId.isNullOrBlank()
                )
            }
        }
        consumePendingDeepLinkIfReady()
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
            ?.let(::buildJobTrackId)
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
            it.copy(activeTab = resolvedTab, topSongsTab = nextTopTab)
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
                runCatching { api.getAppConfig(baseUrl).also { preferences.setAppConfig(it) } }
            }
        }
        searchCoordinator.maybeRefreshForState(currentState)
        favoritesController.maybeHydrateFavoritesFromSync()
    }

    private fun resetRuntimeForModeChange(targetMode: AppMode) {
        serverTrackLoadCoordinator.cancel()
        cancelCastSelection()
        localAnalysisCoordinator.cancelLocalAnalysisInternal(showCancelledMessage = false)
        searchCoordinator.resetRuntimeState()
        appConfigLoaded = false
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
            val resolvedAppId = CastAppIdResolver.resolve(getApplication(), current.baseUrl)
            stateAfterModeChangeReset(
                current = current,
                targetMode = targetMode,
                castEnabled = targetMode == AppMode.Server && !resolvedAppId.isNullOrBlank()
            )
        }
    }

    private fun resetRuntimeForServerSwitch(nextBaseUrl: String) {
        serverTrackLoadCoordinator.cancel()
        cancelCastSelection()
        searchCoordinator.resetRuntimeState()
        favoritesController.resetRuntimeState()
        appConfigLoaded = false
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
            val resolvedAppId = CastAppIdResolver.resolve(getApplication(), nextBaseUrl)
            current.copy(
                baseUrl = nextBaseUrl,
                showBaseUrlPrompt = shouldShowBaseUrlPrompt(mode, nextBaseUrl),
                castEnabled = mode == AppMode.Server && !resolvedAppId.isNullOrBlank(),
                activeTab = TabId.Top,
                topSongsTab = TopSongsTab.TopSongs,
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
        tuningParams: String?
    ): PlaylistTrack? {
        val canonical = canonicalTrackId(trackId) ?: return null
        return PlaylistTrack(
            id = canonical,
            type = PlaylistTrackType.Server,
            title = title.takeIfNotBlank(),
            artist = artist.takeIfNotBlank(),
            tuningParams = tuningParams?.takeIf { it.isNotBlank() }
        )
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
            tuningParams = if (type == PlaylistTrackType.Server && playback.playMode == PlaybackMode.Jukebox) {
                playbackCoordinator.buildTuningParamsString()
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
            PlaylistTrackType.Server -> loadTrackByIdInternal(
                track.id,
                track.title,
                track.artist,
                track.tuningParams,
                playAfterLoaded
            )
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
            canonicalTrackId(currentTrackId) ?: return FavoriteToggleResult.NoTrack
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
                    tuningParams = if (playback.playMode == PlaybackMode.Jukebox) {
                        playbackCoordinator.buildTuningParamsString()
                    } else {
                        null
                    }
                )
                favoritesController.updateFavorites(
                    favorites + newFavorite,
                    fromListenToggle = syncFromListenToggle
                )
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
        searchCoordinator.runSpotifySearch(query)
    }

    fun selectServerPlaylistTrack(
        trackId: String,
        title: String? = null,
        artist: String? = null,
        tuningParams: String? = null
    ) {
        if (blockPlaybackChangeWhileLoading()) return
        clearInactiveSavedPlaylistBeforeOutsideSelection()
        val track = playlistTrackForServerTrack(trackId, title, artist, tuningParams) ?: return
        if (state.value.playlist.isActive()) {
            updatePlaylistState { it.replaceCurrentTrackWith(track) }
        }
        loadTrackById(track.id, track.title, track.artist, track.tuningParams)
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

    fun selectSpotifyTrack(item: SpotifySearchItem) {
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
        serverTrackLoadCoordinator.launch {
            if (artist.isNotBlank()) {
                try {
                    val response = retryTransientServerLoad {
                        api.getJobByTrack(baseUrl, name, artist)
                    }
                    if (shouldReuseLookupJob(response)) {
                        val jobId = response!!.id!!
                        val trackId = trackIdFromAnalysis(response) ?: buildJobTrackId(jobId)
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
                    Log.e(TAG, "Job lookup by track failed", error)
                    // Fall back to YouTube matches.
                } catch (error: IOException) {
                    Log.e(TAG, "Job lookup by track failed", error)
                    // Fall back to YouTube matches.
                } catch (error: IllegalArgumentException) {
                    Log.e(TAG, "Job lookup by track failed", error)
                    // Fall back to YouTube matches.
                } catch (error: IllegalStateException) {
                    Log.e(TAG, "Job lookup by track failed", error)
                    // Fall back to YouTube matches.
                }
            }
            fetchYoutubeMatches(name, artist, duration)
        }
    }

    fun selectYoutubeTrack(item: YoutubeSearchItem) {
        if (blockPlaybackChangeWhileLoading()) return
        clearInactiveSavedPlaylistBeforeOutsideSelection()
        val selection = resolveYoutubeTrackSelection(item, state.value.search) ?: return
        val duration = item.duration
        if (showTrackLengthLimitIfExceeded(duration)) {
            return
        }
        if (state.value.playlist.isActive()) {
            maybeSelectPlaylistTrack(
                playlistTrackForServerTrack(
                    selection.youtubeId,
                    selection.title,
                    selection.artist,
                    null
                )
            )
        }
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
                    val existing = retryTransientServerLoad {
                        api.getJobBySource(baseUrl, SOURCE_PROVIDER_YOUTUBE, youtubeId)
                    }
                    val resolvedJobId = existing?.id
                    if (!resolvedJobId.isNullOrBlank()) {
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
            val existing = retryTransientServerLoad {
                api.getJobBySource(baseUrl, SOURCE_PROVIDER_YOUTUBE, trackId)
            }
            if (existing != null) {
                return@launchServerTrackLoadWithCache serverTrackLoadCoordinator.loadOrPoll(existing)
            }
            val response = retryTransientServerLoad {
                api.startYoutubeAnalysis(
                    baseUrl,
                    trackId,
                    resolvedTitle,
                    resolvedArtist
                )
            }
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
            val responseId = response.id ?: return@launchServerTrackLoadWithCache false
            playbackCoordinator.setAnalysisQueued(response.progress?.roundToInt(), response.message)
            playbackCoordinator.setLastJobId(responseId)
            playbackCoordinator.startPoll(responseId)
            true
        }
    }

    fun loadTrackById(
        trackId: String,
        title: String? = null,
        artist: String? = null,
        tuningParams: String? = null
    ) {
        if (blockPlaybackChangeWhileLoading()) return
        clearInactiveSavedPlaylistBeforeOutsideSelection()
        loadTrackByIdInternal(
            trackId,
            title,
            artist,
            tuningParams,
            playAfterLoaded = false,
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
                        val existing = retryTransientServerLoad {
                            api.getJobBySource(baseUrl, provider, normalizedSourceId)
                        }
                        val resolvedJobId = existing?.id
                        if (!resolvedJobId.isNullOrBlank()) {
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
                    val existing = retryTransientServerLoad {
                        api.getJobBySource(baseUrl, provider, normalizedSourceId)
                    }
                    val resolvedJobId = existing?.id
                    if (resolvedJobId.isNullOrBlank()) {
                        showToast("Unable to queue this track for casting.")
                        return@launchCastSelection
                    }
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
                    Log.e(TAG, "Failed to resolve source for cast", error)
                    showToast("Unable to queue this track for casting.")
                } catch (error: IOException) {
                    Log.e(TAG, "Failed to resolve source for cast", error)
                    showToast("Unable to queue this track for casting.")
                } catch (error: IllegalArgumentException) {
                    Log.e(TAG, "Failed to resolve source for cast", error)
                    showToast("Unable to queue this track for casting.")
                } catch (error: IllegalStateException) {
                    Log.e(TAG, "Failed to resolve source for cast", error)
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
            val existing = retryTransientServerLoad {
                api.getJobBySource(baseUrl, provider, normalizedSourceId)
            }
            if (existing != null) {
                return@launchServerTrackLoadWithCache serverTrackLoadCoordinator.loadOrPoll(existing)
            }
            if (provider != SOURCE_PROVIDER_YOUTUBE) {
                return@launchServerTrackLoadWithCache false
            }
            val started = retryTransientServerLoad {
                api.startYoutubeAnalysis(
                    baseUrl = baseUrl,
                    youtubeId = normalizedSourceId,
                    title = resolvedTitle,
                    artist = resolvedArtist
                )
            }
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
            val responseId = started.id ?: return@launchServerTrackLoadWithCache false
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
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isBlank()) return
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
            val response = retryTransientServerLoad {
                api.getAnalysis(baseUrl, normalizedJobId)
            }
            serverTrackLoadCoordinator.loadOrPoll(response, fallbackJobId = normalizedJobId)
        }
    }

    private suspend fun loadExistingJob(
        jobId: String,
        trackId: String,
        response: AnalysisResponse,
        title: String? = null,
        artist: String? = null
    ) {
        val youtubeId = parseTrackId(trackId)?.youtubeId
        if (blockPlaybackChangeWhileLoading(showToast = false)) return
        if (state.value.playback.isCasting) {
            castPlaybackCoordinator.castTrackId(
                jobId = jobId,
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
                    lastJobId = jobId,
                    lastYouTubeId = youtubeId,
                    trackTitle = title,
                    trackArtist = artist
                )
            )
        }
        applyActiveTab(TabId.Play, recordHistory = true)
        playbackCoordinator.setAnalysisQueued(null, response.message)
        try {
            val handled = serverTrackLoadCoordinator.loadOrPoll(response, fallbackJobId = jobId)
            if (handled) {
                return
            }
            playbackCoordinator.setAnalysisError("Loading failed.")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: IOException) {
            Log.e(TAG, "Failed to load existing job", error)
            playbackCoordinator.setAnalysisError("Loading failed.")
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Failed to load existing job", error)
            playbackCoordinator.setAnalysisError("Loading failed.")
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Failed to load existing job", error)
            playbackCoordinator.setAnalysisError("Loading failed.")
        }
    }

    private fun prepareServerTrackLoad(
        tuningParams: String?,
        stateUpdate: (UiState) -> UiState
    ) {
        serverTrackLoadCoordinator.cancel()
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
        serverTrackLoadCoordinator.launch {
            if (cachedJobId != null && playbackCoordinator.tryLoadCachedTrack(cachedJobId)) {
                return@launch
            }
            playbackCoordinator.setAnalysisQueued(null, "Fetching audio...")
            try {
                val handled = request()
                if (!handled) {
                    playbackCoordinator.setAnalysisError("Loading failed.")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: HttpStatusException) {
                if (error.statusCode == 422) {
                    showServerTrackLengthLimitError()
                } else {
                    Log.e(TAG, failureLogMessage, error)
                    playbackCoordinator.setAnalysisError("Loading failed.")
                }
            } catch (error: IOException) {
                Log.e(TAG, failureLogMessage, error)
                playbackCoordinator.setAnalysisError("Loading failed.")
            } catch (error: IllegalArgumentException) {
                Log.e(TAG, failureLogMessage, error)
                playbackCoordinator.setAnalysisError("Loading failed.")
            } catch (error: IllegalStateException) {
                Log.e(TAG, failureLogMessage, error)
                playbackCoordinator.setAnalysisError("Loading failed.")
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

    fun togglePlayback() {
        val current = state.value.playback
        if (blockPlaybackChangeWhileLoading(showToast = false)) return
        if (shouldRetryFailedLoadFromTransport(state.value)) {
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
                val running = controller.playOrResumePlayback()
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
                        playbackCoordinator.startListenTimer()
                        syncPlaybackServiceSession()
                    }
                    paused -> {
                        playbackCoordinator.stopListenTimer()
                        syncPlaybackServiceSession()
                    }
                    else -> handleJukeboxPlaybackFailure()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: IllegalArgumentException) {
                Log.e(TAG, "Playback toggle failed", error)
                handleJukeboxPlaybackFailure()
            } catch (error: IllegalStateException) {
                Log.e(TAG, "Playback toggle failed", error)
                handleJukeboxPlaybackFailure()
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

    private fun handleJukeboxPlaybackFailure() {
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
                    playback = it.playback.copy(
                        isRunning = false,
                        isPaused = true,
                        canonizerOtherIndex = null
                    )
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
                        beatsPlayed = 0,
                        currentBeatIndex = -1,
                        canonizerOtherIndex = null,
                        canonizerTileColorOverrides = controller.autocanonizer.getTileColorOverrides(),
                        lastJumpFromIndex = null,
                        jumpLine = null,
                        isRunning = true,
                        isPaused = false
                    )
                )
            }
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
            val started = retryTransientServerLoad {
                api.startYoutubeAnalysis(
                    baseUrl = normalizedBaseUrl,
                    youtubeId = youtubeId,
                    title = title,
                    artist = artist
                )
            }
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
            val resolvedJobId = started.id?.trim()?.ifBlank { null }
            resolvedJobId
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
            showToast("Casting is not available for this API base URL.")
        }
    }

    fun retryFailedLoad() {
        if (blockPlaybackChangeWhileLoading()) return
        val baseUrl = state.value.baseUrl.trim()
        if (baseUrl.isBlank()) {
            viewModelScope.launch { showToast("Set a base URL first.") }
            return
        }
        val trackId = state.value.playback.shareTrackIdOrNull()
        if (trackId.isNullOrBlank()) {
            viewModelScope.launch { showToast("Nothing to retry.") }
            return
        }
        val title = state.value.playback.trackTitle
        val artist = state.value.playback.trackArtist
        serverTrackLoadCoordinator.cancel()
        playbackCoordinator.resetForNewTrack()
        loadTrackById(trackId, title, artist)
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
            api.deleteJob(baseUrl, jobId, adminKey)
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
                it.copy(activeTab = nextTab, topSongsTab = TopSongsTab.TopSongs)
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
        Log.e(TAG, "Failed to delete current job", error)
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
        if (blockPlaybackChangeWhileLoading()) return
        val track = state.value.playlist.tracks.getOrNull(index) ?: return
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

    fun setActiveVisualization(index: Int) {
        if (index !in 0 until visualizationCount) {
            return
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
        return listenLinkCoordinator.buildShareUrl()
    }

    fun applyTuning(
        threshold: Int,
        minProb: Double,
        maxProb: Double,
        ramp: Double,
        highlightAnchorBranch: Boolean,
        justBackwards: Boolean,
        justLongBranches: Boolean,
        removeSequentialBranches: Boolean,
        audioModeWireValue: String? = null
    ) {
        viewModelScope.launch {
            val currentPlayback = state.value.playback
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
            val audioModeChanged = currentPlayback.playMode == PlaybackMode.Jukebox &&
                currentPlayback.jukeboxAudioMode != requestedAudioMode
            if (audioModeChanged && !currentPlayback.isCasting) {
                lastCowbellBeatsPlayed = -1
                controller.setJukeboxAudioMode(requestedAudioMode)
            }
            tuningCoordinator.applyTuning(
                threshold = threshold,
                minProb = minProb,
                maxProb = maxProb,
                ramp = ramp,
                highlightAnchorBranch = highlightAnchorBranch,
                justBackwards = justBackwards,
                justLongBranches = justLongBranches,
                removeSequentialBranches = removeSequentialBranches,
                audioMode = requestedAudioMode,
                audioModeWireValue = requestedAudioModeWireValue
            )
            if (audioModeChanged && !currentPlayback.isCasting &&
                (currentPlayback.isRunning || currentPlayback.isPaused)
            ) {
                engine.syncToPlaybackPosition()
            }
            if (audioModeChanged && !currentPlayback.isCasting) {
                _state.update {
                    it.copy(
                        playback = it.playback.copy(
                            jukeboxAudioMode = requestedAudioMode,
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

    fun resetTuningDefaults() {
        viewModelScope.launch {
            val currentPlayback = state.value.playback
            val resetAudioMode = currentPlayback.playMode == PlaybackMode.Jukebox &&
                currentPlayback.jukeboxAudioMode != JukeboxAudioMode.Off
            if (resetAudioMode && !currentPlayback.isCasting) {
                lastCowbellBeatsPlayed = -1
                controller.setJukeboxAudioMode(JukeboxAudioMode.Off)
            }
            tuningCoordinator.resetTuningDefaults()
            if (resetAudioMode && !currentPlayback.isCasting &&
                (currentPlayback.isRunning || currentPlayback.isPaused)
            ) {
                engine.syncToPlaybackPosition()
            }
            if (resetAudioMode && !currentPlayback.isCasting) {
                _state.update {
                    it.copy(
                        playback = it.playback.copy(
                            jukeboxAudioMode = JukeboxAudioMode.Off,
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
            }
        }
    }

    fun handleDeepLink(uri: Uri?) {
        pendingDeepLinkUriString = uri?.toString()
        consumePendingDeepLinkIfReady()
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

    private fun recoverServerLoadingOnForeground(current: UiState, playback: PlaybackState) {
        val baseUrl = current.baseUrl.trim()
        val jobId = playback.lastJobId ?: return
        if (baseUrl.isBlank()) {
            return
        }
        if (serverTrackLoadCoordinator.isRunning() || playbackCoordinator.hasActiveServerLoadWork()) {
            return
        }
        serverTrackLoadCoordinator.launch {
            playbackCoordinator.setAnalysisQueued(
                playback.analysisProgress,
                playback.analysisMessage ?: "Resuming load..."
            )
            try {
                val response = retryTransientServerLoad {
                    api.getAnalysis(baseUrl, jobId)
                }
                val handled = serverTrackLoadCoordinator.loadOrPoll(response, fallbackJobId = jobId)
                if (!handled) {
                    playbackCoordinator.setAnalysisError("Loading failed.")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: IOException) {
                Log.e(TAG, "Failed to recover server load state", error)
                playbackCoordinator.setAnalysisError("Loading failed.")
            } catch (error: IllegalArgumentException) {
                Log.e(TAG, "Failed to recover server load state", error)
                playbackCoordinator.setAnalysisError("Loading failed.")
            } catch (error: IllegalStateException) {
                Log.e(TAG, "Failed to recover server load state", error)
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
        private const val RANDOM_BRANCH_DELTA_PERCENT_SCALE = 500.0
    }
}
