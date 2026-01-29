package com.foreverjukebox.app.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.foreverjukebox.app.data.ApiClient
import com.foreverjukebox.app.data.AppPreferences
import com.foreverjukebox.app.data.AnalysisResponse
import com.foreverjukebox.app.data.FavoriteSourceType
import com.foreverjukebox.app.data.FavoriteTrack
import com.foreverjukebox.app.data.SpotifySearchItem
import com.foreverjukebox.app.data.ThemeMode
import com.foreverjukebox.app.engine.VisualizationData
import com.foreverjukebox.app.playback.ForegroundPlaybackService
import com.foreverjukebox.app.playback.PlaybackControllerHolder
import com.foreverjukebox.app.visualization.JumpLine
import com.foreverjukebox.app.visualization.visualizationCount
import com.foreverjukebox.app.cast.CastAppIdResolver
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.math.roundToInt
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val api = ApiClient()
    private val controller = PlaybackControllerHolder.get(application)
    private val engine = controller.engine
    private val defaultConfig = engine.getConfig()
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var listenTimerJob: Job? = null
    private var refreshTopSongsJob: Job? = null
    private var pollJob: Job? = null
    private var audioLoadInFlight = false
    private var lastJobId: String? = null
    private var lastPlayCountedJobId: String? = null
    private var deleteEligibilityJobId: String? = null
    private var topSongsLoaded = false
    private var appConfigLoaded = false
    private var favoritesSyncHydratedFor: String? = null
    private var syncUpdateInFlight = false
    private var pendingSyncDelta: FavoritesDelta? = null
    private val tabHistory = ArrayDeque<TabId>()
    private var lastNotificationUpdateMs = 0L
    private var castStatusListenerRegistered = false

    init {
        viewModelScope.launch {
            preferences.baseUrl.collect { url ->
                val resolvedAppId = CastAppIdResolver.resolve(getApplication(), url)
                _state.update { current ->
                    current.copy(
                        baseUrl = url.orEmpty(),
                        showBaseUrlPrompt = url.isNullOrBlank(),
                        castEnabled = !resolvedAppId.isNullOrBlank()
                    )
                }
                if (!url.isNullOrBlank()) {
                    if (!appConfigLoaded) {
                        appConfigLoaded = true
                        viewModelScope.launch {
                            runCatching { api.getAppConfig(url).also { preferences.setAppConfig(it) } }
                        }
                    }
                    if (state.value.activeTab == TabId.Top && !topSongsLoaded) {
                        refreshTopSongs()
                    }
                    maybeHydrateFavoritesFromSync()
                }
            }
        }
        viewModelScope.launch {
            preferences.favorites.collect { favorites ->
                val sorted = sortFavorites(favorites).take(MAX_FAVORITES)
                if (sorted.size != favorites.size) {
                    updateFavorites(sorted, sync = false)
                } else {
                    _state.update { it.copy(favorites = sorted) }
                }
            }
        }
        viewModelScope.launch {
            preferences.favoritesSyncCode.collect { code ->
                _state.update { it.copy(favoritesSyncCode = code) }
                maybeHydrateFavoritesFromSync()
            }
        }
        viewModelScope.launch {
            preferences.appConfig.collect { config ->
                if (config != null) {
                    _state.update { it.copy(allowFavoritesSync = config.allowFavoritesSync) }
                    maybeHydrateFavoritesFromSync()
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
                val resolvedIndex = if (index in 0 until visualizationCount) index else 0
                _state.update {
                    it.copy(playback = it.playback.copy(activeVizIndex = resolvedIndex))
                }
            }
        }
        engine.onUpdate { engineState ->
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
            maybeUpdateNotification()
        }

        restorePlaybackState()
    }

    override fun onCleared() {
        super.onCleared()
        listenTimerJob?.cancel()
        pollJob?.cancel()
        controller.player.release()
    }

    fun setBaseUrl(url: String) {
        viewModelScope.launch {
            preferences.setBaseUrl(url.trim())
            delay(100)
            refreshTopSongs()
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferences.setThemeMode(mode)
        }
    }

    fun setActiveTab(tabId: TabId) {
        if (tabId == TabId.Top && state.value.activeTab == TabId.Top) {
            setTopSongsTab(TopSongsTab.TopSongs)
            return
        }
        applyActiveTab(tabId, recordHistory = true)
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
    }

    fun refreshFavoritesFromSync() {
        viewModelScope.launch {
            if (!state.value.allowFavoritesSync) {
                return@launch
            }
            val result = fetchFavoritesFromSync()
            if (result == null) {
                showToast("Favorites sync failed.")
            } else {
                updateFavorites(result, sync = false)
                favoritesSyncHydratedFor = state.value.favoritesSyncCode
                showToast("Favorites refreshed.")
            }
        }
    }

    fun createFavoritesSyncCode() {
        viewModelScope.launch {
            if (!state.value.allowFavoritesSync) {
                return@launch
            }
            val baseUrl = state.value.baseUrl
            if (baseUrl.isBlank()) {
                showToast("API base URL is required.")
                return@launch
            }
            val favorites = state.value.favorites
            try {
                val response = api.createFavoritesSync(baseUrl, favorites)
                val code = response.code?.trim()?.lowercase()
                if (code.isNullOrBlank()) {
                    throw IOException("Missing sync code")
                }
                preferences.setFavoritesSyncCode(code)
                favoritesSyncHydratedFor = code
                val normalized = normalizeFavorites(response.favorites)
                if (normalized.isNotEmpty()) {
                    updateFavorites(normalized, sync = false)
                }
            } catch (_: Exception) {
                showToast("Unable to create sync code.")
            }
        }
    }

    suspend fun fetchFavoritesPreview(code: String): List<FavoriteTrack>? {
        return fetchFavoritesFromSync(code)
    }

    fun applyFavoritesSync(code: String, favorites: List<FavoriteTrack>) {
        viewModelScope.launch {
            if (!state.value.allowFavoritesSync) {
                return@launch
            }
            preferences.setFavoritesSyncCode(code)
            favoritesSyncHydratedFor = code
            updateFavorites(normalizeFavorites(favorites), sync = false)
        }
    }

    private fun applyActiveTab(tabId: TabId, recordHistory: Boolean) {
        val current = state.value.activeTab
        if (tabId == current) return
        if (recordHistory && tabHistory.lastOrNull() != current) {
            tabHistory.addLast(current)
        }
        _state.update {
            val nextTopTab = if (tabId == TabId.Top) TopSongsTab.TopSongs else it.topSongsTab
            it.copy(activeTab = tabId, topSongsTab = nextTopTab)
        }
        if (tabId == TabId.Top) {
            scheduleTopSongsRefresh()
        }
        if (tabId != TabId.Play) {
            _state.update { it.copy(playback = it.playback.copy()) }
        }
    }

    private fun scheduleTopSongsRefresh() {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank() || topSongsLoaded) return
        refreshTopSongsJob?.cancel()
        refreshTopSongsJob = viewModelScope.launch {
            delay(250)
            refreshTopSongs()
        }
    }

    private fun updateSearchState(transform: (SearchState) -> SearchState) {
        _state.update { it.copy(search = transform(it.search)) }
    }

    private fun setSearchQuery(value: String) {
        updateSearchState { it.copy(query = value) }
    }

    private fun updatePlaybackState(transform: (PlaybackState) -> PlaybackState) {
        _state.update { it.copy(playback = transform(it.playback)) }
    }

    private fun setLastJobId(jobId: String?) {
        lastJobId = jobId
        updatePlaybackState { it.copy(lastJobId = jobId) }
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

    private suspend fun maybeRepairMissing(
        response: AnalysisResponse
    ): AnalysisResponse {
        return response
    }

    fun toggleFavoriteForCurrent(): Boolean {
        val currentId = state.value.playback.lastYouTubeId ?: return false
        val favorites = state.value.favorites
        val existing = favorites.any { it.uniqueSongId == currentId }
        return if (existing) {
            updateFavorites(favorites.filterNot { it.uniqueSongId == currentId })
            false
        } else {
            if (favorites.size >= MAX_FAVORITES) {
                true
            } else {
                val playback = state.value.playback
                val title = playback.trackTitle?.takeIf { it.isNotBlank() } ?: "Untitled"
                val artist = playback.trackArtist?.takeIf { it.isNotBlank() } ?: "Unknown"
                val newFavorite = FavoriteTrack(
                    uniqueSongId = currentId,
                    title = title,
                    artist = artist,
                    duration = playback.trackDurationSeconds,
                    sourceType = FavoriteSourceType.Youtube
                )
                updateFavorites(favorites + newFavorite)
                false
            }
        }
    }

    fun removeFavorite(uniqueSongId: String) {
        val favorites = state.value.favorites
        if (favorites.none { it.uniqueSongId == uniqueSongId }) return
        updateFavorites(favorites.filterNot { it.uniqueSongId == uniqueSongId })
    }

    private sealed class LoadingEvent {
        data object Reset : LoadingEvent()
        data class AnalysisQueued(val progress: Int?, val message: String?) : LoadingEvent()
        data class AnalysisProgress(val progress: Int?, val message: String?) : LoadingEvent()
        data object AnalysisCalculating : LoadingEvent()
        data class AnalysisError(val message: String) : LoadingEvent()
        data class AudioLoading(val loading: Boolean) : LoadingEvent()
    }

    private fun applyLoadingEvent(event: LoadingEvent) {
        updatePlaybackState { current ->
            when (event) {
                LoadingEvent.Reset -> current.copy(
                    analysisProgress = null,
                    analysisMessage = null,
                    analysisErrorMessage = null,
                    analysisInFlight = false,
                    analysisCalculating = false
                )
                is LoadingEvent.AnalysisQueued -> current.copy(
                    analysisProgress = event.progress,
                    analysisMessage = event.message,
                    analysisErrorMessage = null,
                    analysisInFlight = true,
                    analysisCalculating = false
                )
                is LoadingEvent.AnalysisProgress -> current.copy(
                    analysisProgress = event.progress,
                    analysisMessage = event.message,
                    analysisErrorMessage = null,
                    analysisInFlight = true,
                    analysisCalculating = false
                )
                LoadingEvent.AnalysisCalculating -> current.copy(
                    analysisProgress = null,
                    analysisMessage = null,
                    analysisErrorMessage = null,
                    analysisInFlight = false,
                    analysisCalculating = true
                )
                is LoadingEvent.AnalysisError -> current.copy(
                    analysisProgress = null,
                    analysisMessage = null,
                    analysisErrorMessage = event.message,
                    analysisInFlight = false,
                    analysisCalculating = false,
                    audioLoading = false
                )
                is LoadingEvent.AudioLoading -> current.copy(audioLoading = event.loading)
            }
        }
    }

    private fun setAnalysisQueued(progress: Int?, message: String? = null) {
        applyLoadingEvent(LoadingEvent.AnalysisQueued(progress, message))
    }

    private fun setAnalysisProgress(progress: Int?, message: String? = null) {
        val normalized = if (progress == 0 && message != "Loading audio") null else progress
        applyLoadingEvent(LoadingEvent.AnalysisProgress(normalized, message))
    }

    private fun setDecodeProgress(percent: Int) {
        val current = state.value.playback
        if (
            current.analysisInFlight &&
            !current.analysisMessage.isNullOrBlank() &&
            current.analysisMessage != "Loading audio"
        ) {
            return
        }
        setAnalysisProgress(percent, "Loading audio")
    }

    private fun setAnalysisCalculating() {
        applyLoadingEvent(LoadingEvent.AnalysisCalculating)
    }

    private fun setAnalysisError(message: String) {
        applyLoadingEvent(LoadingEvent.AnalysisError(message))
    }

    private fun setAudioLoading(loading: Boolean) {
        applyLoadingEvent(LoadingEvent.AudioLoading(loading))
    }

    private inline fun ignoreFailures(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Ignore cache failures.
        }
    }

    private fun startListenTimer() {
        if (listenTimerJob?.isActive == true) return
        listenTimerJob = viewModelScope.launch {
            while (coroutineContext.isActive) {
                updateListenTimeDisplay()
                delay(200)
            }
        }
    }

    private fun stopListenTimer() {
        listenTimerJob?.cancel()
        listenTimerJob = null
    }

    private fun startPoll(jobId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            try {
                pollAnalysis(jobId)
            } catch (_: Exception) {
                setAnalysisError("Loading failed.")
            }
        }
    }

    fun refreshTopSongs() {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        topSongsLoaded = true
        viewModelScope.launch {
            updateSearchState { it.copy(topSongsLoading = true) }
            try {
                val items = api.fetchTopSongs(baseUrl)
                updateSearchState { it.copy(topSongs = items) }
            } catch (err: Exception) {
                updateSearchState { it.copy(topSongs = emptyList()) }
            } finally {
                updateSearchState { it.copy(topSongsLoading = false) }
            }
        }
    }

    fun runSpotifySearch(query: String) {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        setSearchQuery(query)
        _state.update {
            it.copy(
                search = it.search.copy(
                    youtubeMatches = emptyList(),
                    spotifyResults = emptyList(),
                    spotifyLoading = true
                )
            )
        }
        viewModelScope.launch {
            try {
                val items = api.searchSpotify(baseUrl, query).take(10)
                updateSearchState { it.copy(spotifyResults = items) }
            } catch (_: Exception) {
                updateSearchState { it.copy(spotifyResults = emptyList()) }
            } finally {
                updateSearchState { it.copy(spotifyLoading = false) }
            }
        }
    }

    fun selectSpotifyTrack(item: SpotifySearchItem) {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        val name = item.name ?: "Untitled"
        val artist = item.artist ?: ""
        val duration = item.duration ?: return
        viewModelScope.launch {
            if (artist.isNotBlank()) {
                try {
                    val response = maybeRepairMissing(
                        api.getJobByTrack(baseUrl, name, artist)
                    )
                    val jobId = response.id
                    val youtubeId = response.youtubeId
                    if (jobId != null && youtubeId != null && response.status != "failed") {
                        if (state.value.playback.isCasting) {
                            castTrackId(youtubeId, name, artist)
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
                } catch (_: Exception) {
                    // Fall back to YouTube matches.
                    if (state.value.playback.isCasting) {
                        showToast("Only existing songs can be cast.")
                        return@launch
                    }
                }
            }
            if (state.value.playback.isCasting) {
                showToast("Only existing songs can be cast.")
                return@launch
            }
            fetchYoutubeMatches(name, artist, duration)
        }
    }

    fun fetchYoutubeMatches(name: String, artist: String, duration: Double) {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        val query = if (artist.isNotBlank()) "$artist - $name" else name
        _state.update {
            it.copy(
                search = it.search.copy(
                    pendingTrackName = name,
                    pendingTrackArtist = artist,
                    spotifyResults = emptyList(),
                    youtubeMatches = emptyList(),
                    youtubeLoading = true
                )
            )
        }
        viewModelScope.launch {
            try {
                val items = api.searchYoutube(baseUrl, query, duration).take(10)
                updateSearchState { it.copy(youtubeMatches = items) }
            } catch (_: Exception) {
                updateSearchState { it.copy(youtubeMatches = emptyList()) }
            } finally {
                updateSearchState { it.copy(youtubeLoading = false) }
            }
        }
    }

    fun startYoutubeAnalysis(youtubeId: String, title: String? = null, artist: String? = null) {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        val resolvedTitle = title ?: state.value.search.pendingTrackName.orEmpty()
        val resolvedArtist = artist ?: state.value.search.pendingTrackArtist.orEmpty()
        if (state.value.playback.isCasting) {
            castTrackId(youtubeId, resolvedTitle, resolvedArtist)
            _state.update {
                it.copy(playback = it.playback.copy(lastYouTubeId = youtubeId))
            }
            applyActiveTab(TabId.Play, recordHistory = true)
            return
        }
        resetForNewTrack()
        _state.update {
            it.copy(
                search = it.search.copy(
                    query = "",
                    spotifyResults = emptyList(),
                    youtubeMatches = emptyList(),
                    youtubeLoading = false,
                    pendingTrackName = null,
                    pendingTrackArtist = null
                ),
                playback = it.playback.copy(
                    audioLoading = false,
                    lastYouTubeId = youtubeId
                )
            )
        }
        applyActiveTab(TabId.Play, recordHistory = true)
        viewModelScope.launch {
            if (tryLoadCachedTrack(youtubeId)) {
                return@launch
            }
            setAnalysisQueued(null, "Fetching audio...")
            try {
                val response = api.startYoutubeAnalysis(
                    baseUrl,
                    youtubeId,
                    resolvedTitle,
                    resolvedArtist
                )
                if (response.id == null) {
                    throw IllegalStateException("Invalid job response")
                }
                setAnalysisQueued(response.progress?.roundToInt(), response.message)
                setLastJobId(response.id)
                startPoll(response.id)
            } catch (err: Exception) {
                setAnalysisError("Loading failed.")
            }
        }
    }

    fun loadTrackByYoutubeId(youtubeId: String, title: String? = null, artist: String? = null) {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        val (resolvedTitle, resolvedArtist) = if (title == null && artist == null) {
            resolveTrackMeta(youtubeId)
        } else {
            title to artist
        }
        if (state.value.playback.isCasting) {
            castTrackId(youtubeId, resolvedTitle, resolvedArtist)
            _state.update {
                it.copy(
                    playback = it.playback.copy(
                        lastYouTubeId = youtubeId,
                        trackTitle = resolvedTitle,
                        trackArtist = resolvedArtist
                    )
                )
            }
            applyActiveTab(TabId.Play, recordHistory = true)
            return
        }
        resetForNewTrack()
        _state.update {
            it.copy(
                playback = it.playback.copy(
                    audioLoading = false,
                    lastYouTubeId = youtubeId,
                    trackTitle = resolvedTitle,
                    trackArtist = resolvedArtist
                )
            )
        }
        applyActiveTab(TabId.Play, recordHistory = true)
        viewModelScope.launch {
            if (tryLoadCachedTrack(youtubeId)) {
                return@launch
            }
            setAnalysisQueued(null, "Fetching audio...")
            try {
                val response = maybeRepairMissing(api.getJobByYoutube(baseUrl, youtubeId))
                if (response.id == null) {
                    setAnalysisError("Loading failed.")
                    return@launch
                }
                updateDeleteEligibility(response)
                setLastJobId(response.id)
                if (response.status == "complete" && response.result != null) {
                    if (!state.value.playback.audioLoaded) {
                        val loaded = loadAudioFromJob(response.id)
                        if (!loaded) {
                            startPoll(response.id)
                            return@launch
                        }
                    }
                    if (applyAnalysisResult(response)) {
                        return@launch
                    }
                    return@launch
                }
                startPoll(response.id)
            } catch (err: Exception) {
                setAnalysisError("Loading failed.")
            }
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
            castTrackId(youtubeId, title, artist)
            _state.update {
                it.copy(
                    playback = it.playback.copy(
                        lastYouTubeId = youtubeId,
                        trackTitle = title,
                        trackArtist = artist
                    )
                )
            }
            applyActiveTab(TabId.Play, recordHistory = true)
            return
        }
        resetForNewTrack()
        _state.update {
            it.copy(
                search = it.search.copy(
                    query = "",
                    spotifyResults = emptyList(),
                    youtubeMatches = emptyList(),
                    youtubeLoading = false,
                    pendingTrackName = null,
                    pendingTrackArtist = null
                ),
                playback = it.playback.copy(
                    lastYouTubeId = youtubeId,
                    trackTitle = title,
                    trackArtist = artist
                )
            )
        }
        applyActiveTab(TabId.Play, recordHistory = true)
        setAnalysisQueued(null, response.message)
        setLastJobId(jobId)
        updateDeleteEligibility(response)
        try {
            if (response.status == "complete" && response.result != null) {
                if (!state.value.playback.audioLoaded) {
                    val loaded = loadAudioFromJob(jobId)
                    if (!loaded) {
                        startPoll(jobId)
                        return
                    }
                }
                if (applyAnalysisResult(response)) {
                    return
                }
                return
            }
            startPoll(jobId)
        } catch (_: Exception) {
            setAnalysisError("Loading failed.")
        }
    }

    private fun cacheDir(): File {
        val dir = File(getApplication<Application>().cacheDir, "jukebox-cache")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun analysisFile(youtubeId: String): File =
        File(cacheDir(), "$youtubeId.analysis.json")

    private fun audioFile(youtubeId: String): File = File(cacheDir(), "$youtubeId.audio")

    private suspend fun tryLoadCachedTrack(youtubeId: String): Boolean {
        val cached = withContext(Dispatchers.IO) {
            val analysisPath = analysisFile(youtubeId)
            val audioPath = audioFile(youtubeId)
            if (!analysisPath.exists() || !audioPath.exists()) {
                return@withContext null
            }
            val analysisText = analysisPath.readText()
            val response = json.decodeFromString<AnalysisResponse>(analysisText)
            response to audioPath
        }
        if (cached == null) {
            return false
        }
        val (response, audioPath) = cached
        setAnalysisProgress(0, "Loading audio")
        try {
            withContext(Dispatchers.Default) {
                controller.player.loadFile(audioPath) { percent ->
                    viewModelScope.launch(Dispatchers.Main) {
                        setAnalysisProgress(percent, "Loading audio")
                    }
                }
            }
        } catch (err: OutOfMemoryError) {
            withContext(Dispatchers.IO) {
                audioFile(youtubeId).delete()
            }
            return false
        }
        audioLoadInFlight = false
        updatePlaybackState {
            it.copy(
                audioLoaded = true,
                audioLoading = false,
                analysisProgress = null,
                analysisMessage = null,
                analysisInFlight = false,
                analysisCalculating = false
            )
        }
        setLastJobId(response.id)
        applyAnalysisResult(response)
        return true
    }

    private fun cacheAnalysis(
        youtubeId: String,
        response: AnalysisResponse
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            ignoreFailures {
                val payload = json.encodeToString(response)
                analysisFile(youtubeId).writeText(payload)
            }
        }
    }

    private suspend fun clearCachedTrack(youtubeId: String) {
        withContext(Dispatchers.IO) {
            ignoreFailures { analysisFile(youtubeId).delete() }
            ignoreFailures { audioFile(youtubeId).delete() }
        }
    }

    private fun updateDeleteEligibility(response: AnalysisResponse) {
        val jobId = response.id ?: lastJobId ?: return
        if (deleteEligibilityJobId == jobId) {
            return
        }
        val createdAt = response.createdAt
        if (createdAt.isNullOrBlank()) {
            updatePlaybackState { it.copy(deleteEligible = false) }
            deleteEligibilityJobId = null
            return
        }
        deleteEligibilityJobId = jobId
        val parsed = runCatching { OffsetDateTime.parse(createdAt).toInstant() }.getOrNull()
        val eligible = if (parsed == null) {
            false
        } else {
            Duration.between(parsed, OffsetDateTime.now().toInstant()).seconds <= 1800
        }
        updatePlaybackState { it.copy(deleteEligible = eligible) }
    }

    fun togglePlayback() {
        val current = state.value.playback
        if (current.isCasting) {
            if (!state.value.castEnabled) {
                notifyCastUnavailable()
                return
            }
            val trackId = current.lastYouTubeId ?: current.lastJobId
            if (trackId.isNullOrBlank()) {
                viewModelScope.launch { showToast("Select a track before playing.") }
                return
            }
            val command = if (current.isRunning) "stop" else "play"
            val sent = sendCastCommand(command)
            if (!sent) {
                viewModelScope.launch { showToast("Connect to a Cast device first.") }
                return
            }
            _state.update {
                it.copy(playback = it.playback.copy(isRunning = !current.isRunning))
            }
            return
        }
        if (!current.audioLoaded || !current.analysisLoaded) return
        if (!current.isRunning) {
            try {
                val running = controller.togglePlayback()
                updateListenTimeDisplay()
                _state.update {
                    it.copy(
                        playback = it.playback.copy(
                            beatsPlayed = 0,
                            currentBeatIndex = -1,
                            isRunning = running
                        )
                    )
                }
                if (running) {
                    startListenTimer()
                    ForegroundPlaybackService.start(getApplication())
                }
            } catch (err: Exception) {
                setAnalysisError("Playback failed.")
            }
        } else {
            controller.stopPlayback()
            stopListenTimer()
            updateListenTimeDisplay()
            _state.update { it.copy(playback = it.playback.copy(isRunning = false)) }
            ForegroundPlaybackService.stop(getApplication())
        }
    }

    fun castCurrentTrack() {
        if (!state.value.castEnabled) {
            notifyCastUnavailable()
            return
        }
        val baseUrl = state.value.baseUrl.trim()
        if (baseUrl.isBlank()) {
            viewModelScope.launch { showToast("Set a base URL before casting.") }
            return
        }
        val playback = state.value.playback
        val trackId = playback.lastYouTubeId ?: playback.lastJobId
        if (trackId.isNullOrBlank()) {
            viewModelScope.launch { showToast("Load a track before casting.") }
            return
        }
        val castContext = try {
            CastContext.getSharedInstance(getApplication())
        } catch (_: Exception) {
            viewModelScope.launch { showToast("Cast is unavailable on this device.") }
            return
        }
        val session = castContext.sessionManager.currentCastSession
        if (session == null) {
            viewModelScope.launch { showToast("Connect to a Cast device first.") }
            return
        }
        castTrackId(trackId, playback.trackTitle, playback.trackArtist)
    }

    fun setCastingConnected(isConnected: Boolean, deviceName: String? = null) {
        if (isConnected) {
            if (state.value.playback.isCasting) {
                _state.update {
                    it.copy(
                        playback = it.playback.copy(
                            castDeviceName = deviceName
                        )
                    )
                }
                return
            }
            _state.update {
                it.copy(
                    playback = it.playback.copy(
                        isCasting = true,
                        castDeviceName = deviceName
                    )
                )
            }
            castStatusListenerRegistered = false
            resetForNewTrack()
            requestCastStatus()
        } else {
            if (!state.value.playback.isCasting) {
                return
            }
            _state.update {
                it.copy(
                    playback = it.playback.copy(
                        isCasting = false,
                        castDeviceName = null
                    )
                )
            }
            castStatusListenerRegistered = false
            resetForNewTrack()
            applyActiveTab(TabId.Top, recordHistory = true)
        }
    }

    fun stopCasting() {
        val castContext = try {
            CastContext.getSharedInstance(getApplication())
        } catch (_: Exception) {
            null
        }
        castContext?.sessionManager?.endCurrentSession(true)
        setCastingConnected(false)
    }

    fun requestCastStatus() {
        if (!state.value.castEnabled) {
            return
        }
        val castContext = runCatching {
            CastContext.getSharedInstance(getApplication())
        }.getOrNull() ?: return
        val session = castContext.sessionManager.currentCastSession ?: return
        ensureCastStatusListener(session)
        val payload = JSONObject().apply {
            put("type", "getStatus")
        }
        runCatching { session.sendMessage(CAST_COMMAND_NAMESPACE, payload.toString()) }
    }

    private fun ensureCastStatusListener(session: CastSession) {
        if (castStatusListenerRegistered) {
            return
        }
        session.setMessageReceivedCallbacks(CAST_COMMAND_NAMESPACE, Cast.MessageReceivedCallback { _, _, message ->
            handleCastStatusMessage(message)
        })
        castStatusListenerRegistered = true
    }

    private fun handleCastStatusMessage(message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return
        if (json.optString("type") != "status") {
            return
        }
        val songId = json.optString("songId", "")
        val title = json.optString("title", "")
        val artist = json.optString("artist", "")
        val isPlaying = json.optBoolean("isPlaying", false)
        val displayTitle = if (artist.isBlank()) {
            if (title.isBlank()) "" else title
        } else {
            "${if (title.isBlank()) "Unknown" else title} — $artist"
        }
        _state.update {
            it.copy(
                playback = it.playback.copy(
                    isRunning = isPlaying,
                    playTitle = displayTitle,
                    trackTitle = if (title.isBlank()) null else title,
                    trackArtist = if (artist.isBlank()) null else artist,
                    lastYouTubeId = if (songId.isBlank()) it.playback.lastYouTubeId else songId
                )
            )
        }
    }

    private fun castTrackId(trackId: String, title: String? = null, artist: String? = null) {
        if (!state.value.castEnabled) {
            notifyCastUnavailable()
            return
        }
        val baseUrl = state.value.baseUrl.trim()
        if (baseUrl.isBlank()) return
        val castContext = try {
            CastContext.getSharedInstance(getApplication())
        } catch (_: Exception) {
            return
        }
        val session = castContext.sessionManager.currentCastSession ?: return
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val customData = JSONObject().apply {
            put("baseUrl", normalizedBaseUrl)
            put("songId", trackId)
        }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            title?.let { putString(MediaMetadata.KEY_TITLE, it) }
            artist?.let { putString(MediaMetadata.KEY_ARTIST, it) }
        }
        val displayTitle = if (artist.isNullOrBlank()) {
            title?.takeIf { it.isNotBlank() } ?: "Unknown"
        } else {
            "${title?.takeIf { it.isNotBlank() } ?: "Unknown"} — $artist"
        }
        _state.update {
            it.copy(
                playback = it.playback.copy(
                    playTitle = displayTitle,
                    trackTitle = title,
                    trackArtist = artist,
                    isRunning = true,
                    listenTime = "00:00:00",
                    beatsPlayed = 0
                )
            )
        }
        val mediaInfo = MediaInfo.Builder("foreverjukebox://cast/$trackId")
            .setStreamType(MediaInfo.STREAM_TYPE_NONE)
            .setContentType("application/json")
            .setMetadata(metadata)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCustomData(customData)
            .build()
        session.remoteMediaClient?.load(request)
    }

    private fun sendCastCommand(command: String): Boolean {
        if (!state.value.castEnabled) {
            notifyCastUnavailable()
            return false
        }
        val castContext = try {
            CastContext.getSharedInstance(getApplication())
        } catch (_: Exception) {
            return false
        }
        val session = castContext.sessionManager.currentCastSession ?: return false
        val payload = JSONObject().apply {
            put("type", command)
        }
        return try {
            session.sendMessage(CAST_COMMAND_NAMESPACE, payload.toString())
            true
        } catch (_: Exception) {
            false
        }
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
        resetForNewTrack()
        loadTrackByYoutubeId(youtubeId, title, artist)
    }

    suspend fun deleteCurrentJob(): Boolean {
        val jobId = lastJobId ?: return false
        val baseUrl = state.value.baseUrl
        val youtubeId = state.value.playback.lastYouTubeId
        if (baseUrl.isBlank()) return false
        return try {
            api.deleteJob(baseUrl, jobId)
            if (youtubeId != null) {
                clearCachedTrack(youtubeId)
                updateFavorites(state.value.favorites.filterNot { it.uniqueSongId == youtubeId })
            }
            resetForNewTrack()
            _state.update { it.copy(activeTab = TabId.Top, topSongsTab = TopSongsTab.TopSongs) }
            tabHistory.removeLastOrNull()?.let { last ->
                if (last != TabId.Play) {
                    tabHistory.addLast(last)
                }
            }
            true
        } catch (_: Exception) {
            updatePlaybackState { it.copy(deleteEligible = false) }
            deleteEligibilityJobId = jobId
            false
        }
    }

    fun deleteSelectedEdge() = Unit

    fun prepareForExit() {
        resetForNewTrack()
        engine.clearAnalysis()
        controller.player.clear()
        controller.setTrackMeta(null, null)
        _state.update { it.copy(activeTab = TabId.Top, topSongsTab = TopSongsTab.TopSongs) }
    }

    fun selectBeat(index: Int) {
        val data = state.value.playback.vizData ?: return
        if (index < 0 || index >= data.beats.size) return
        val beat = data.beats[index]
        controller.player.seek(beat.start)
        _state.update { it.copy(playback = it.playback.copy(currentBeatIndex = index)) }
    }

    fun setActiveVisualization(index: Int) {
        _state.update { it.copy(playback = it.playback.copy(activeVizIndex = index)) }
        viewModelScope.launch {
            preferences.setActiveVizIndex(index)
        }
    }

    fun applyTuning(
        threshold: Int,
        minProb: Double,
        maxProb: Double,
        ramp: Double,
        addLastEdge: Boolean,
        justBackwards: Boolean,
        justLongBranches: Boolean,
        removeSequentialBranches: Boolean
    ) {
        viewModelScope.launch {
            val vizData = withContext(Dispatchers.Default) {
                val current = engine.getConfig()
                val nextConfig = current.copy(
                    currentThreshold = threshold,
                    minRandomBranchChance = minProb,
                    maxRandomBranchChance = maxProb,
                    randomBranchChanceDelta = ramp,
                    addLastEdge = addLastEdge,
                    justBackwards = justBackwards,
                    justLongBranches = justLongBranches,
                    removeSequentialBranches = removeSequentialBranches
                )
                engine.updateConfig(nextConfig)
                engine.rebuildGraph()
                engine.getVisualizationData()
            }
            _state.update { it.copy(playback = it.playback.copy(vizData = vizData)) }
            syncTuningState()
        }
    }

    fun handleDeepLink(uri: Uri?) {
        if (uri == null) return
        val base = state.value.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return
        val baseUri = runCatching { Uri.parse(base) }.getOrNull() ?: return
        if (uri.scheme != baseUri.scheme || uri.host != baseUri.host) return
        if (baseUri.port != -1 && uri.port != baseUri.port) return
        val segments = uri.pathSegments
        if (segments.size >= 2 && segments.firstOrNull() == "listen") {
            val id = segments[1]
            loadTrackByYoutubeId(id)
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val sizeBytes = cacheDir().walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
            _state.update { it.copy(cacheSizeBytes = sizeBytes) }
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = cacheDir()
            dir.listFiles()?.forEach { it.deleteRecursively() }
            val sizeBytes = cacheDir().walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
            _state.update { it.copy(cacheSizeBytes = sizeBytes) }
        }
    }

    fun openListenTab() {
        applyActiveTab(TabId.Play, recordHistory = true)
    }

    private suspend fun pollAnalysis(jobId: String) {
        val baseUrl = state.value.baseUrl
        val intervalMs = 2000L
        while (currentCoroutineContext().isActive) {
            val response = api.getAnalysis(baseUrl, jobId)
            updateDeleteEligibility(response)
            when (response.status) {
                "failed" -> {
                    if (response.errorCode == "analysis_missing" && response.id != null) {
                        try {
                            api.repairJob(baseUrl, response.id)
                            delay(intervalMs)
                            continue
                        } catch (_: Exception) {
                            // Fall through to error handling.
                        }
                    }
                    setAnalysisError(response.error ?: "Loading failed.")
                    return
                }
                "downloading", "queued", "processing" -> {
                    val progress = response.progress?.roundToInt()
                    setAnalysisProgress(progress, response.message)
                    if (response.status != "downloading" &&
                        !state.value.playback.audioLoaded &&
                        !audioLoadInFlight
                    ) {
                        audioLoadInFlight = true
                        viewModelScope.launch {
                            try {
                                loadAudioFromJob(jobId)
                            } catch (_: Exception) {
                                audioLoadInFlight = false
                            }
                        }
                    }
                }
                "complete" -> {
                    if (!state.value.playback.audioLoaded) {
                        val loaded = loadAudioFromJob(jobId)
                        if (!loaded) {
                            delay(intervalMs)
                            continue
                        }
                    }
                    if (applyAnalysisResult(response)) {
                        return
                    }
                }
            }
            delay(intervalMs)
        }
    }

    private suspend fun loadAudioFromJob(jobId: String): Boolean {
        val baseUrl = state.value.baseUrl
        setAudioLoading(true)
        setAnalysisProgress(0, "Loading audio")
        try {
            val youtubeId = state.value.playback.lastYouTubeId
            val target = audioFile(youtubeId ?: jobId)
            api.fetchAudioToFile(baseUrl, jobId, target)
            withContext(Dispatchers.Default) {
                controller.player.loadFile(target) { percent ->
                    viewModelScope.launch(Dispatchers.Main) {
                        setDecodeProgress(percent)
                    }
                }
            }
            audioLoadInFlight = false
            updatePlaybackState { it.copy(audioLoaded = true, audioLoading = false) }
            return true
        } catch (err: IOException) {
            audioLoadInFlight = false
            updatePlaybackState { it.copy(audioLoading = false) }
            if (err.message?.contains("HTTP 404") == true) {
                try {
                    api.repairJob(baseUrl, jobId)
                } catch (_: Exception) {
                    // Ignore repair failures; poll loop will surface errors.
                }
                return false
            }
            throw err
        }
    }

    private suspend fun applyAnalysisResult(response: AnalysisResponse): Boolean {
        val result = response.result ?: return false
        updateDeleteEligibility(response)
        setAnalysisCalculating()
        val vizData = withContext(Dispatchers.Default) {
            engine.loadAnalysis(result)
            engine.getVisualizationData()
        }
        syncTuningState()
        val rootObj = result.jsonObject
        val trackElement = rootObj["track"] ?: rootObj["analysis"]?.jsonObject?.get("track")
        val trackMeta = trackElement?.let { json.decodeFromJsonElement(TrackMetaJson.serializer(), it) }
        val title = trackMeta?.title
        val artist = trackMeta?.artist
        val durationSeconds = trackMeta?.duration
        val playTitle = when {
            !title.isNullOrBlank() && !artist.isNullOrBlank() -> "$title — $artist"
            !title.isNullOrBlank() -> title
            else -> ""
        }
        controller.setTrackMeta(title, artist)
        _state.update {
            it.copy(
                playback = it.playback.copy(
                    analysisLoaded = true,
                    vizData = vizData,
                    playTitle = playTitle,
                    trackDurationSeconds = durationSeconds,
                    trackTitle = title,
                    trackArtist = artist,
                    analysisProgress = null,
                    analysisMessage = null,
                    analysisErrorMessage = null,
                    analysisInFlight = false,
                    analysisCalculating = false,
                    audioLoading = false
                )
            )
        }
        applyActiveTab(TabId.Play, recordHistory = true)
        val jobId = response.id ?: lastJobId
        if (jobId != null) {
            recordPlay(jobId)
        }
        val youtubeId = state.value.playback.lastYouTubeId
        if (youtubeId != null) {
            cacheAnalysis(youtubeId, response)
        }
        ForegroundPlaybackService.update(getApplication())
        return true
    }

    private fun recordPlay(jobId: String) {
        if (lastPlayCountedJobId == jobId) return
        lastPlayCountedJobId = jobId
        viewModelScope.launch {
            try {
                api.postPlay(state.value.baseUrl, jobId)
            } catch (_: Exception) {
                lastPlayCountedJobId = null
            }
        }
    }

    private fun maybeUpdateNotification() {
        if (!controller.isPlaying()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationUpdateMs < 500L) return
        lastNotificationUpdateMs = now
        ForegroundPlaybackService.update(getApplication())
    }

    private fun resetForNewTrack() {
        engine.clearDeletedEdges()
        audioLoadInFlight = false
        engine.updateConfig(defaultConfig)
        controller.stopPlayback()
        controller.resetTimers()
        controller.setTrackMeta(null, null)
        ForegroundPlaybackService.stop(getApplication())
        stopListenTimer()
        _state.update {
            it.copy(
                playback = it.playback.copy(
                    audioLoaded = false,
                    analysisLoaded = false,
                    beatsPlayed = 0,
                    listenTime = "00:00:00",
                    trackDurationSeconds = null,
                    trackTitle = null,
                    trackArtist = null,
                    isRunning = false,
                    vizData = null,
                    currentBeatIndex = -1,
                    jumpLine = null,
                    playTitle = "",
                    lastYouTubeId = null,
                    lastJobId = null,
                    deleteEligible = false,
                    analysisProgress = null,
                    analysisMessage = null,
                    analysisErrorMessage = null,
                    analysisInFlight = false,
                    analysisCalculating = false,
                    audioLoading = false,
                    isCasting = it.playback.isCasting,
                    castDeviceName = it.playback.castDeviceName
                ),
                search = it.search.copy(
                    pendingTrackName = null,
                    pendingTrackArtist = null,
                    spotifyLoading = false,
                    youtubeLoading = false
                )
            )
        }
        engine.stopJukebox()
        val emptyViz = VisualizationData(beats = emptyList(), edges = mutableListOf())
        _state.update { it.copy(playback = it.playback.copy(vizData = emptyViz)) }
        setLastJobId(null)
        lastPlayCountedJobId = null
        deleteEligibilityJobId = null
        pollJob?.cancel()
        pollJob = null
        syncTuningState()
    }

    private fun updateListenTimeDisplay() {
        val durationSeconds = controller.player.getDurationSeconds()
        if (durationSeconds != null && controller.player.getCurrentTime() >= durationSeconds - END_EPSILON_SECONDS) {
            controller.stopPlayback()
            stopListenTimer()
            updatePlaybackState { it.copy(isRunning = false) }
            return
        }
        val totalSeconds = controller.getListenTimeSeconds()
        updatePlaybackState {
            it.copy(
                listenTime = formatDuration(totalSeconds),
                isRunning = controller.isPlaying()
            )
        }
    }

    private fun restorePlaybackState() {
        val vizData = engine.getVisualizationData()
        val audioDuration = controller.player.getDurationSeconds()
        val hasAnalysis = vizData != null
        val hasAudio = audioDuration != null
        if (!hasAnalysis && !hasAudio) return
        val title = controller.getTrackTitle()
        val artist = controller.getTrackArtist()
        val playTitle = when {
            !title.isNullOrBlank() && !artist.isNullOrBlank() -> "$title — $artist"
            !title.isNullOrBlank() -> title
            else -> ""
        }
        val currentTime = controller.player.getCurrentTime()
        val beatIndex = if (hasAnalysis) engine.getBeatAtTime(currentTime)?.which ?: -1 else -1
        _state.update {
            it.copy(
                playback = it.playback.copy(
                    audioLoaded = hasAudio,
                    analysisLoaded = hasAnalysis,
                    vizData = vizData,
                    playTitle = playTitle,
                    trackDurationSeconds = audioDuration,
                    trackTitle = title,
                    trackArtist = artist,
                    currentBeatIndex = beatIndex,
                    isRunning = controller.isPlaying()
                ),
                activeTab = if (hasAnalysis) TabId.Play else it.activeTab
            )
        }
        if (controller.isPlaying()) {
            startListenTimer()
        }
    }

    private fun syncTuningState() {
        val config = engine.getConfig()
        val graph = engine.getGraphState()
        val thresholdValue =
            if (config.currentThreshold == 0 && graph != null) {
                graph.currentThreshold
            } else {
                config.currentThreshold
            }
        _state.update {
            it.copy(
                tuning = it.tuning.copy(
                    threshold = thresholdValue,
                    minProb = (config.minRandomBranchChance * 100).toInt(),
                    maxProb = (config.maxRandomBranchChance * 100).toInt(),
                    ramp = (config.randomBranchChanceDelta * 100).toInt(),
                    addLastEdge = config.addLastEdge,
                    justBackwards = config.justBackwards,
                    justLong = config.justLongBranches,
                    removeSequential = config.removeSequentialBranches
                )
            )
        }
    }

    private fun updateFavorites(favorites: List<FavoriteTrack>, sync: Boolean = true) {
        val previous = state.value.favorites
        val sorted = sortFavorites(favorites).take(MAX_FAVORITES)
        _state.update { it.copy(favorites = sorted) }
        viewModelScope.launch {
            preferences.setFavorites(sorted)
        }
        if (!sync) {
            return
        }
        val delta = computeFavoritesDelta(previous, sorted)
        if (delta.added.isEmpty() && delta.removedIds.isEmpty()) {
            return
        }
        scheduleFavoritesSync(delta)
    }

    private fun scheduleFavoritesSync(delta: FavoritesDelta) {
        if (!state.value.allowFavoritesSync) {
            return
        }
        val code = state.value.favoritesSyncCode
        if (code.isNullOrBlank()) {
            return
        }
        if (syncUpdateInFlight) {
            pendingSyncDelta = delta
            return
        }
        viewModelScope.launch {
            syncFavoritesToBackend(delta)
        }
    }

    private suspend fun syncFavoritesToBackend(delta: FavoritesDelta) {
        syncUpdateInFlight = true
        try {
            if (!state.value.allowFavoritesSync) {
                return
            }
            val baseUrl = state.value.baseUrl
            val code = state.value.favoritesSyncCode
            if (baseUrl.isBlank() || code.isNullOrBlank()) {
                return
            }
            val serverFavorites = fetchFavoritesFromSync(code) ?: return
            val merged = applyFavoritesDelta(serverFavorites, delta)
            val response = api.updateFavoritesSync(baseUrl, code, merged)
            val normalized = normalizeFavorites(response.favorites)
            if (normalized.isNotEmpty()) {
                updateFavorites(normalized, sync = false)
            }
        } catch (_: Exception) {
            showToast("Favorites sync failed.")
        } finally {
            syncUpdateInFlight = false
            pendingSyncDelta?.let {
                pendingSyncDelta = null
                scheduleFavoritesSync(it)
            }
        }
    }

    private fun computeFavoritesDelta(
        previous: List<FavoriteTrack>,
        next: List<FavoriteTrack>
    ): FavoritesDelta {
        val prevMap = previous.associateBy { it.uniqueSongId }
        val nextMap = next.associateBy { it.uniqueSongId }
        val added = nextMap.filterKeys { it !in prevMap }.values.toList()
        val removedIds = prevMap.keys.filter { it !in nextMap }.toSet()
        return FavoritesDelta(added = added, removedIds = removedIds)
    }

    private fun applyFavoritesDelta(
        serverFavorites: List<FavoriteTrack>,
        delta: FavoritesDelta
    ): List<FavoriteTrack> {
        val filtered = serverFavorites.filter { it.uniqueSongId !in delta.removedIds }
        val existing = filtered.map { it.uniqueSongId }.toHashSet()
        val merged = filtered.toMutableList()
        delta.added.forEach { favorite ->
            if (existing.add(favorite.uniqueSongId)) {
                merged.add(favorite)
            }
        }
        return sortFavorites(merged).take(MAX_FAVORITES)
    }

    private fun normalizeFavorites(items: List<FavoriteTrack>): List<FavoriteTrack> {
        val normalized = items.mapNotNull { item ->
            val id = item.uniqueSongId
            if (id.isBlank()) return@mapNotNull null
            val title = item.title.ifBlank { "Untitled" }
            val artist = item.artist
            FavoriteTrack(
                uniqueSongId = id,
                title = title,
                artist = artist,
                duration = item.duration,
                sourceType = item.sourceType
            )
        }
        return sortFavorites(normalized).take(MAX_FAVORITES)
    }

    private suspend fun fetchFavoritesFromSync(codeOverride: String? = null): List<FavoriteTrack>? {
        if (!state.value.allowFavoritesSync) {
            return null
        }
        val baseUrl = state.value.baseUrl
        val code = codeOverride ?: state.value.favoritesSyncCode
        if (baseUrl.isBlank() || code.isNullOrBlank()) {
            return null
        }
        return try {
            api.fetchFavoritesSync(baseUrl, code.trim())
        } catch (_: Exception) {
            null
        }
    }

    private fun maybeHydrateFavoritesFromSync() {
        val code = state.value.favoritesSyncCode
        val baseUrl = state.value.baseUrl
        if (code.isNullOrBlank() || baseUrl.isBlank() || !state.value.allowFavoritesSync) {
            return
        }
        if (favoritesSyncHydratedFor == code) {
            return
        }
        favoritesSyncHydratedFor = code
        viewModelScope.launch {
            val favorites = fetchFavoritesFromSync(code)
            if (favorites == null) {
                favoritesSyncHydratedFor = null
                showToast("Favorites sync failed.")
                return@launch
            }
            updateFavorites(favorites, sync = false)
        }
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(getApplication(), message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun sortFavorites(items: List<FavoriteTrack>): List<FavoriteTrack> {
        val deduped = items.distinctBy { it.uniqueSongId }
        return deduped.sortedWith(
            compareBy<FavoriteTrack, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist }
        )
    }

    companion object {
        private const val MAX_FAVORITES = 100
        private const val CAST_COMMAND_NAMESPACE = "urn:x-cast:com.foreverjukebox.app"
    }
}

private data class FavoritesDelta(
    val added: List<FavoriteTrack>,
    val removedIds: Set<String>
)

private const val END_EPSILON_SECONDS = 0.02
