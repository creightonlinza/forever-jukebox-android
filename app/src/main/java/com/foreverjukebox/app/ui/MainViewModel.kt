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
import com.foreverjukebox.app.data.FavoriteSourceType
import com.foreverjukebox.app.data.FavoriteTrack
import com.foreverjukebox.app.data.SpotifySearchItem
import com.foreverjukebox.app.data.ThemeMode
import com.foreverjukebox.app.data.YoutubeSearchItem
import com.foreverjukebox.app.local.LocalAnalysisService
import com.foreverjukebox.app.playback.ForegroundPlaybackService
import com.foreverjukebox.app.playback.PlaybackControllerHolder
import com.foreverjukebox.app.visualization.JumpLine
import com.foreverjukebox.app.visualization.defaultVisualizationIndex
import com.foreverjukebox.app.visualization.visualizationCount
import com.foreverjukebox.app.cast.CastAppIdResolver
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
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
    startAnalysis: suspend (baseUrl: String, youtubeId: String, title: String?, artist: String?) -> Unit
): Boolean {
    val normalizedBaseUrl = baseUrl.trim()
    if (normalizedBaseUrl.isBlank()) {
        return false
    }
    return runCatching {
        startAnalysis(normalizedBaseUrl, youtubeId, title, artist)
    }.isSuccess
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

internal fun sleepTimerOptionForDurationMs(durationMs: Long?): SleepTimerOption {
    if (durationMs == null || durationMs <= 0L) {
        return SleepTimerOption.Off
    }
    return SleepTimerOption.entries.firstOrNull { option ->
        option.durationMs == durationMs
    } ?: SleepTimerOption.Off
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val api = ApiClient()
    private val controller = PlaybackControllerHolder.get(application)
    private val engine = controller.engine
    private val defaultConfig = engine.getConfig()
    private val json = Json { ignoreUnknownKeys = true }
    private val localAnalysisService = LocalAnalysisService.create(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var appConfigLoaded = false
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
        applyActiveTab = ::applyActiveTab
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
        updateState = { updater -> _state.update(updater) },
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
        application = getApplication(),
        scope = viewModelScope,
        controller = controller,
        castPlaybackCoordinator = castPlaybackCoordinator,
        playbackCoordinator = playbackCoordinator,
        serverTrackLoadCoordinator = serverTrackLoadCoordinator,
        getState = { state.value },
        updateState = { updater -> _state.update(updater) },
        applyActiveTab = ::applyActiveTab,
        notifyCastUnavailable = ::notifyCastUnavailable,
        setPlaybackMode = ::setPlaybackMode,
        syncCastNotification = ::syncCastNotification,
        showToast = ::showToast
    )
    private val listenLinkCoordinator = ListenLinkCoordinator(
        buildTuningParamsString = playbackCoordinator::buildTuningParamsString,
        getState = { state.value },
        setPlaybackMode = ::setPlaybackMode,
        loadTrackByYoutubeId = ::loadTrackByYoutubeId
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
    private val sleepTimerExpiredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != ForegroundPlaybackService.ACTION_SLEEP_TIMER_EXPIRED) {
                return
            }
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
                syncCastNotification(state.value.playback)
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            getApplication<Application>(),
            sleepTimerExpiredReceiver,
            IntentFilter(ForegroundPlaybackService.ACTION_SLEEP_TIMER_EXPIRED),
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
                maybeRefreshServerDataForCurrentState()
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
            }
        }
        viewModelScope.launch {
            preferences.favorites.collect { favorites ->
                val normalized = favoritesController.normalizeFavorites(favorites).take(MAX_FAVORITES)
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
            preferences.appConfig.collect { config ->
                if (config != null) {
                    _state.update {
                        it.copy(
                            allowFavoritesSync = config.allowFavoritesSync,
                            maxTrackLengthMinutes = config.maxTrackLength
                        )
                    }
                    favoritesController.maybeHydrateFavoritesFromSync()
                } else {
                    _state.update { it.copy(maxTrackLengthMinutes = null) }
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
            if (state.value.playback.playMode == PlaybackMode.Autocanonizer) {
                return@onUpdate
            }
            val currentBeatIndex = engineState.currentBeatIndex
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
            _state.update {
                it.copy(
                    playback = it.playback.copy(
                        isRunning = false,
                        isPaused = false
                    )
                )
            }
            ForegroundPlaybackService.stop(getApplication())
        }

        playbackCoordinator.restorePlaybackState()
        localAnalysisCoordinator.refreshLocalCachedTracks()
        checkForAppUpdateOnce()
    }

    override fun onCleared() {
        serverTrackLoadCoordinator.cancel()
        localAnalysisCoordinator.cancelLocalAnalysisInternal(showCancelledMessage = false)
        runCatching {
            getApplication<Application>().unregisterReceiver(sleepTimerExpiredReceiver)
        }
        super.onCleared()
        playbackCoordinator.onCleared()
        controller.release()
    }

    fun setBaseUrl(url: String) {
        val trimmedUrl = url.trim()
        _state.update {
            it.copy(
                baseUrl = trimmedUrl,
                showBaseUrlPrompt = shouldShowBaseUrlPrompt(it.appMode, trimmedUrl)
            )
        }
        viewModelScope.launch {
            preferences.setBaseUrl(trimmedUrl)
            if (state.value.appMode == AppMode.Server) {
                delay(100)
                refreshTopSongs()
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferences.setThemeMode(mode)
        }
    }

    fun startLocalAnalysis(uri: Uri, displayName: String?) {
        localAnalysisCoordinator.startLocalAnalysis(uri, displayName)
    }

    fun openCachedLocalTrack(localId: String) {
        localAnalysisCoordinator.openCachedLocalTrack(localId)
    }

    fun deleteCachedLocalTrack(localId: String) {
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

    fun canNavigateBack(): Boolean = tabHistory.isNotEmpty()

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
        ForegroundPlaybackService.stop(getApplication())

        _state.update { current ->
            val resolvedAppId = CastAppIdResolver.resolve(getApplication(), current.baseUrl)
            stateAfterModeChangeReset(
                current = current,
                targetMode = targetMode,
                castEnabled = targetMode == AppMode.Server && !resolvedAppId.isNullOrBlank()
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

    private fun resolveTrackMeta(youtubeId: String): Pair<String?, String?> {
        val topMatch = state.value.search.topSongs.firstOrNull { it.youtubeId == youtubeId }
        if (topMatch != null) {
            return topMatch.title to topMatch.artist
        }
        val favoriteMatch = state.value.favorites.firstOrNull { it.uniqueSongId == youtubeId }
        if (favoriteMatch != null) {
            return favoriteMatch.title to favoriteMatch.artist
        }
        return null to null
    }

    fun toggleFavoriteForCurrent(): FavoriteToggleResult {
        val currentState = state.value
        val currentId = currentState.playback.lastYouTubeId ?: return FavoriteToggleResult.NoTrack
        if (shouldBlockListenFavoriteToggle(currentState)) {
            return FavoriteToggleResult.BlockedInFlight
        }
        val favorites = currentState.favorites
        val syncFromListenToggle = hasRealFavoritesSyncPath(currentState)
        val existing = favorites.any { it.uniqueSongId == currentId }
        return if (existing) {
            favoritesController.updateFavorites(
                favorites.filterNot { it.uniqueSongId == currentId },
                fromListenToggle = syncFromListenToggle
            )
            FavoriteToggleResult.Removed
        } else {
            if (favorites.size >= MAX_FAVORITES) {
                FavoriteToggleResult.LimitReached
            } else {
                val playback = currentState.playback
                val title = playback.trackTitle?.takeIf { it.isNotBlank() } ?: "Untitled"
                val artist = playback.trackArtist?.takeIf { it.isNotBlank() } ?: "Unknown"
                val newFavorite = FavoriteTrack(
                    uniqueSongId = currentId,
                    title = title,
                    artist = artist,
                    duration = playback.trackDurationSeconds,
                    sourceType = FavoriteSourceType.Youtube,
                    tuningParams = if (playback.playMode == PlaybackMode.Jukebox) {
                        TuningParamsCodec.stripHighlightAnchorParam(
                            playbackCoordinator.buildTuningParamsString()
                        )
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
        if (favorites.none { it.uniqueSongId == uniqueSongId }) return
        favoritesController.updateFavorites(favorites.filterNot { it.uniqueSongId == uniqueSongId })
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

    fun selectSpotifyTrack(item: SpotifySearchItem) {
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
                    val response = api.getJobByTrack(baseUrl, name, artist)
                    val jobId = response?.id
                    val youtubeId = response?.youtubeId
                    if (response != null && jobId != null && youtubeId != null && response.status != "failed") {
                        if (state.value.playback.isCasting) {
                            clearSearchSelectionState()
                            castPlaybackCoordinator.castTrackId(youtubeId, name, artist)
                            applyActiveTab(TabId.Play, recordHistory = true)
                            return@launch
                        }
                        loadExistingJob(
                            jobId,
                            youtubeId,
                            response,
                            name,
                            artist
                        )
                        return@launch
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.e(TAG, "Job lookup by track failed", error)
                    // Fall back to YouTube matches.
                }
            }
            fetchYoutubeMatches(name, artist, duration)
        }
    }

    fun selectYoutubeTrack(item: YoutubeSearchItem) {
        val youtubeId = item.id ?: return
        val duration = item.duration
        if (showTrackLengthLimitIfExceeded(duration)) {
            return
        }
        startYoutubeAnalysis(youtubeId)
    }

    fun fetchYoutubeMatches(name: String, artist: String, duration: Double) {
        searchCoordinator.fetchYoutubeMatches(name, artist, duration)
    }

    fun startYoutubeAnalysis(youtubeId: String, title: String? = null, artist: String? = null) {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        val resolvedTitle = title ?: state.value.search.pendingTrackName.orEmpty()
        val resolvedArtist = artist ?: state.value.search.pendingTrackArtist.orEmpty()
        if (state.value.playback.isCasting) {
            clearSearchSelectionState()
            applyActiveTab(TabId.Play, recordHistory = true)
            viewModelScope.launch {
                val queued = queueYoutubeAnalysisForCast(
                    youtubeId = youtubeId,
                    title = resolvedTitle,
                    artist = resolvedArtist
                )
                if (!queued) {
                    return@launch
                }
                castPlaybackCoordinator.castTrackId(youtubeId, resolvedTitle, resolvedArtist)
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
                    lastYouTubeId = youtubeId
                )
            )
        }
        launchServerTrackLoadWithCache(
            cacheKey = youtubeId,
            failureLogMessage = "Failed to start YouTube analysis"
        ) {
            val response = api.startYoutubeAnalysis(
                baseUrl,
                youtubeId,
                resolvedTitle,
                resolvedArtist
            )
            val responseId = response.id ?: return@launchServerTrackLoadWithCache false
            playbackCoordinator.setAnalysisQueued(response.progress?.roundToInt(), response.message)
            playbackCoordinator.setLastJobId(responseId)
            playbackCoordinator.startPoll(responseId)
            true
        }
    }

    fun loadTrackByYoutubeId(
        youtubeId: String,
        title: String? = null,
        artist: String? = null,
        tuningParams: String? = null
    ) {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        val (resolvedTitle, resolvedArtist) = if (title == null && artist == null) {
            resolveTrackMeta(youtubeId)
        } else {
            title to artist
        }
        if (state.value.playback.isCasting) {
            applyActiveTab(TabId.Play, recordHistory = true)
            viewModelScope.launch {
                val queued = queueYoutubeAnalysisForCast(
                    youtubeId = youtubeId,
                    title = resolvedTitle,
                    artist = resolvedArtist
                )
                if (!queued) {
                    return@launch
                }
                castPlaybackCoordinator.castTrackId(youtubeId, resolvedTitle, resolvedArtist)
            }
            return
        }
        prepareServerTrackLoad(tuningParams = tuningParams) { current ->
            current.copy(
                playback = current.playback.copy(
                    audioLoading = false,
                    lastYouTubeId = youtubeId,
                    trackTitle = resolvedTitle,
                    trackArtist = resolvedArtist
                )
            )
        }
        launchServerTrackLoadWithCache(
            cacheKey = youtubeId,
            failureLogMessage = "Failed to load track by YouTube id"
        ) {
            val response = api.getJobByYoutube(baseUrl, youtubeId)
            if (response != null) {
                return@launchServerTrackLoadWithCache serverTrackLoadCoordinator.loadOrPoll(response)
            }
            val started = api.startYoutubeAnalysis(
                baseUrl = baseUrl,
                youtubeId = youtubeId,
                title = resolvedTitle,
                artist = resolvedArtist
            )
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
        tuningParams: String? = null
    ) {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        if (state.value.playback.isCasting) {
            castPlaybackCoordinator.castTrackId(jobId, title, artist)
            applyActiveTab(TabId.Play, recordHistory = true)
            return
        }
        prepareServerTrackLoad(tuningParams = tuningParams) { current ->
            current.copy(
                playback = current.playback.copy(
                    audioLoading = false,
                    lastYouTubeId = null,
                    lastJobId = jobId,
                    trackTitle = title,
                    trackArtist = artist
                )
            )
        }
        launchServerTrackLoadWithCache(
            cacheKey = jobId,
            failureLogMessage = "Failed to load track by job id"
        ) {
            val response = api.getAnalysis(baseUrl, jobId)
            serverTrackLoadCoordinator.loadOrPoll(response, fallbackJobId = jobId)
        }
    }

    private suspend fun loadExistingJob(
        jobId: String,
        youtubeId: String,
        response: AnalysisResponse,
        title: String? = null,
        artist: String? = null
    ) {
        if (state.value.playback.isCasting) {
            castPlaybackCoordinator.castTrackId(youtubeId, title, artist)
            applyActiveTab(TabId.Play, recordHistory = true)
            return
        }
        playbackCoordinator.resetForNewTrack()
        _state.update {
            it.copy(
                search = resetSearchStateAfterTrackSelection(it.search),
                playback = it.playback.copy(
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
        } catch (error: Exception) {
            Log.e(TAG, "Failed to load existing job", error)
            playbackCoordinator.setAnalysisError("Loading failed.")
        }
    }

    private fun prepareServerTrackLoad(
        tuningParams: String?,
        stateUpdate: (UiState) -> UiState
    ) {
        serverTrackLoadCoordinator.cancel()
        playbackCoordinator.resetForNewTrack()
        playbackCoordinator.setPendingTuningParams(tuningParams)
        _state.update(stateUpdate)
        applyActiveTab(TabId.Play, recordHistory = true)
    }

    private fun launchServerTrackLoadWithCache(
        cacheKey: String,
        failureLogMessage: String,
        request: suspend () -> Boolean
    ) {
        serverTrackLoadCoordinator.launch {
            if (playbackCoordinator.tryLoadCachedTrack(cacheKey)) {
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
            } catch (error: Exception) {
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
        val youtubeId = current.lastYouTubeId
        if (!youtubeId.isNullOrBlank()) {
            playbackCoordinator.setAnalysisQueued(null, "Restoring track...")
            loadTrackByYoutubeId(youtubeId, current.trackTitle, current.trackArtist)
            return false
        }
        playbackCoordinator.setAnalysisError("Analysis unavailable. Reload the track.")
        return false
    }

    fun togglePlayback() {
        val current = state.value.playback
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
        val trackId = current.lastYouTubeId ?: current.lastJobId
        if (trackId.isNullOrBlank()) {
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
        syncCastNotification(state.value.playback)
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
                        ForegroundPlaybackService.start(getApplication())
                    }
                    paused -> {
                        playbackCoordinator.stopListenTimer()
                        ForegroundPlaybackService.update(getApplication())
                    }
                    else -> handleJukeboxPlaybackFailure()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
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
        ForegroundPlaybackService.update(getApplication())
    }

    private fun handleJukeboxPlaybackFailure() {
        playbackCoordinator.stopListenTimer()
        ForegroundPlaybackService.stop(getApplication())
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
            ForegroundPlaybackService.update(getApplication())
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
            val resumed = controller.autocanonizer.resume()
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
            ForegroundPlaybackService.start(getApplication())
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
            ForegroundPlaybackService.start(getApplication())
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

    fun castCurrentTrack() {
        castSessionCoordinator.castCurrentTrack()
    }

    fun setCastingConnected(isConnected: Boolean, deviceName: String? = null) {
        castSessionCoordinator.setCastingConnected(isConnected, deviceName)
    }

    fun stopCasting() {
        castSessionCoordinator.stopCasting()
    }

    fun requestCastStatus() {
        castSessionCoordinator.requestCastStatus()
    }

    private fun sendCastCommand(command: String): Boolean {
        return castPlaybackCoordinator.sendCastCommand(command)
    }

    private suspend fun queueYoutubeAnalysisForCast(
        youtubeId: String,
        title: String?,
        artist: String?
    ): Boolean {
        val queued = tryQueueYoutubeAnalysisForCast(
            baseUrl = state.value.baseUrl,
            youtubeId = youtubeId,
            title = title,
            artist = artist
        ) { baseUrl, id, trackTitle, trackArtist ->
            api.startYoutubeAnalysis(
                baseUrl = baseUrl,
                youtubeId = id,
                title = trackTitle,
                artist = trackArtist
            )
            Unit
        }
        if (!queued) {
            showToast("Unable to queue this track for casting.")
        }
        return queued
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
        val baseUrl = state.value.baseUrl.trim()
        if (baseUrl.isBlank()) {
            viewModelScope.launch { showToast("Set a base URL first.") }
            return
        }
        val youtubeId = state.value.playback.lastYouTubeId
        if (youtubeId.isNullOrBlank()) {
            viewModelScope.launch { showToast("Nothing to retry.") }
            return
        }
        val title = state.value.playback.trackTitle
        val artist = state.value.playback.trackArtist
        serverTrackLoadCoordinator.cancel()
        playbackCoordinator.resetForNewTrack()
        loadTrackByYoutubeId(youtubeId, title, artist)
    }

    suspend fun deleteCurrentJob(): Boolean {
        if (state.value.playback.deleteInFlight) return false
        val jobId = playbackCoordinator.getLastJobId() ?: return false
        val baseUrl = state.value.baseUrl
        val youtubeId = state.value.playback.lastYouTubeId
        if (baseUrl.isBlank()) return false
        updatePlaybackState { it.copy(deleteInFlight = true) }
        return try {
            api.deleteJob(baseUrl, jobId)
            if (youtubeId != null) {
                playbackCoordinator.clearCachedTrack(youtubeId)
                favoritesController.updateFavorites(
                    state.value.favorites.filterNot { it.uniqueSongId == youtubeId }
                )
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
        } catch (error: Exception) {
            Log.e(TAG, "Failed to delete current job", error)
            playbackCoordinator.markDeleteEligibilityFailed(jobId)
            false
        } finally {
            updatePlaybackState { it.copy(deleteInFlight = false) }
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
            ForegroundPlaybackService.start(getApplication())
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

    fun refreshPlaybackFromController() {
        playbackCoordinator.restorePlaybackState()
        playbackCoordinator.updateListenTimeDisplay()
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        val current = state.value.playback
        if (current.playMode == mode) {
            return
        }
        if (!current.isCasting) {
            stopTransportForModeChange(
                context = getApplication(),
                controller = controller,
                previousMode = current.playMode,
                isRunning = current.isRunning || current.isPaused,
                onStopped = {
                    playbackCoordinator.stopListenTimer()
                    playbackCoordinator.updateListenTimeDisplay()
                }
            )
        }
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
            syncCastNotification(state.value.playback)
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
        removeSequentialBranches: Boolean
    ) {
        viewModelScope.launch {
            tuningCoordinator.applyTuning(
                threshold = threshold,
                minProb = minProb,
                maxProb = maxProb,
                ramp = ramp,
                highlightAnchorBranch = highlightAnchorBranch,
                justBackwards = justBackwards,
                justLongBranches = justLongBranches,
                removeSequentialBranches = removeSequentialBranches
            )
        }
    }

    fun resetTuningDefaults() {
        viewModelScope.launch {
            tuningCoordinator.resetTuningDefaults()
        }
    }

    fun handleDeepLink(uri: Uri?) {
        listenLinkCoordinator.handleDeepLink(uri?.toString())
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

    private fun checkForAppUpdateOnce() {
        appLifecycleCoordinator.checkForAppUpdateOnce()
    }

    private fun syncCastNotification(playback: PlaybackState) {
        if (!playback.shouldShowCastNotification()) {
            ForegroundPlaybackService.stop(getApplication())
            return
        }
        ForegroundPlaybackService.updateCast(
            context = getApplication(),
            isPlaying = playback.isRunning,
            title = playback.castNotificationTitle(),
            artist = playback.trackArtist,
            deviceName = playback.castDeviceName
        )
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(getApplication(), message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
        private const val MAX_FAVORITES = 100
        private const val CAST_MAX_TRACK_DURATION_MINUTES = 7.0
        private const val GITHUB_REPO_OWNER = "creightonlinza"
        private const val GITHUB_REPO_NAME = "forever-jukebox-android"
        private const val RANDOM_BRANCH_DELTA_PERCENT_SCALE = 500.0
    }
}
