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
import com.foreverjukebox.app.data.TopSongItem
import com.foreverjukebox.app.data.YoutubeSearchItem
import com.foreverjukebox.app.engine.Edge
import com.foreverjukebox.app.engine.JukeboxConfig
import com.foreverjukebox.app.engine.VisualizationData
import com.foreverjukebox.app.playback.ForegroundPlaybackService
import com.foreverjukebox.app.playback.PlaybackControllerHolder
import com.foreverjukebox.app.visualization.JumpLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

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
    val topSongs: List<TopSongItem> = emptyList(),
    val topSongsLoading: Boolean = false,
    val searchResults: List<SpotifySearchItem> = emptyList(),
    val searchLoading: Boolean = false,
    val youtubeMatches: List<YoutubeSearchItem> = emptyList(),
    val youtubeLoading: Boolean = false,
    val analysisProgress: Int? = null,
    val analysisInFlight: Boolean = false,
    val playTitle: String = "",
    val audioLoaded: Boolean = false,
    val analysisLoaded: Boolean = false,
    val isRunning: Boolean = false,
    val beatsPlayed: Int = 0,
    val listenTime: String = "00:00:00",
    val trackDurationSeconds: Double? = null,
    val vizData: VisualizationData? = null,
    val selectedEdge: Edge? = null,
    val activeVizIndex: Int = 0,
    val currentBeatIndex: Int = -1,
    val lastJumpFromIndex: Int? = null,
    val jumpLine: JumpLine? = null,
    val lastYouTubeId: String? = null,
    val pendingTrackName: String? = null,
    val pendingTrackArtist: String? = null,
    val tuningThreshold: Int = 0,
    val tuningMinProb: Int = 18,
    val tuningMaxProb: Int = 50,
    val tuningRamp: Int = 10,
    val tuningAddLastEdge: Boolean = true,
    val tuningJustBackwards: Boolean = false,
    val tuningJustLong: Boolean = false,
    val tuningRemoveSequential: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val api = ApiClient()
    private val controller = PlaybackControllerHolder.get(application)
    private val engine = controller.engine
    private val defaultConfig = engine.getConfig()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var listenTimerJob: Job? = null
    private var refreshTopSongsJob: Job? = null
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
                    beatsPlayed = engineState.beatsPlayed,
                    currentBeatIndex = currentBeatIndex,
                    lastJumpFromIndex = lastJumpFrom,
                    jumpLine = jumpLine
                )
            }
        }

        listenTimerJob = viewModelScope.launch {
            while (true) {
                updateListenTimeDisplay()
                delay(200)
            }
        }

        restorePlaybackState()
    }

    override fun onCleared() {
        super.onCleared()
        listenTimerJob?.cancel()
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
            _state.update { it.copy(selectedEdge = null) }
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

    fun refreshTopSongs() {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(topSongsLoading = true) }
            try {
                val items = api.fetchTopSongs(baseUrl)
                _state.update { it.copy(topSongs = items) }
            } catch (err: Exception) {
                _state.update { it.copy(topSongs = emptyList()) }
            } finally {
                _state.update { it.copy(topSongsLoading = false) }
            }
        }
    }

    fun runSpotifySearch(query: String) {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        _state.update {
            it.copy(
                youtubeMatches = emptyList(),
                searchResults = emptyList(),
                searchLoading = true
            )
        }
        viewModelScope.launch {
            try {
                val items = api.searchSpotify(baseUrl, query).take(10)
                _state.update { it.copy(searchResults = items) }
            } catch (_: Exception) {
                _state.update { it.copy(searchResults = emptyList()) }
            } finally {
                _state.update { it.copy(searchLoading = false) }
            }
        }
    }

    fun fetchYoutubeMatches(name: String, artist: String, duration: Double) {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        val query = if (artist.isNotBlank()) "$artist - $name" else name
        _state.update {
            it.copy(
                pendingTrackName = name,
                pendingTrackArtist = artist,
                searchResults = emptyList(),
                youtubeMatches = emptyList(),
                youtubeLoading = true
            )
        }
        viewModelScope.launch {
            try {
                val items = api.searchYoutube(baseUrl, query, duration).take(10)
                _state.update { it.copy(youtubeMatches = items) }
            } catch (_: Exception) {
                _state.update { it.copy(youtubeMatches = emptyList()) }
            } finally {
                _state.update { it.copy(youtubeLoading = false) }
            }
        }
    }

    fun startYoutubeAnalysis(youtubeId: String, title: String? = null, artist: String? = null) {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        val resolvedTitle = title ?: state.value.pendingTrackName.orEmpty()
        val resolvedArtist = artist ?: state.value.pendingTrackArtist.orEmpty()
        resetForNewTrack()
        _state.update {
            it.copy(
                analysisProgress = 0,
                analysisInFlight = true,
                searchResults = emptyList(),
                youtubeMatches = emptyList(),
                activeTab = TabId.Play,
                lastYouTubeId = youtubeId
            )
        }
        viewModelScope.launch {
            try {
                val response = api.startYoutubeAnalysis(baseUrl, youtubeId, resolvedTitle, resolvedArtist)
                if (response.id == null) {
                    throw IllegalStateException("Invalid job response")
                }
                lastJobId = response.id
                pollAnalysis(response.id)
            } catch (err: Exception) {
                _state.update { it.copy(analysisProgress = null, analysisInFlight = false) }
            }
        }
    }

    fun loadTrackByYoutubeId(youtubeId: String) {
        val baseUrl = state.value.baseUrl
        if (baseUrl.isBlank()) return
        resetForNewTrack()
        _state.update {
            it.copy(
                analysisProgress = 0,
                analysisInFlight = true,
                activeTab = TabId.Play,
                lastYouTubeId = youtubeId
            )
        }
        viewModelScope.launch {
            try {
                val response = api.getJobByYoutube(baseUrl, youtubeId)
                if (response.id == null) {
                    _state.update { it.copy(analysisProgress = null, analysisInFlight = false) }
                    return@launch
                }
                lastJobId = response.id
                if (response.status == "complete" && response.result != null) {
                    if (!state.value.audioLoaded) {
                        loadAudioFromJob(response.id)
                    }
                    if (applyAnalysisResult(response)) {
                        return@launch
                    }
                    return@launch
                }
                pollAnalysis(response.id)
            } catch (err: Exception) {
                _state.update { it.copy(analysisProgress = null, analysisInFlight = false) }
            }
        }
    }

    fun togglePlayback() {
        val current = state.value
        if (!current.audioLoaded || !current.analysisLoaded) return
        if (!current.isRunning) {
            try {
                val running = controller.togglePlayback()
                updateListenTimeDisplay()
                _state.update {
                    it.copy(
                        beatsPlayed = 0,
                        currentBeatIndex = -1,
                        selectedEdge = null,
                        isRunning = running
                    )
                }
                if (running) {
                    ForegroundPlaybackService.start(getApplication())
                }
            } catch (err: Exception) {
                _state.update { it.copy(analysisProgress = null, analysisInFlight = false) }
            }
        } else {
            controller.stopPlayback()
            _state.update { it.copy(isRunning = false) }
            ForegroundPlaybackService.stop(getApplication())
        }
    }

    fun deleteSelectedEdge() {
        val edge = state.value.selectedEdge ?: return
        if (edge.deleted) return
        viewModelScope.launch {
            val vizData = withContext(Dispatchers.Default) {
                engine.deleteEdge(edge)
                engine.rebuildGraph()
                engine.getVisualizationData()
            }
            _state.update { it.copy(vizData = vizData, selectedEdge = null) }
        }
    }

    fun selectBeat(index: Int) {
        val data = state.value.vizData ?: return
        if (index < 0 || index >= data.beats.size) return
        val beat = data.beats[index]
        controller.player.seek(beat.start)
        _state.update { it.copy(currentBeatIndex = index) }
    }

    fun selectEdge(edge: Edge?) {
        _state.update { it.copy(selectedEdge = edge) }
    }

    fun setActiveVisualization(index: Int) {
        _state.update { it.copy(activeVizIndex = index) }
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
            _state.update { it.copy(vizData = vizData) }
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

    private suspend fun pollAnalysis(jobId: String) {
        val baseUrl = state.value.baseUrl
        val intervalMs = 2000L
        while (true) {
            val response = api.getAnalysis(baseUrl, jobId)
            if (response.status == "failed") {
                _state.update { it.copy(analysisProgress = null, analysisInFlight = false) }
                throw IllegalStateException(response.error ?: "Analysis failed")
            }
            if (response.status == "downloading" || response.status == "queued" || response.status == "processing") {
                val progress = response.progress?.roundToInt()
                _state.update { it.copy(analysisProgress = progress) }
                if (response.status != "downloading" && !state.value.audioLoaded && !audioLoadInFlight) {
                    audioLoadInFlight = true
                    try {
                        loadAudioFromJob(jobId)
                    } catch (_: Exception) {
                        audioLoadInFlight = false
                    }
                }
            } else if (response.status == "complete") {
                if (!state.value.audioLoaded) {
                    loadAudioFromJob(jobId)
                }
                if (applyAnalysisResult(response)) {
                    return
                }
            }
            delay(intervalMs)
        }
    }

    private suspend fun loadAudioFromJob(jobId: String) {
        val baseUrl = state.value.baseUrl
        val bytes = api.fetchAudioBytes(baseUrl, jobId)
        withContext(Dispatchers.Default) {
            controller.player.loadBytes(bytes, jobId)
        }
        audioLoadInFlight = false
        _state.update { it.copy(audioLoaded = true) }
    }

    private suspend fun applyAnalysisResult(response: com.foreverjukebox.app.data.AnalysisResponse): Boolean {
        val result = response.result ?: return false
        val vizData = withContext(Dispatchers.Default) {
            engine.loadAnalysis(result)
            engine.getVisualizationData()
        }
        syncTuningState()
        val rootObj = result.jsonObject
        val track = rootObj["track"] ?: rootObj["analysis"]?.jsonObject?.get("track")
        val title = track?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
        val artist = track?.jsonObject?.get("artist")?.jsonPrimitive?.contentOrNull
        val durationSeconds = track?.jsonObject
            ?.get("duration")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toDoubleOrNull()
        val playTitle = when {
            !title.isNullOrBlank() && !artist.isNullOrBlank() -> "$title — $artist"
            !title.isNullOrBlank() -> title
            else -> ""
        }
        controller.setTrackMeta(title, artist)
        _state.update {
            it.copy(
                analysisLoaded = true,
                vizData = vizData,
                playTitle = playTitle,
                trackDurationSeconds = durationSeconds,
                analysisProgress = null,
                analysisInFlight = false,
                activeTab = TabId.Play
            )
        }
        val jobId = response.id ?: lastJobId
        if (jobId != null) {
            recordPlay(jobId)
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
                refreshTopSongs()
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
        _state.update {
            it.copy(
                audioLoaded = false,
                analysisLoaded = false,
                beatsPlayed = 0,
                listenTime = "00:00:00",
                trackDurationSeconds = null,
                selectedEdge = null,
                isRunning = false,
                vizData = null,
                currentBeatIndex = -1,
                jumpLine = null,
                playTitle = "",
                lastYouTubeId = null,
                pendingTrackName = null,
                pendingTrackArtist = null,
                searchLoading = false,
                youtubeLoading = false,
                analysisProgress = 0,
                analysisInFlight = false
            )
        }
        engine.stopJukebox()
        val emptyViz = VisualizationData(beats = emptyList(), edges = mutableListOf())
        _state.update { it.copy(vizData = emptyViz) }
        lastJobId = null
        lastPlayCountedJobId = null
        syncTuningState()
    }

    private fun updateListenTimeDisplay() {
        val totalSeconds = controller.getListenTimeSeconds()
        _state.update {
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
                audioLoaded = hasAudio,
                analysisLoaded = hasAnalysis,
                vizData = vizData,
                playTitle = playTitle,
                trackDurationSeconds = audioDuration,
                activeTab = if (hasAnalysis) TabId.Play else it.activeTab,
                currentBeatIndex = beatIndex,
                isRunning = controller.isPlaying()
            )
        }
    }

    private fun formatDuration(seconds: Double): String {
        val totalSeconds = seconds.toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, secs)
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
                tuningThreshold = thresholdValue,
                tuningMinProb = (config.minRandomBranchChance * 100).toInt(),
                tuningMaxProb = (config.maxRandomBranchChance * 100).toInt(),
                tuningRamp = (config.randomBranchChanceDelta * 100).toInt(),
                tuningAddLastEdge = config.addLastEdge,
                tuningJustBackwards = config.justBackwards,
                tuningJustLong = config.justLongBranches,
                tuningRemoveSequential = config.removeSequentialBranches
            )
        }
    }
}
