package com.foreverjukebox.app.ui

import android.app.Application
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.foreverjukebox.app.data.ApiClient
import com.foreverjukebox.app.data.AnalysisResponse
import com.foreverjukebox.app.data.HttpStatusException
import com.foreverjukebox.app.data.SOURCE_PROVIDER_YOUTUBE
import com.foreverjukebox.app.data.canonicalJobId
import com.foreverjukebox.app.data.sourceProviderFromRaw
import com.foreverjukebox.app.engine.JukeboxConfig
import com.foreverjukebox.app.engine.JukeboxEngine
import com.foreverjukebox.app.engine.VisualizationData
import com.foreverjukebox.app.playback.ForegroundPlaybackService
import com.foreverjukebox.app.playback.PlaybackController
import com.foreverjukebox.app.playback.loadingNotificationProgressBucket
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.math.roundToInt
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal fun isAnalysisInProgressStatus(status: String?): Boolean {
    return status == "downloading" || status == "queued" || status == "processing"
}

internal data class ResolvedLoadedTrackMeta(
    val title: String?,
    val artist: String?,
    val durationSeconds: Double?
)

internal fun resolveLoadedTrackMeta(
    backendTrackMeta: TrackMetaJson?,
    currentPlayback: PlaybackState
): ResolvedLoadedTrackMeta {
    return ResolvedLoadedTrackMeta(
        title = backendTrackMeta?.title.takeIfNotBlank()
            ?: currentPlayback.trackTitle.takeIfNotBlank(),
        artist = backendTrackMeta?.artist.takeIfNotBlank()
            ?: currentPlayback.trackArtist.takeIfNotBlank(),
        durationSeconds = backendTrackMeta?.duration
    )
}

class PlaybackCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val api: ApiClient,
    private val controller: PlaybackController,
    private val engine: JukeboxEngine,
    private val json: Json,
    private val defaultConfig: JukeboxConfig,
    private val getState: () -> UiState,
    private val updateState: ((UiState) -> UiState) -> Unit,
    private val updatePlaybackState: ((PlaybackState) -> PlaybackState) -> Unit,
    private val applyActiveTab: (TabId, Boolean) -> Unit,
    private val onStableTrackLoaded: () -> Unit = {}
) {
    private var listenTimerJob: Job? = null
    private var pollJob: Job? = null
    private var backgroundAudioLoadJob: Job? = null
    private var audioLoadInFlight = false
    private var playbackServiceSessionVisible = false
    private var lastPlaybackServiceSessionKind: PlaybackServiceSessionKind? = null
    private var lastLoadingNotificationBucket: Int? = null
    private var lastJobId: String? = null
    private var lastPlayCountedJobId: String? = null
    private var deleteEligibilityJobId: String? = null
    private var pendingTuningParams: String? = null
    private var lastNotificationUpdateMs = 0L

    fun onCleared() {
        listenTimerJob?.cancel()
        pollJob?.cancel()
        backgroundAudioLoadJob?.cancel()
        if (playbackServiceSessionVisible) {
            hardStopPlaybackServiceSession()
        }
    }

    fun hasActiveServerLoadWork(): Boolean {
        return pollJob?.isActive == true || backgroundAudioLoadJob?.isActive == true || audioLoadInFlight
    }

    fun getLastJobId(): String? = lastJobId ?: getState().playback.lastJobId

    fun setLastJobId(jobId: String?) {
        lastJobId = jobId
        updatePlaybackState { it.copy(lastJobId = jobId) }
    }

    fun setPendingTuningParams(raw: String?) {
        pendingTuningParams = if (!raw.isNullOrBlank()) {
            raw
        } else {
            null
        }
    }

    fun buildTuningParamsString(): String? {
        val config = engine.getConfig()
        val playback = getState().playback
        val params = mutableListOf<String>()
        if (config.justBackwards) {
            params.add("jb=1")
        }
        if (config.justLongBranches) {
            params.add("lg=1")
        }
        if (config.removeSequentialBranches) {
            params.add("sq=0")
        }
        if (config.currentThreshold != 0) {
            params.add("thresh=${config.currentThreshold}")
        }
        val minChanged = config.minRandomBranchChance != defaultConfig.minRandomBranchChance
        val maxChanged = config.maxRandomBranchChance != defaultConfig.maxRandomBranchChance
        val deltaChanged = config.randomBranchChanceDelta != defaultConfig.randomBranchChanceDelta
        if (minChanged || maxChanged || deltaChanged) {
            val minPct = mapValueToPercent(config.minRandomBranchChance, 1.0)
            val maxPct = mapValueToPercent(config.maxRandomBranchChance, 1.0)
            val deltaPct = mapValueToPercent(
                config.randomBranchChanceDelta,
                MAX_RANDOM_BRANCH_DELTA
            )
            params.add("bp=$minPct,$maxPct,$deltaPct")
        }
        val deletedIds = getDeletedEdgeIds()
        if (deletedIds.isNotEmpty()) {
            params.add("d=${deletedIds.joinToString(",")}")
        }
        config.preferredAnchorBeat?.let { anchorBeat ->
            params.add("ab=$anchorBeat")
        }
        if (playback.jukeboxAudioMode != JukeboxAudioMode.Off) {
            params.add("am=${playback.jukeboxAudioMode.wireValue}")
        }
        return if (params.isEmpty()) null else params.joinToString("&")
    }

    fun setAnalysisQueued(progress: Int?, message: String? = null) {
        applyLoadingEvent(LoadingEvent.AnalysisQueued(progress, message))
    }

    fun setAnalysisProgress(progress: Int?, message: String? = null) {
        val normalized = if (progress == 0 && message != "Loading audio") null else progress
        applyLoadingEvent(LoadingEvent.AnalysisProgress(normalized, message))
    }

    fun setDecodeProgress(percent: Int) {
        val current = getState().playback
        if (
            current.analysisInFlight &&
            !current.analysisMessage.isNullOrBlank() &&
            current.analysisMessage != "Loading audio"
        ) {
            return
        }
        setAnalysisProgress(percent, "Loading audio")
    }

    fun setAnalysisCalculating() {
        applyLoadingEvent(LoadingEvent.AnalysisCalculating)
    }

    fun setAnalysisError(message: String) {
        applyLoadingEvent(LoadingEvent.AnalysisError(message))
    }

    fun setAudioLoading(loading: Boolean) {
        applyLoadingEvent(LoadingEvent.AudioLoading(loading))
    }

    fun startListenTimer() {
        if (listenTimerJob?.isActive == true) return
        listenTimerJob = scope.launch {
            while (coroutineContext.isActive) {
                updateListenTimeDisplay()
                delay(200)
            }
        }
    }

    fun stopListenTimer() {
        listenTimerJob?.cancel()
        listenTimerJob = null
    }

    fun startPoll(jobId: String) {
        pollJob?.cancel()
        backgroundAudioLoadJob?.cancel()
        backgroundAudioLoadJob = null
        audioLoadInFlight = false
        pollJob = scope.launch {
            try {
                pollAnalysis(jobId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: IOException) {
                Log.e(TAG, "Polling failed for $jobId", error)
                setAnalysisError("Loading failed.")
            } catch (error: IllegalArgumentException) {
                Log.e(TAG, "Polling failed for $jobId", error)
                setAnalysisError("Loading failed.")
            } catch (error: IllegalStateException) {
                Log.e(TAG, "Polling failed for $jobId", error)
                setAnalysisError("Loading failed.")
            }
        }
    }

    suspend fun tryLoadCachedTrack(jobId: String): Boolean {
        if (!isActiveJobId(jobId)) {
            return false
        }
        val cached = withContext(Dispatchers.IO) {
            val analysisPath = analysisFile(jobId)
            val audioPath = audioFile(jobId)
            if (!hasCompleteServerTrackCache(cacheDir(), jobId)) {
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
        if (!isActiveJobId(jobId)) {
            return false
        }
        setAnalysisProgress(0, "Loading audio")
        try {
            withContext(Dispatchers.Default) {
                controller.player.loadFile(audioPath) { percent ->
                    scope.launch(Dispatchers.Main) {
                        setAnalysisProgress(percent, "Loading audio")
                    }
                }
                engine.refreshAnchorJump()
            }
        } catch (err: OutOfMemoryError) {
            Log.e(TAG, "Out of memory while loading cached track audio for $jobId", err)
            withContext(Dispatchers.IO) {
                audioFile(jobId).delete()
            }
            return false
        }
        if (!isActiveJobId(jobId)) {
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
        val resolvedJobId = canonicalJobId(response.id)
            ?: canonicalJobId(getState().playback.lastJobId)
            ?: jobId
        setLastJobId(resolvedJobId)
        applyAnalysisResult(response)
        return true
    }

    suspend fun clearCachedTrack(jobId: String) {
        withContext(Dispatchers.IO) {
            ignoreFailures { analysisFile(jobId).delete() }
            ignoreFailures { audioFile(jobId).delete() }
        }
    }

    fun updateDeleteEligibility(response: AnalysisResponse) {
        val jobId = canonicalJobId(response.id) ?: canonicalJobId(lastJobId) ?: return
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

    fun markDeleteEligibilityFailed(jobId: String) {
        updatePlaybackState { it.copy(deleteEligible = false) }
        deleteEligibilityJobId = jobId
    }

    suspend fun loadAudioFromJob(jobId: String): Boolean {
        if (!isActiveJobId(jobId)) {
            return false
        }
        return withAudioLoadWakeLock {
            loadAudioFromJobWithWakeLock(jobId)
        }
    }

    private suspend fun loadAudioFromJobWithWakeLock(jobId: String): Boolean {
        val baseUrl = getState().baseUrl
        setAudioLoading(true)
        setAnalysisProgress(0, "Loading audio")
        val target = audioFile(jobId)
        try {
            retryTransientServerLoad {
                api.fetchAudioToFile(baseUrl, jobId, target)
            }
            if (!isActiveJobId(jobId)) {
                return false
            }
            val loaded = loadServerAudioFileWithRetry(jobId, target)
            if (!loaded) return false
            if (!isActiveJobId(jobId)) {
                return false
            }
            audioLoadInFlight = false
            updatePlaybackState { it.copy(audioLoaded = true, audioLoading = false) }
            syncPlaybackServiceSession(PlaybackServiceSyncReason.StateChanged)
            return true
        } catch (err: HttpStatusException) {
            clearFailedAudioLoad()
            if (err.statusCode == 404) {
                return false
            }
            throw err
        } catch (err: IOException) {
            clearFailedAudioLoad()
            ignoreFailures { target.delete() }
            throw err
        }
    }

    private suspend fun <T> withAudioLoadWakeLock(block: suspend () -> T): T {
        val powerManager = application.getSystemService(PowerManager::class.java)
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            AUDIO_LOAD_WAKE_LOCK_TAG
        )
        wakeLock.setReferenceCounted(false)
        return try {
            wakeLock.acquire(AUDIO_LOAD_WAKE_LOCK_TIMEOUT_MS)
            block()
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    private suspend fun loadServerAudioFileWithRetry(jobId: String, target: File): Boolean {
        var attempt = 1
        while (true) {
            try {
                withContext(Dispatchers.Default) {
                    controller.player.loadFile(target) { percent ->
                        scope.launch(Dispatchers.Main) {
                            setDecodeProgress(percent)
                        }
                    }
                    engine.refreshAnchorJump()
                }
                return true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: IllegalArgumentException) {
                if (!handleServerAudioDecodeFailure(jobId, attempt, error)) return false
                attempt += 1
            } catch (error: IllegalStateException) {
                if (!handleServerAudioDecodeFailure(jobId, attempt, error)) return false
                attempt += 1
            }
        }
    }

    private suspend fun handleServerAudioDecodeFailure(
        jobId: String,
        attempt: Int,
        error: Throwable
    ): Boolean {
        controller.player.clear()
        if (!isActiveJobId(jobId)) {
            return false
        }
        if (attempt >= SERVER_AUDIO_DECODE_MAX_ATTEMPTS) {
            clearFailedAudioLoad()
            throw error
        }
        setAnalysisProgress(0, "Loading audio")
        delay(SERVER_AUDIO_DECODE_RETRY_DELAY_MS)
        return isActiveJobId(jobId)
    }

    private fun clearFailedAudioLoad() {
        audioLoadInFlight = false
        updatePlaybackState { it.copy(audioLoading = false) }
        syncPlaybackServiceSession(PlaybackServiceSyncReason.StateChanged)
    }

    suspend fun applyAnalysisResult(response: AnalysisResponse): Boolean {
        val responseJobId = canonicalJobId(response.id)
        if (response.id != null && (responseJobId == null || !isActiveJobId(responseJobId))) {
            return false
        }
        val result = response.result ?: return false
        updateDeleteEligibility(response)
        setAnalysisCalculating()
        val rootObj = result.jsonObject
        val trackElement = rootObj["track"] ?: rootObj["analysis"]?.jsonObject?.get("track")
        val trackMeta = trackElement?.let { json.decodeFromJsonElement(TrackMetaJson.serializer(), it) }
        val (vizData, autocanonizerData) = withContext(Dispatchers.Default) {
            engine.loadAnalysis(result)
            applyPendingTuningParams()
            controller.setCowbellSectionStartBeatIndices(engine.getSectionStartBeatIndices())
            val viz = engine.getVisualizationData()
            val canonizer = controller.autocanonizer.setAnalysis(result, trackMeta?.duration)
            viz to canonizer
        }
        syncTuningState()
        val currentPlayback = getState().playback
        val loadedTrackMeta = resolveLoadedTrackMeta(trackMeta, currentPlayback)
        val responseSourceProvider = sourceProviderFromRaw(response.sourceProvider)
        val responseSourceId = response.sourceId?.trim().orEmpty().ifBlank { null }
        val resolvedYouTubeId = if (responseSourceProvider == SOURCE_PROVIDER_YOUTUBE) {
            responseSourceId ?: currentPlayback.lastYouTubeId
        } else {
            currentPlayback.lastYouTubeId
        }
        val playTitle = buildPlayTitle(
            loadedTrackMeta.title,
            loadedTrackMeta.artist,
            currentPlayback.playMode,
            currentPlayback.jukeboxAudioMode
        )
        controller.setTrackMeta(loadedTrackMeta.title, loadedTrackMeta.artist)
        updateState {
            it.copy(
                playback = it.playback.copy(
                    analysisLoaded = true,
                    vizData = vizData,
                    autocanonizerData = autocanonizerData,
                    playTitle = playTitle,
                    trackDurationSeconds = loadedTrackMeta.durationSeconds,
                    castTotalBeats = null,
                    castTotalBranches = null,
                    lastYouTubeId = resolvedYouTubeId,
                    trackTitle = loadedTrackMeta.title,
                    trackArtist = loadedTrackMeta.artist,
                    canonizerOtherIndex = null,
                    canonizerTileColorOverrides = controller.autocanonizer.getTileColorOverrides(),
                    analysisProgress = null,
                    analysisMessage = null,
                    analysisErrorMessage = null,
                    analysisInFlight = false,
                    analysisCalculating = false,
                    audioLoading = false,
                    isPaused = false
                )
            )
        }
        applyActiveTab(TabId.Play, true)
        syncPlaybackServiceSession(PlaybackServiceSyncReason.StateChanged)
        val jobId = responseJobId ?: canonicalJobId(lastJobId)
        if (jobId != null) {
            recordPlay(jobId)
        }
        if (jobId != null) {
            cacheAnalysis(jobId, response)
        }
        syncPlaybackServiceSession(PlaybackServiceSyncReason.StateChanged)
        onStableTrackLoaded()
        return true
    }

    fun maybeUpdateNotification() {
        if (!controller.isPlaying()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationUpdateMs < 500L) return
        lastNotificationUpdateMs = now
        syncPlaybackServiceSession(PlaybackServiceSyncReason.ProgressTick)
    }

    internal fun syncPlaybackServiceSession(reason: PlaybackServiceSyncReason) {
        if (reason == PlaybackServiceSyncReason.HardStop) {
            hardStopPlaybackServiceSession()
            return
        }
        val current = getState()
        val session = resolvePlaybackServiceSession(
            playback = current.playback,
            keepFailedLoadVisible = shouldRetryFailedLoadFromTransport(current)
        )
        val skip = resolvePlaybackServiceSkipAvailability(current)
        when (session) {
            PlaybackServiceSession.Hidden -> syncHiddenPlaybackServiceSession()
            is PlaybackServiceSession.Cast -> syncCastPlaybackServiceSession(session, skip)
            is PlaybackServiceSession.LocalLoading -> syncLocalLoadingPlaybackServiceSession(
                session,
                skip
            )
            PlaybackServiceSession.LocalFailed -> syncLocalFailedPlaybackServiceSession()
            PlaybackServiceSession.LocalPaused,
            PlaybackServiceSession.LocalPlaying,
            PlaybackServiceSession.LocalReady -> syncLocalPlaybackServiceSession(skip)
        }
    }

    private fun syncHiddenPlaybackServiceSession() {
        resetPlaybackServiceSessionTracking()
        playbackServiceSessionVisible = false
        ForegroundPlaybackService.stop(application)
    }

    private fun syncCastPlaybackServiceSession(
        session: PlaybackServiceSession.Cast,
        skip: PlaybackServiceSkipAvailability
    ) {
        resetPlaybackServiceSessionTracking()
        ForegroundPlaybackService.updateCast(
            context = application,
            isPlaying = session.isPlaying,
            title = session.title,
            artist = session.artist,
            deviceName = session.deviceName,
            canSkipPrevious = skip.canSkipPrevious,
            canSkipNext = skip.canSkipNext
        )
        playbackServiceSessionVisible = true
        lastPlaybackServiceSessionKind = session.kind
    }

    private fun syncLocalLoadingPlaybackServiceSession(
        session: PlaybackServiceSession.LocalLoading,
        skip: PlaybackServiceSkipAvailability
    ) {
        val progressBucket = loadingNotificationProgressBucket(session.progress)
        if (
            playbackServiceSessionVisible &&
            lastPlaybackServiceSessionKind == session.kind &&
            lastLoadingNotificationBucket == progressBucket
        ) {
            return
        }
        ForegroundPlaybackService.update(
            context = application,
            canSkipPrevious = skip.canSkipPrevious,
            canSkipNext = skip.canSkipNext,
            isLoading = true,
            loadingProgress = session.progress
        )
        playbackServiceSessionVisible = true
        lastPlaybackServiceSessionKind = session.kind
        lastLoadingNotificationBucket = progressBucket
    }

    private fun syncLocalFailedPlaybackServiceSession() {
        if (
            playbackServiceSessionVisible &&
            lastPlaybackServiceSessionKind == PlaybackServiceSessionKind.LocalFailed
        ) {
            return
        }
        ForegroundPlaybackService.update(
            context = application,
            isLoadFailed = true
        )
        playbackServiceSessionVisible = true
        lastPlaybackServiceSessionKind = PlaybackServiceSessionKind.LocalFailed
        lastLoadingNotificationBucket = null
    }

    private fun syncLocalPlaybackServiceSession(skip: PlaybackServiceSkipAvailability) {
        resetPlaybackServiceSessionTracking()
        ForegroundPlaybackService.update(
            context = application,
            canSkipPrevious = skip.canSkipPrevious,
            canSkipNext = skip.canSkipNext
        )
        playbackServiceSessionVisible = true
        val current = getState()
        lastPlaybackServiceSessionKind = resolvePlaybackServiceSession(
            playback = current.playback,
            keepFailedLoadVisible = shouldRetryFailedLoadFromTransport(current)
        ).kind
    }

    private fun hardStopPlaybackServiceSession() {
        resetPlaybackServiceSessionTracking()
        playbackServiceSessionVisible = false
        ForegroundPlaybackService.stop(application)
    }

    private fun resetPlaybackServiceSessionTracking() {
        lastPlaybackServiceSessionKind = null
        lastLoadingNotificationBucket = null
    }

    fun resetForNewTrack(stopPlaybackService: Boolean = true) {
        engine.clearAnalysis()
        pendingTuningParams = null
        audioLoadInFlight = false
        controller.autocanonizer.reset()
        controller.stopExternalPlayback()
        controller.setJukeboxAudioMode(JukeboxAudioMode.Off)
        controller.setCowbellSectionStartBeatIndices(emptyList())
        engine.updateConfig(defaultConfig)
        controller.stopPlayback()
        controller.resetTimers()
        controller.setTrackMeta(null, null)
        resetPlaybackServiceSessionTracking()
        if (stopPlaybackService) {
            hardStopPlaybackServiceSession()
        }
        stopListenTimer()
        updateState {
            it.copy(
                playback = it.playback.copy(
                    playMode = it.playback.playMode,
                    jukeboxAudioMode = JukeboxAudioMode.Off,
                    canonizerFinishOutSong = it.playback.canonizerFinishOutSong,
                    audioLoaded = false,
                    analysisLoaded = false,
                    playAfterLoaded = false,
                    beatsPlayed = 0,
                    listenTime = "00:00:00",
                    trackDurationSeconds = null,
                    castTotalBeats = null,
                    castTotalBranches = null,
                    trackTitle = null,
                    trackArtist = null,
                    isRunning = false,
                    isPaused = false,
                    vizData = null,
                    autocanonizerData = null,
                    currentBeatIndex = -1,
                    canonizerOtherIndex = null,
                    canonizerTileColorOverrides = emptyMap(),
                    jumpLine = null,
                    playTitle = "",
                    lastYouTubeId = null,
                    lastTrackCreatedAtEpochMs = null,
                    lastJobId = null,
                    castPlaybackState = null,
                    isCastLoading = false,
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
        if (stopPlaybackService) {
            syncPlaybackServiceSession(PlaybackServiceSyncReason.StateChanged)
        }
        engine.stopJukebox()
        val emptyViz = VisualizationData(beats = emptyList(), edges = mutableListOf())
        updateState { it.copy(playback = it.playback.copy(vizData = emptyViz)) }
        setLastJobId(null)
        lastPlayCountedJobId = null
        deleteEligibilityJobId = null
        pollJob?.cancel()
        pollJob = null
        backgroundAudioLoadJob?.cancel()
        backgroundAudioLoadJob = null
        syncTuningState()
    }

    fun refreshCacheSize() {
        scope.launch(Dispatchers.IO) {
            val sizeBytes = cacheDir().walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
            updateState { it.copy(cacheSizeBytes = sizeBytes) }
        }
    }

    fun clearCache() {
        scope.launch(Dispatchers.IO) {
            val dir = cacheDir()
            dir.listFiles()?.forEach { it.deleteRecursively() }
            val sizeBytes = cacheDir().walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
            updateState { it.copy(cacheSizeBytes = sizeBytes) }
        }
    }

    fun updateListenTimeDisplay() {
        val totalSeconds = controller.getListenTimeSeconds()
        updatePlaybackState {
            it.copy(
                listenTime = formatDuration(totalSeconds),
                isRunning = controller.isPlaying(),
                isPaused = controller.isPaused()
            )
        }
    }

    fun restorePlaybackState() {
        val vizData = engine.getVisualizationData()
        val autocanonizerData = controller.autocanonizer.getData()
        val audioDuration = controller.player.getDurationSeconds()
        val hasAnalysis = vizData != null
        val hasAudio = controller.player.hasAudio() && audioDuration != null
        if (!hasAnalysis && !hasAudio) return
        val title = controller.getTrackTitle()
        val artist = controller.getTrackArtist()
        val currentPlayback = getState().playback
        val playTitle = buildPlayTitle(
            title,
            artist,
            currentPlayback.playMode,
            currentPlayback.jukeboxAudioMode
        )
        val currentTime = controller.player.getCurrentTime()
        val beatIndex = if (hasAnalysis) engine.getBeatAtTime(currentTime)?.which ?: -1 else -1
        updateState {
            it.copy(
                playback = it.playback.copy(
                    audioLoaded = hasAudio,
                    analysisLoaded = hasAnalysis,
                    vizData = vizData,
                    autocanonizerData = autocanonizerData,
                    playTitle = playTitle,
                    trackDurationSeconds = audioDuration,
                    castTotalBeats = null,
                    castTotalBranches = null,
                    trackTitle = title,
                    trackArtist = artist,
                    currentBeatIndex = beatIndex,
                    canonizerOtherIndex = controller.autocanonizer.getForcedOtherIndex(),
                    canonizerTileColorOverrides = controller.autocanonizer.getTileColorOverrides(),
                    isRunning = controller.isPlaying(),
                    isPaused = controller.isPaused()
                ),
                activeTab = if (hasAnalysis) TabId.Play else it.activeTab
            )
        }
        if (controller.isPlaying()) {
            startListenTimer()
        }
        syncPlaybackServiceSession(PlaybackServiceSyncReason.StateChanged)
    }

    suspend fun ensureAudioReady(): Boolean {
        if (controller.player.hasAudio()) {
            return true
        }
        val playback = getState().playback
        val cachedId = playback.lastJobId ?: lastJobId
        if (cachedId.isNullOrBlank()) {
            return false
        }
        val cachedAudio = audioFile(cachedId)
        if (cachedAudio.exists()) {
            setAudioLoading(true)
            setAnalysisProgress(0, "Loading audio")
            try {
                withContext(Dispatchers.Default) {
                    controller.player.loadFile(cachedAudio) { percent ->
                        scope.launch(Dispatchers.Main) {
                            setDecodeProgress(percent)
                        }
                    }
                    engine.refreshAnchorJump()
                }
                updatePlaybackState { it.copy(audioLoaded = true, audioLoading = false) }
                syncPlaybackServiceSession(PlaybackServiceSyncReason.StateChanged)
                return true
            } catch (_: OutOfMemoryError) {
                withContext(Dispatchers.IO) {
                    cachedAudio.delete()
                }
                updatePlaybackState { it.copy(audioLoading = false, audioLoaded = false) }
                syncPlaybackServiceSession(PlaybackServiceSyncReason.StateChanged)
                return false
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: IOException) {
                Log.e(TAG, "Failed to load cached audio for $cachedId", error)
                updatePlaybackState { it.copy(audioLoading = false, audioLoaded = false) }
                syncPlaybackServiceSession(PlaybackServiceSyncReason.StateChanged)
                return false
            } catch (error: IllegalArgumentException) {
                Log.e(TAG, "Failed to load cached audio for $cachedId", error)
                updatePlaybackState { it.copy(audioLoading = false, audioLoaded = false) }
                syncPlaybackServiceSession(PlaybackServiceSyncReason.StateChanged)
                return false
            } catch (error: IllegalStateException) {
                Log.e(TAG, "Failed to load cached audio for $cachedId", error)
                updatePlaybackState { it.copy(audioLoading = false, audioLoaded = false) }
                syncPlaybackServiceSession(PlaybackServiceSyncReason.StateChanged)
                return false
            }
        }
        val jobId = playback.lastJobId ?: return false
        return loadAudioFromJob(jobId)
    }

    fun syncTuningState() {
        val config = engine.getConfig()
        val graph = engine.getGraphState()
        updateState { state ->
            val thresholdValue = when {
                config.currentThreshold != 0 -> config.currentThreshold
                graph != null -> graph.currentThreshold
                else -> state.tuning.threshold
            }
            state.copy(
                tuning = state.tuning.copy(
                    threshold = thresholdValue,
                    computedThreshold = null,
                    minProb = (config.minRandomBranchChance * 100).toInt(),
                    maxProb = (config.maxRandomBranchChance * 100).toInt(),
                    ramp = (config.randomBranchChanceDelta * RANDOM_BRANCH_DELTA_PERCENT_SCALE).toInt(),
                    justBackwards = config.justBackwards,
                    justLong = config.justLongBranches,
                    removeSequential = config.removeSequentialBranches
                )
            )
        }
    }

    fun applyPlaybackMode(mode: PlaybackMode) {
        updatePlaybackState {
            it.copy(
                playMode = mode,
                jukeboxAudioMode = if (mode == PlaybackMode.Autocanonizer) {
                    JukeboxAudioMode.Off
                } else {
                    it.jukeboxAudioMode
                },
                playTitle = buildPlayTitle(
                    it.trackTitle,
                    it.trackArtist,
                    mode,
                    if (mode == PlaybackMode.Autocanonizer) {
                        JukeboxAudioMode.Off
                    } else {
                        it.jukeboxAudioMode
                    }
                )
            )
        }
    }

    private sealed class LoadingEvent {
        data class AnalysisQueued(val progress: Int?, val message: String?) : LoadingEvent()
        data class AnalysisProgress(val progress: Int?, val message: String?) : LoadingEvent()
        data object AnalysisCalculating : LoadingEvent()
        data class AnalysisError(val message: String) : LoadingEvent()
        data class AudioLoading(val loading: Boolean) : LoadingEvent()
    }

    private fun applyLoadingEvent(event: LoadingEvent) {
        updateState { current ->
            val playback = current.playback
            current.copy(
                playback = when (event) {
                    is LoadingEvent.AnalysisQueued -> playback.copy(
                        analysisProgress = event.progress,
                        analysisMessage = event.message,
                        analysisErrorMessage = null,
                        analysisInFlight = true,
                        analysisCalculating = false
                    )
                    is LoadingEvent.AnalysisProgress -> playback.copy(
                        analysisProgress = event.progress,
                        analysisMessage = event.message,
                        analysisErrorMessage = null,
                        analysisInFlight = true,
                        analysisCalculating = false
                    )
                    LoadingEvent.AnalysisCalculating -> playback.copy(
                        analysisProgress = null,
                        analysisMessage = null,
                        analysisErrorMessage = null,
                        analysisInFlight = false,
                        analysisCalculating = true
                    )
                    is LoadingEvent.AnalysisError -> playback.copy(
                        analysisProgress = null,
                        analysisMessage = null,
                        analysisErrorMessage = event.message,
                        analysisInFlight = false,
                        analysisCalculating = false,
                        audioLoading = false
                    )
                    is LoadingEvent.AudioLoading -> playback.copy(audioLoading = event.loading)
                }
            )
        }
        syncPlaybackServiceSession(PlaybackServiceSyncReason.StateChanged)
    }

    private inline fun ignoreFailures(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Ignore cache failures.
        }
    }

    private suspend fun pollAnalysis(jobId: String) {
        val baseUrl = getState().baseUrl
        val intervalMs = 3000L
        while (currentCoroutineContext().isActive) {
            if (!isActiveJobId(jobId)) {
                return
            }
            val response = retryTransientServerLoad {
                api.getAnalysis(baseUrl, jobId)
            }
            if (!isActiveJobId(jobId)) {
                return
            }
            updateDeleteEligibility(response)
            when {
                response.status == "failed" -> {
                    setAnalysisError(
                        ErrorDisplay.format(
                            raw = response.error,
                            errorCode = response.errorCode,
                            sourceProvider = response.sourceProvider,
                            fallback = "Loading failed."
                        )
                    )
                    return
                }
                isAnalysisInProgressStatus(response.status) -> {
                    val progress = response.progress?.roundToInt()
                    setAnalysisProgress(progress, response.message)
                    if (response.status != "downloading" &&
                        !getState().playback.audioLoaded &&
                        !audioLoadInFlight
                    ) {
                        audioLoadInFlight = true
                        var audioJob: Job? = null
                        audioJob = scope.launch {
                            try {
                                loadAudioFromJob(jobId)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (error: IOException) {
                                Log.e(TAG, "Background audio load failed for $jobId", error)
                            } catch (error: IllegalArgumentException) {
                                Log.e(TAG, "Background audio load failed for $jobId", error)
                            } catch (error: IllegalStateException) {
                                Log.e(TAG, "Background audio load failed for $jobId", error)
                            } finally {
                                audioLoadInFlight = false
                                if (backgroundAudioLoadJob == audioJob) {
                                    backgroundAudioLoadJob = null
                                }
                            }
                        }
                        backgroundAudioLoadJob = audioJob
                    }
                }
                response.status == "complete" -> {
                    if (!getState().playback.audioLoaded) {
                        if (audioLoadInFlight) {
                            delay(intervalMs)
                            continue
                        }
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

    private fun isActiveJobId(jobId: String): Boolean {
        return getState().playback.lastJobId == jobId
    }

    private fun cacheDir(): File {
        val dir = File(application.cacheDir, "jukebox-cache")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun analysisFile(jobId: String): File =
        serverTrackAnalysisFile(cacheDir(), jobId)

    private fun audioFile(jobId: String): File = serverTrackAudioFile(cacheDir(), jobId)

    private fun cacheAnalysis(
        jobId: String,
        response: AnalysisResponse
    ) {
        scope.launch(Dispatchers.IO) {
            ignoreFailures {
                val payload = json.encodeToString(response)
                analysisFile(jobId).writeText(payload)
            }
        }
    }

    private fun recordPlay(jobId: String) {
        if (lastPlayCountedJobId == jobId) return
        lastPlayCountedJobId = jobId
        scope.launch {
            try {
                api.postPlay(getState().baseUrl, jobId)
            } catch (_: Exception) {
                lastPlayCountedJobId = null
            }
        }
    }

    private data class ResolvedTuningParams(
        val config: JukeboxConfig,
        val deletedEdgeIds: List<Int>,
        val audioMode: JukeboxAudioMode?
    )

    private companion object {
        const val TAG = "PlaybackCoordinator"
        const val SERVER_AUDIO_DECODE_MAX_ATTEMPTS = 3
        const val SERVER_AUDIO_DECODE_RETRY_DELAY_MS = 500L
        const val AUDIO_LOAD_WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        const val AUDIO_LOAD_WAKE_LOCK_TAG = "ForeverJukebox:AudioLoad"
    }

    private fun parseTuningParams(raw: String?): ResolvedTuningParams? {
        val parsed = TuningParamsCodec.parse(raw, minThreshold = 0) ?: return null
        var config = defaultConfig
        parsed.justBackwards?.let { value ->
            config = config.copy(justBackwards = value)
        }
        parsed.justLongBranches?.let { value ->
            config = config.copy(justLongBranches = value)
        }
        parsed.removeSequentialBranches?.let { value ->
            config = config.copy(removeSequentialBranches = value)
        }
        parsed.threshold?.let { value ->
            config = config.copy(currentThreshold = value)
        }
        parsed.minProbPercent?.let { value ->
            config = config.copy(
                minRandomBranchChance = mapPercentToRange(value, 1.0)
            )
        }
        parsed.maxProbPercent?.let { value ->
            config = config.copy(
                maxRandomBranchChance = mapPercentToRange(value, 1.0)
            )
        }
        parsed.rampPercent?.let { value ->
            config = config.copy(
                randomBranchChanceDelta = mapPercentToRange(value, MAX_RANDOM_BRANCH_DELTA)
            )
        }
        parsed.anchorBeat?.let { value ->
            config = config.copy(preferredAnchorBeat = value)
        }
        return ResolvedTuningParams(config, parsed.deletedEdgeIds, parsed.audioMode)
    }

    private fun mapPercentToRange(percent: Int, max: Double): Double {
        val safe = percent.coerceIn(0, 100)
        return (max * safe) / 100.0
    }

    private fun mapValueToPercent(value: Double, max: Double): Int {
        val safeValue = value.coerceIn(0.0, max)
        return ((100.0 * safeValue) / max).roundToInt()
    }

    private fun getDeletedEdgeIds(): List<Int> {
        val graph = engine.getGraphState() ?: return emptyList()
        return graph.allEdges.filter { it.deleted }.map { it.id }
    }

    private fun applyPendingTuningParams() {
        val raw = pendingTuningParams
        pendingTuningParams = null
        val parsed = parseTuningParams(raw) ?: return
        val graph = engine.getGraphState()
        if (graph != null && parsed.deletedEdgeIds.isNotEmpty()) {
            val edgeById = graph.allEdges.associateBy { it.id }
            for (id in parsed.deletedEdgeIds) {
                val edge = edgeById[id] ?: continue
                engine.deleteEdge(edge)
            }
        }
        val configChanged = parsed.config != defaultConfig
        val shouldRebuild = configChanged || parsed.deletedEdgeIds.isNotEmpty()
        if (configChanged) {
            engine.updateConfig(parsed.config)
        }
        if (shouldRebuild) {
            engine.rebuildGraph()
        }
        if (parsed.audioMode != null) {
            controller.setJukeboxAudioMode(parsed.audioMode)
            updatePlaybackState {
                it.copy(
                    jukeboxAudioMode = parsed.audioMode,
                    playTitle = buildPlayTitle(
                        it.trackTitle,
                        it.trackArtist,
                        it.playMode,
                        parsed.audioMode
                    )
                )
            }
        }
    }

    private fun buildPlayTitle(
        title: String?,
        artist: String?,
        mode: PlaybackMode,
        audioMode: JukeboxAudioMode = JukeboxAudioMode.Off
    ): String {
        if (title.isNullOrBlank()) {
            return ""
        }
        val resolvedTitle = when {
            mode == PlaybackMode.Autocanonizer -> "$title (autocanonized)"
            audioMode != JukeboxAudioMode.Off -> "$title (${audioMode.wireValue})"
            else -> title
        }
        return if (!artist.isNullOrBlank()) {
            "$resolvedTitle — $artist"
        } else {
            resolvedTitle
        }
    }
}

private fun String?.takeIfNotBlank(): String? = this?.trim()?.takeIf { it.isNotBlank() }

private const val MAX_RANDOM_BRANCH_DELTA = 0.2
private const val RANDOM_BRANCH_DELTA_PERCENT_SCALE = 100.0 / MAX_RANDOM_BRANCH_DELTA
