package com.foreverjukebox.app.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.foreverjukebox.app.data.ApiClient
import com.foreverjukebox.app.data.AppPreferences
import com.foreverjukebox.app.data.SpotifySearchItem
import com.foreverjukebox.app.data.ThemeMode
import com.foreverjukebox.app.engine.VisualizationData
import com.foreverjukebox.app.playback.ForegroundPlaybackService
import com.foreverjukebox.app.playback.PlaybackControllerHolder
import com.foreverjukebox.app.visualization.JumpLine
import com.foreverjukebox.app.visualization.visualizationCount
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.coroutines.coroutineContext

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

    init {
        viewModelScope.launch {
            preferences.baseUrl.collect { url ->
                _state.update { current ->
                    current.copy(
                        baseUrl = url.orEmpty(),
                        showBaseUrlPrompt = url.isNullOrBlank()
                    )
                }
                if (!url.isNullOrBlank() && state.value.activeTab == TabId.Top) {
                    refreshTopSongs()
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
        _state.update { it.copy(activeTab = tabId) }
        if (tabId == TabId.Top) {
            scheduleTopSongsRefresh()
        }
        if (tabId != TabId.Play) {
            _state.update { it.copy(playback = it.playback.copy()) }
        }
    }

    private fun scheduleTopSongsRefresh() {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        refreshTopSongsJob?.cancel()
        refreshTopSongsJob = viewModelScope.launch {
            delay(250)
            refreshTopSongs()
        }
    }

    private fun updateSearchState(transform: (SearchState) -> SearchState) {
        _state.update { it.copy(search = transform(it.search)) }
    }

    private fun updatePlaybackState(transform: (PlaybackState) -> PlaybackState) {
        _state.update { it.copy(playback = transform(it.playback)) }
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

    private fun setAnalysisIdle() {
        applyLoadingEvent(LoadingEvent.Reset)
    }

    private fun setAnalysisQueued(progress: Int?, message: String? = null) {
        applyLoadingEvent(LoadingEvent.AnalysisQueued(progress, message))
    }

    private fun setAnalysisProgress(progress: Int?, message: String? = null) {
        applyLoadingEvent(LoadingEvent.AnalysisProgress(progress, message))
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
                    val response = api.getJobByTrack(baseUrl, name, artist)
                    val jobId = response.id
                    val youtubeId = response.youtubeId
                    if (jobId != null && youtubeId != null && response.status != "failed") {
                        loadExistingJob(jobId, youtubeId, response)
                        return@launch
                    }
                } catch (_: Exception) {
                    // Fall back to YouTube matches.
                }
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
        resetForNewTrack()
        _state.update {
            it.copy(
                search = it.search.copy(
                    spotifyResults = emptyList(),
                    youtubeMatches = emptyList()
                ),
                playback = it.playback.copy(
                    audioLoading = false,
                    lastYouTubeId = youtubeId
                ),
                activeTab = TabId.Play
            )
        }
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
                lastJobId = response.id
                startPoll(response.id)
            } catch (err: Exception) {
                setAnalysisError("Loading failed.")
            }
        }
    }

    fun loadTrackByYoutubeId(youtubeId: String) {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        resetForNewTrack()
        _state.update {
            it.copy(
                playback = it.playback.copy(
                    audioLoading = false,
                    lastYouTubeId = youtubeId
                ),
                activeTab = TabId.Play
            )
        }
        viewModelScope.launch {
            if (tryLoadCachedTrack(youtubeId)) {
                return@launch
            }
            setAnalysisQueued(null, "Fetching audio...")
            try {
                val response = api.getJobByYoutube(baseUrl, youtubeId)
                if (response.id == null) {
                    setAnalysisError("Loading failed.")
                    return@launch
                }
                lastJobId = response.id
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
        response: com.foreverjukebox.app.data.AnalysisResponse
    ) {
        resetForNewTrack()
        _state.update {
            it.copy(
                search = it.search.copy(
                    spotifyResults = emptyList(),
                    youtubeMatches = emptyList()
                ),
                playback = it.playback.copy(lastYouTubeId = youtubeId),
                activeTab = TabId.Play
            )
        }
        setAnalysisQueued(null, response.message)
        lastJobId = jobId
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

    private fun jobFile(youtubeId: String): File = File(cacheDir(), "$youtubeId.job")

    private suspend fun tryLoadCachedTrack(youtubeId: String): Boolean {
        setAnalysisCalculating()
        setAudioLoading(true)
        return withContext(Dispatchers.IO) {
            val analysisPath = analysisFile(youtubeId)
            val audioPath = audioFile(youtubeId)
            if (!analysisPath.exists() || !audioPath.exists()) {
                return@withContext false
            }
            val analysisText = analysisPath.readText()
            val analysis = json.parseToJsonElement(analysisText)
            val audioBytes = audioPath.readBytes()
            val cachedJobId = jobFile(youtubeId).takeIf { it.exists() }?.readText()
            try {
                withContext(Dispatchers.Default) {
                    controller.player.loadBytes(audioBytes, cachedJobId ?: youtubeId)
                }
            } catch (err: OutOfMemoryError) {
                audioPath.delete()
                return@withContext false
            }
            audioLoadInFlight = false
            updatePlaybackState { it.copy(audioLoaded = true, audioLoading = false) }
            lastJobId = cachedJobId
            val response = com.foreverjukebox.app.data.AnalysisResponse(
                id = cachedJobId,
                status = "complete",
                result = analysis
            )
            applyAnalysisResult(response)
        }
    }

    private fun cacheAudio(youtubeId: String, jobId: String?, bytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            ignoreFailures {
                audioFile(youtubeId).writeBytes(bytes)
                if (!jobId.isNullOrBlank()) {
                    jobFile(youtubeId).writeText(jobId)
                }
            }
        }
    }

    private fun cacheAnalysis(youtubeId: String, jobId: String?, analysis: JsonElement) {
        viewModelScope.launch(Dispatchers.IO) {
            ignoreFailures {
                val payload = json.encodeToString(JsonElement.serializer(), analysis)
                analysisFile(youtubeId).writeText(payload)
                if (!jobId.isNullOrBlank()) {
                    jobFile(youtubeId).writeText(jobId)
                }
            }
        }
    }

    fun togglePlayback() {
        val current = state.value.playback
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
                setAnalysisError("Loading failed.")
            }
        } else {
            controller.stopPlayback()
            stopListenTimer()
            updateListenTimeDisplay()
            _state.update { it.copy(playback = it.playback.copy(isRunning = false)) }
            ForegroundPlaybackService.stop(getApplication())
        }
    }

    fun deleteSelectedEdge() = Unit

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
        if (uri.scheme == "foreverjukebox" && uri.host == "listen") {
            val id = uri.pathSegments.firstOrNull() ?: return
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
        _state.update { it.copy(activeTab = TabId.Play) }
    }

    private suspend fun pollAnalysis(jobId: String) {
        val baseUrl = state.value.baseUrl
        val intervalMs = 2000L
        while (coroutineContext.isActive) {
            val response = api.getAnalysis(baseUrl, jobId)
            when (response.status) {
                "failed" -> {
                    if (response.error == "Analysis missing") {
                        try {
                            api.repairJob(baseUrl, jobId)
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
                        try {
                            loadAudioFromJob(jobId)
                        } catch (_: Exception) {
                            audioLoadInFlight = false
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
        try {
            val bytes = api.fetchAudioBytes(baseUrl, jobId)
            withContext(Dispatchers.Default) {
                controller.player.loadBytes(bytes, jobId)
            }
            audioLoadInFlight = false
            updatePlaybackState { it.copy(audioLoaded = true, audioLoading = false) }
            val youtubeId = state.value.playback.lastYouTubeId
            if (youtubeId != null) {
                cacheAudio(youtubeId, jobId, bytes)
            }
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

    private suspend fun applyAnalysisResult(response: com.foreverjukebox.app.data.AnalysisResponse): Boolean {
        val result = response.result ?: return false
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
                    analysisProgress = null,
                    analysisMessage = null,
                    analysisErrorMessage = null,
                    analysisInFlight = false,
                    analysisCalculating = false,
                    audioLoading = false
                ),
                activeTab = TabId.Play
            )
        }
        val jobId = response.id ?: lastJobId
        if (jobId != null) {
            recordPlay(jobId)
        }
        val youtubeId = state.value.playback.lastYouTubeId
        if (youtubeId != null) {
            cacheAnalysis(youtubeId, jobId, result)
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
                    isRunning = false,
                    vizData = null,
                    currentBeatIndex = -1,
                    jumpLine = null,
                    playTitle = "",
                    lastYouTubeId = null,
                    analysisProgress = null,
                    analysisMessage = null,
                    analysisErrorMessage = null,
                    analysisInFlight = false,
                    analysisCalculating = false,
                    audioLoading = false
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
        lastJobId = null
        lastPlayCountedJobId = null
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
}

private const val END_EPSILON_SECONDS = 0.02
