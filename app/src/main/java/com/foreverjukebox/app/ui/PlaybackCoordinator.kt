package com.foreverjukebox.app.ui

import android.app.Application
import android.media.MediaCodec
import android.os.PowerManager
import android.os.SystemClock
import com.foreverjukebox.app.AppLog
import com.foreverjukebox.app.audio.LoadingAudioFeedbackController
import com.foreverjukebox.app.data.HttpStatusException
import com.foreverjukebox.app.data.SOURCE_PROVIDER_YOUTUBE
import com.foreverjukebox.app.data.canonicalJobId
import com.foreverjukebox.app.data.sourceProviderFromRaw
import com.foreverjukebox.app.engine.JukeboxConfig
import com.foreverjukebox.app.engine.JukeboxEngine
import com.foreverjukebox.app.engine.withMinimumJumpDistancePercent
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

// A decode failure that does NOT mean the audio file itself is bad. MediaCodec decoder
// creation/configuration can fail transiently when hardware decoders are contended or have
// been reclaimed (common while the app is backgrounded with the screen off), and an
// OutOfMemoryError reflects momentary heap pressure, not a corrupt file. Cached audio must
// not be discarded on these — the same file decodes fine once resources free up.
//
// A CodecException with the generic error code (Integer.MIN_VALUE / ERROR_UNKNOWN) is also
// treated as transient regardless of its isTransient/isRecoverable flags: vendor codecs
// report it for resource/service failures with both flags false, and it carries no evidence
// that the bitstream is bad. Genuine file corruption still surfaces as extractor/format
// errors (IllegalArgumentException, "No audio track found"), which remain non-transient.
internal fun isTransientDecodeError(error: Throwable): Boolean {
    return when (error) {
        is OutOfMemoryError -> true
        is MediaCodec.CodecException ->
            error.isTransient || error.isRecoverable || error.errorCode == Int.MIN_VALUE
        else -> false
    }
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

internal fun resolveAutocanonizerTrackDuration(
    autocanonizerTrackDuration: Double?,
    sourceDuration: Double?
): Double {
    return autocanonizerTrackDuration ?: sourceDuration ?: 0.0
}

/**
 * Playback state after the current track is cleared ahead of a new one.
 *
 * [keepLoadVisible] is set when a new load follows immediately: the reset state must
 * then already resolve as loading ([PlaybackServiceSession.LocalLoading]). Otherwise
 * there is a window — until the load coroutine posts its first loading event — where
 * the state resolves to Hidden, and any racing service sync (e.g. the outgoing track's
 * async stop callback) tears down the foreground service. A backgrounded app can only
 * restart it inside a brief OS exemption, and Android restricts the audio subsystem
 * (codec included) for non-foreground apps, so the load that follows would fail.
 */
internal fun PlaybackState.resetForNewTrack(keepLoadVisible: Boolean): PlaybackState {
    return copy(
        playMode = playMode,
        jukeboxAudioMode = JukeboxAudioMode.Off,
        jukeboxAudioModeIntensity = AudioModeIntensity.DEFAULT,
        canonizerFinishOutSong = canonizerFinishOutSong,
        audioLoaded = false,
        analysisLoaded = false,
        playAfterLoaded = false,
        beatsPlayed = 0,
        listenTime = "00:00:00",
        trackDurationSeconds = null,
        autocanonizer = autocanonizerUiStateForTrack(0.0),
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
        localSourceUri = null,
        lastTrackCreatedAtEpochMs = null,
        lastJobId = null,
        castPlaybackState = null,
        isCastLoading = false,
        castTransfer = null,
        deleteEligible = false,
        analysisProgress = null,
        analysisMessage = null,
        analysisErrorMessage = null,
        analysisInFlight = keepLoadVisible,
        analysisCalculating = false,
        audioLoading = false,
        isCasting = isCasting,
        castDeviceName = castDeviceName
    )
}

class PlaybackCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val serverGateway: ServerGateway,
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
    private var transientDecodeFailureJobId: String? = null
    private var transientDecodeFailureAtMs = 0L
    private var transientDecodeFailureError: Throwable? = null
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

    /** Tuning parked for a load that has not reached the engine yet. */
    fun getPendingTuningParams(): String? = pendingTuningParams

    fun setPendingTuningParams(raw: String?) {
        pendingTuningParams = if (!raw.isNullOrBlank()) {
            raw
        } else {
            null
        }
    }

    fun buildTuningParamsString(): String? {
        return buildTuningParamsString(
            audioModeWireValue = getState().playback.jukeboxAudioMode.wireValue,
            audioModeIntensity = getState().playback.jukeboxAudioModeIntensity
        )
    }

    /** Engine tuning paired with an audio mode the caller knows better than playback state. */
    fun buildTuningParamsString(
        audioModeWireValue: String,
        audioModeIntensity: Int
    ): String? {
        return TuningParamsCodec.buildSavedTuningParams(
            tuning = engineTuningState(
                config = engine.getConfig(),
                computedThreshold = engine.getGraphState()?.computedThreshold,
                deletedEdgeIds = getDeletedEdgeIds(),
                anchorBranchId = engine.getUserAnchorEdgeId()
            ),
            audioModeWireValue = audioModeWireValue,
            audioModeIntensity = audioModeIntensity
        )
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

    /**
     * Drop the in-flight loading state for an attempt that ended without a loading screen to report
     * into — a cast queue failure surfaces as a toast, and anything left in flight keeps the
     * playback-change lock engaged. Clears every field that lock reads, so releasing it here does
     * not depend on which of them the attempt happened to raise. Any error already on screen stays.
     */
    fun clearAnalysisLoading() {
        applyLoadingEvent(LoadingEvent.AnalysisIdle)
    }

    fun setAnalysisError(message: String, cause: Throwable? = null) {
        // Single chokepoint for every surfaced load/analysis error (server, cached,
        // local, playback, autocanonizer). Persisting the message here guarantees
        // the cause of any "Loading failed." is captured even on paths that have no
        // throwable to log at the call site (e.g. server-reported failures); call
        // sites that do hold one pass it so the record carries the real exception
        // chain. Every surfaced failure records a Crashlytics non-fatal — a failure
        // the user saw but that never reaches the console is invisible to remote
        // diagnostics, which is worse than issue noise. The benign user-cancel
        // sentinel is excluded.
        if (message != LoadingAudioFeedbackController.LOCAL_ANALYSIS_CANCELLED_MESSAGE) {
            AppLog.error(TAG, "Load/analysis error surfaced: $message", cause)
        }
        applyLoadingEvent(LoadingEvent.AnalysisError(message))
    }

    fun clearAnalysisErrorForPlaybackStart() {
        if (getState().playback.analysisErrorMessage.isNullOrBlank()) return
        updatePlaybackState { it.copy(analysisErrorMessage = null) }
        syncPlaybackServiceSession()
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
                AppLog.warn(TAG, "Polling failed for $jobId", error)
                setAnalysisError("Loading failed.")
            } catch (error: IllegalArgumentException) {
                AppLog.warn(TAG, "Polling failed for $jobId", error)
                setAnalysisError("Loading failed.")
            } catch (error: IllegalStateException) {
                AppLog.warn(TAG, "Polling failed for $jobId", error)
                setAnalysisError("Loading failed.")
            }
        }
    }

    private fun isDeviceIdle(): Boolean =
        application.getSystemService(PowerManager::class.java)?.isDeviceIdleMode == true

    // Breadcrumb stamping the power regime a load starts under, so a later failure
    // report shows whether doze was already engaged before the first codec/network
    // attempt rather than leaving that to be inferred from failure timing.
    fun logLoadPowerState(stage: String) {
        val powerManager = application.getSystemService(PowerManager::class.java)
        AppLog.warn(
            TAG,
            "$stage: deviceIdle=${powerManager?.isDeviceIdleMode} " +
                "powerSave=${powerManager?.isPowerSaveMode} " +
                "batteryUnrestricted=" +
                "${powerManager?.isIgnoringBatteryOptimizations(application.packageName)}"
        )
    }

    private fun noteTransientDecodeFailure(jobId: String, error: Throwable) {
        transientDecodeFailureJobId = jobId
        transientDecodeFailureAtMs = SystemClock.elapsedRealtime()
        transientDecodeFailureError = error
    }

    // The decode error behind an active transient-failure memo, or null when none is
    // memoed for this job. Lets outer load layers attribute a surfaced failure to the
    // blocked codec instead of whatever secondary error (typically a network failure
    // on the fallback fetch) happened to finish the flow.
    fun recentTransientDecodeFailure(jobId: String): Throwable? =
        if (hasRecentTransientDecodeFailure(jobId)) transientDecodeFailureError else null

    // Whether a decode for this job just failed on a transient/reclaim codec signature.
    // Load layers stack (cached try, loadOrPoll's cached try, fresh-file decode), and
    // each stalled codec attempt costs tens of seconds — so once one layer has hit the
    // signature, sibling layers skip their own doomed attempts instead of repeating it.
    // The memo scopes to a single load flow: resetForNewTrack clears it, so a
    // user-initiated retry or new selection always gets a real decode attempt — a
    // fully cached track must stay playable offline, and skipping its decode would
    // otherwise reroute the load through the network.
    private fun hasRecentTransientDecodeFailure(jobId: String): Boolean =
        jobId == transientDecodeFailureJobId &&
            SystemClock.elapsedRealtime() - transientDecodeFailureAtMs < TRANSIENT_DECODE_MEMO_MS

    // Catches Throwable around audio decoding on purpose: an OutOfMemoryError (not an
    // Exception) must be treated as a transient/recoverable decode failure, not a corrupt
    // cache entry. See the catch block below.
    @Suppress("TooGenericExceptionCaught")
    suspend fun tryLoadCachedTrack(jobId: String): Boolean {
        if (!isActiveJobId(jobId)) {
            return false
        }
        if (hasRecentTransientDecodeFailure(jobId)) {
            return false
        }
        val cached = withContext(Dispatchers.IO) {
            val analysisPath = analysisFile(jobId)
            val audioPath = audioFile(jobId)
            if (!hasCompleteTrackCache(jobId)) {
                return@withContext null
            }
            val analysisText = analysisPath.readText()
            val response = json.decodeFromString<TrackAnalysisResult>(analysisText)
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
        val decoded = try {
            // Retry transient codec failures the same way the server load path does, so a
            // momentary MediaCodec hiccup (e.g. backgrounded with the screen off) does not
            // get mistaken for a corrupt cache entry.
            decodeAudioFileWithRetry(jobId, audioPath) { percent ->
                setAnalysisProgress(percent, "Loading audio")
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            // Decoding failed even after retries. A transient/recoverable codec failure or
            // an OutOfMemoryError does NOT mean the file is bad — keep it and let the caller
            // re-fetch/retry rather than deleting a track the user plays daily. Only files
            // that are genuinely unreadable are discarded.
            if (isTransientDecodeError(error)) {
                AppLog.warn(
                    TAG,
                    "Transient decode failure for cached audio $jobId; keeping cache",
                    error
                )
                if (isActiveJobId(jobId)) {
                    controller.player.clear()
                }
            } else {
                discardUnusableCachedAudio(jobId, error)
            }
            return false
        }
        if (!decoded || !isActiveJobId(jobId)) {
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

    fun updateDeleteEligibility(response: TrackAnalysisResult) {
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
        return loadAudioFromActiveJob(jobId)
    }

    private suspend fun loadAudioFromActiveJob(jobId: String): Boolean {
        // A decode for this job just failed on a transient signature; skip the doomed
        // attempt. Callers that poll re-invoke this on their next tick, so the decode
        // happens for real once the memo window lapses.
        if (hasRecentTransientDecodeFailure(jobId)) {
            return false
        }
        val baseUrl = getState().baseUrl
        setAudioLoading(true)
        setAnalysisProgress(0, "Loading audio")
        val target = audioFile(jobId)
        // A cached copy whose decode failed with a transient/reclaim codec signature
        // is still a good file; re-downloading it cannot fix the codec. Fetch only
        // when no local copy exists (downloads land atomically, so an existing file
        // is a complete one). The fetch has its own catches: a failed download never
        // touches the target, so any cached copy survives for the next attempt —
        // unlike a decode failure below, which can condemn the file itself.
        if (!target.exists()) {
            try {
                serverGateway.fetchAudioToFile(baseUrl, jobId, target)
            } catch (err: HttpStatusException) {
                clearFailedAudioLoad()
                if (err.statusCode == 404) {
                    return false
                }
                throw err
            } catch (err: IOException) {
                clearFailedAudioLoad()
                throw err
            }
        }
        if (!isActiveJobId(jobId)) {
            return false
        }
        try {
            val loaded = loadServerAudioFileWithRetry(jobId, target)
            if (!loaded) return false
            if (!isActiveJobId(jobId)) {
                return false
            }
            audioLoadInFlight = false
            updatePlaybackState { it.copy(audioLoaded = true, audioLoading = false) }
            syncPlaybackServiceSession()
            return true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: OutOfMemoryError) {
            // Decoding a full track to PCM can exhaust the heap. Recover the same
            // way the cached-track path does (PlaybackCoordinator.tryLoadCachedTrack)
            // rather than letting the Error propagate uncaught and kill the app.
            throw failedAudioDecode(jobId, target, err, "Out of memory while loading audio for $jobId")
        } catch (err: IOException) {
            // Extractor/decoder creation throws IOException for unreadable bytes. This
            // is a decode failure, not a fetch failure (the fetch has its own catches
            // above), so the unusable file is discarded and the next attempt downloads
            // a fresh copy instead of re-decoding the same bad bytes forever.
            throw failedAudioDecode(jobId, target, err, "Failed to read audio for $jobId")
        } catch (err: IllegalStateException) {
            // A decode failure (e.g. MediaCodec.CodecException) that survived the
            // per-attempt retries in loadServerAudioFileWithRetry.
            throw failedAudioDecode(jobId, target, err, "Failed to decode audio for $jobId")
        } catch (err: IllegalArgumentException) {
            throw failedAudioDecode(jobId, target, err, "Failed to decode audio for $jobId")
        }
    }

    // Shared teardown for a decode that failed after retries. Remaps to an IOException so
    // every caller's load-failure handling applies instead of the raw error reaching the
    // launching scope. The player is cleared only while this job still owns it — a stale
    // failure must not wipe a replacement track's loaded audio. The file is deleted only
    // for non-transient failures: a reclaim-shaped codec error or momentary heap pressure
    // says nothing about the file's integrity, while a genuinely unreadable file must go
    // so the next attempt re-downloads instead of re-decoding the same bad bytes.
    private fun failedAudioDecode(
        jobId: String,
        target: File,
        err: Throwable,
        message: String
    ): IOException {
        if (isActiveJobId(jobId)) {
            controller.player.clear()
        }
        clearFailedAudioLoad()
        if (!isTransientDecodeError(err)) {
            ignoreFailures { target.delete() }
        }
        return IOException(message, err)
    }

    private suspend fun loadServerAudioFileWithRetry(jobId: String, target: File): Boolean {
        return decodeAudioFileWithRetry(jobId, target) { percent ->
            setDecodeProgress(percent)
        }
    }

    // Decode an audio file into the player, retrying transient codec failures the same way
    // for both freshly-downloaded and cached files. MediaCodec decoder creation/config can
    // fail transiently under resource pressure (e.g. backgrounded with the screen off), so a
    // single failure must not be treated as fatal. Returns true on success, false if the job
    // is no longer active, and throws the final error once retries are exhausted.
    private suspend fun decodeAudioFileWithRetry(
        jobId: String,
        file: File,
        reportProgress: (Int) -> Unit
    ): Boolean {
        // Progress callbacks are posted on the coordinator scope and the decoder has no
        // cancellation points, so a cancelled load keeps decoding — and reporting — to the
        // end. Posts are dropped once this load's job dies so an abandoned decode cannot
        // re-raise the loading overlay (and the playback-change lock) over whatever replaced
        // it, such as a track handed off to a cast receiver.
        val loadJob = currentCoroutineContext()[Job]
        var attempt = 1
        while (true) {
            try {
                withContext(Dispatchers.Default) {
                    controller.player.loadFile(file) { percent ->
                        scope.launch(Dispatchers.Main) {
                            if (loadJob?.isActive == true) {
                                reportProgress(percent)
                            }
                        }
                    }
                    engine.refreshAnchorJump()
                }
                transientDecodeFailureJobId = null
                transientDecodeFailureError = null
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
        // Stale check before touching the player: a decode failing on behalf of an abandoned
        // load must not wipe the audio a replacement track has already loaded.
        if (!isActiveJobId(jobId)) {
            return false
        }
        controller.player.clear()
        val reclaimShaped = isTransientDecodeError(error)
        if (reclaimShaped) {
            noteTransientDecodeFailure(jobId, error)
        }
        // When the app is backgrounded under doze the OS restricts the audio subsystem
        // (audio hardening) and every codec attempt — hardware or software — fails the
        // same way after stalling for tens of seconds. Retrying only delays the error
        // surfacing, so bail after the first such failure while the device is idle.
        if (attempt >= SERVER_AUDIO_DECODE_MAX_ATTEMPTS || (reclaimShaped && isDeviceIdle())) {
            clearFailedAudioLoad()
            throw error
        }
        setAnalysisProgress(0, "Loading audio")
        // Exponential backoff: codec reclaim under memory/background pressure can take
        // several seconds to clear (screen off, app cached), so short fixed delays
        // exhaust retries before the codec is grantable again.
        delay(SERVER_AUDIO_DECODE_BASE_RETRY_DELAY_MS shl (attempt - 1))
        return isActiveJobId(jobId)
    }

    private fun clearFailedAudioLoad() {
        audioLoadInFlight = false
        updatePlaybackState { it.copy(audioLoading = false) }
        syncPlaybackServiceSession()
    }

    // Only for genuinely unreadable/corrupt cache entries (decode failed after retries with
    // a non-transient error). Transient codec/OOM failures are handled by the caller without
    // deleting the file — see tryLoadCachedTrack and isTransientDecodeError.
    private suspend fun discardUnusableCachedAudio(jobId: String, error: Throwable) {
        // error (not warn): deletes the user's cached audio, so record the cause.
        AppLog.error(TAG, "Discarding unusable cached audio for $jobId", error)
        // Clear only while this job still owns the player; a stale failure must not wipe a
        // replacement track's loaded audio. The unreadable file is deleted either way.
        if (isActiveJobId(jobId)) {
            controller.player.clear()
        }
        withContext(Dispatchers.IO) {
            ignoreFailures { audioFile(jobId).delete() }
        }
    }

    suspend fun applyAnalysisResult(response: TrackAnalysisResult): Boolean {
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
                    autocanonizer = autocanonizerUiStateForTrack(
                        resolveAutocanonizerTrackDuration(
                            autocanonizerTrackDuration = autocanonizerData?.trackDuration,
                            sourceDuration = loadedTrackMeta.durationSeconds
                        )
                    ),
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
        syncPlaybackServiceSession()
        val jobId = responseJobId ?: canonicalJobId(lastJobId)
        if (jobId != null) {
            recordPlay(jobId)
        }
        if (jobId != null) {
            cacheAnalysis(jobId, response)
        }
        syncPlaybackServiceSession()
        onStableTrackLoaded()
        return true
    }

    fun maybeUpdateNotification() {
        if (!controller.isPlaying()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationUpdateMs < 500L) return
        lastNotificationUpdateMs = now
        syncPlaybackServiceSession()
    }

    internal fun syncPlaybackServiceSession() {
        val current = getState()
        val session = resolvePlaybackServiceSession(current)
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
        lastPlaybackServiceSessionKind = resolvePlaybackServiceSession(current).kind
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
        // Each load flow gets a fresh chance at the codec — see hasRecentTransientDecodeFailure.
        transientDecodeFailureJobId = null
        transientDecodeFailureError = null
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
                playback = it.playback.resetForNewTrack(
                    keepLoadVisible = !stopPlaybackService
                ),
                // Note: pendingTrackName/pendingTrackArtist are intentionally NOT cleared
                // here. They are search-context metadata describing the still-visible
                // videoMatches list (which this reset leaves intact), so their lifecycle
                // is tied to the results list, not to a playback reset. They are cleared
                // where videoMatches is cleared/replaced (search start, track committed).
                search = it.search.copy(
                    spotifyLoading = false,
                    youtubeLoading = false
                )
            )
        }
        if (stopPlaybackService) {
            syncPlaybackServiceSession()
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
                    autocanonizer = it.playback.autocanonizer.copy(
                        trackDurationSeconds = resolveAutocanonizerTrackDuration(
                            autocanonizerTrackDuration = autocanonizerData?.trackDuration,
                            sourceDuration = null
                        )
                    ),
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
        syncPlaybackServiceSession()
    }

    // Catches Throwable around audio decoding on purpose: an OutOfMemoryError (not an
    // Exception) must be treated as a transient/recoverable decode failure, not a corrupt
    // cache entry. See the catch block below.
    @Suppress("TooGenericExceptionCaught")
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
            val decoded = try {
                // Retry transient codec failures the same way tryLoadCachedTrack does, so a
                // momentary MediaCodec hiccup (e.g. backgrounded with the screen off) does not
                // get mistaken for a corrupt cache entry.
                decodeAudioFileWithRetry(cachedId, cachedAudio) { percent ->
                    setDecodeProgress(percent)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // Decoding failed even after retries. A transient/recoverable codec failure or
                // an OutOfMemoryError does NOT mean the file is bad — keep it and re-fetch from
                // the server rather than deleting a track the user plays often. Only genuinely
                // unreadable files are discarded.
                if (isTransientDecodeError(error)) {
                    AppLog.warn(
                        TAG,
                        "Transient decode failure for cached audio $cachedId; keeping cache",
                        error
                    )
                    controller.player.clear()
                } else {
                    discardUnusableCachedAudio(cachedId, error)
                }
                updatePlaybackState { it.copy(audioLoading = false, audioLoaded = false) }
                syncPlaybackServiceSession()
                return false
            }
            if (!decoded) {
                updatePlaybackState { it.copy(audioLoading = false, audioLoaded = false) }
                syncPlaybackServiceSession()
                return false
            }
            updatePlaybackState { it.copy(audioLoaded = true, audioLoading = false) }
            syncPlaybackServiceSession()
            return true
        }
        val jobId = playback.lastJobId ?: return false
        return try {
            loadAudioFromJob(jobId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: IOException) {
            AppLog.error(TAG, "Failed to load audio for $jobId", error)
            updatePlaybackState { it.copy(audioLoading = false, audioLoaded = false) }
            syncPlaybackServiceSession()
            false
        }
    }

    fun syncTuningState() {
        val config = engine.getConfig()
        val graph = engine.getGraphState()
        updateState { state ->
            // The receiver owns tuning while casting and reports all of it with every status.
            // The local engine is not the one playing, so none of its values may be written over
            // the mirrored ones; the status reducer is the only writer for the duration.
            if (state.playback.isCasting) {
                return@updateState state
            }
            state.copy(
                tuning = state.tuning.copy(
                    threshold = config.currentThreshold.takeIf { it != 0 },
                    computedThreshold = graph?.computedThreshold,
                    minProb = (config.minRandomBranchChance * 100).toInt(),
                    maxProb = (config.maxRandomBranchChance * 100).toInt(),
                    ramp = (config.randomBranchChanceDelta * RANDOM_BRANCH_DELTA_PERCENT_SCALE).toInt(),
                    justBackwards = config.justBackwards,
                    minJumpDistancePercent = if (config.justLongBranches) {
                        config.minLongBranchPercent
                    } else {
                        0
                    },
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
        object AnalysisIdle : LoadingEvent()
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
                    LoadingEvent.AnalysisIdle -> playback.copy(
                        analysisProgress = null,
                        analysisMessage = null,
                        analysisInFlight = false,
                        analysisCalculating = false,
                        audioLoading = false
                    )
                }
            )
        }
        syncPlaybackServiceSession()
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
            val response = serverGateway.getAnalysis(baseUrl, jobId)
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
                                AppLog.warn(TAG, "Background audio load failed for $jobId", error)
                            } catch (error: IllegalArgumentException) {
                                AppLog.warn(TAG, "Background audio load failed for $jobId", error)
                            } catch (error: IllegalStateException) {
                                AppLog.warn(TAG, "Background audio load failed for $jobId", error)
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
        File(cacheDir(), "$jobId.analysis.json")

    private fun audioFile(jobId: String): File = File(cacheDir(), "$jobId.audio")

    private fun hasCompleteTrackCache(jobId: String): Boolean {
        return analysisFile(jobId).exists() && audioFile(jobId).exists()
    }

    private fun cacheAnalysis(
        jobId: String,
        response: TrackAnalysisResult
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
                serverGateway.postPlay(getState().baseUrl, jobId)
            } catch (_: Exception) {
                lastPlayCountedJobId = null
            }
        }
    }

    private data class ResolvedTuningParams(
        val config: JukeboxConfig,
        val deletedEdgeIds: List<Int>,
        val anchorBranchId: Int?,
        val audioMode: JukeboxAudioMode?,
        val audioModeIntensity: Int
    )

    private companion object {
        const val TAG = "PlaybackCoordinator"
        const val SERVER_AUDIO_DECODE_MAX_ATTEMPTS = 3
        const val SERVER_AUDIO_DECODE_BASE_RETRY_DELAY_MS = 500L
        const val TRANSIENT_DECODE_MEMO_MS = 30_000L
    }

    private fun parseTuningParams(raw: String?): ResolvedTuningParams? {
        val parsed = TuningParamsCodec.parse(raw) ?: return null
        var config = defaultConfig
        parsed.justBackwards?.let { value ->
            config = config.copy(justBackwards = value)
        }
        parsed.minJumpDistancePercent?.let { percent ->
            config = config.withMinimumJumpDistancePercent(percent)
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
        return ResolvedTuningParams(
            config = config,
            deletedEdgeIds = parsed.deletedEdgeIds,
            anchorBranchId = parsed.anchorBranchId,
            audioMode = parsed.audioMode,
            audioModeIntensity = parsed.audioModeIntensity
        )
    }

    private fun mapPercentToRange(percent: Int, max: Double): Double {
        val safe = percent.coerceIn(0, 100)
        return (max * safe) / 100.0
    }

    private fun getDeletedEdgeIds(): List<Int> {
        val graph = engine.getGraphState() ?: return emptyList()
        return graph.allEdges.filter { it.deleted }.map { it.id }
    }

    // Mirrors the web's applyAnchorBranchFromUrl: `ab` is an anchor edge id, valid only
    // when the edge still exists, isn't deleted, and jumps backwards.
    private fun applyAnchorBranchFromParams(anchorBranchId: Int) {
        val graph = engine.getGraphState() ?: return
        val edge = graph.allEdges.firstOrNull { it.id == anchorBranchId } ?: return
        if (edge.deleted || edge.dest.which >= edge.src.which) {
            return
        }
        engine.setUserAnchorEdge(edge)
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
        parsed.anchorBranchId?.let(::applyAnchorBranchFromParams)
        if (parsed.audioMode != null) {
            controller.setJukeboxAudioMode(parsed.audioMode, parsed.audioModeIntensity)
            updatePlaybackState {
                it.copy(
                    jukeboxAudioMode = parsed.audioMode,
                    jukeboxAudioModeIntensity = parsed.audioModeIntensity,
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

/**
 * Snapshot of the engine's live tuning as a [TuningState], so on-device capture flows
 * through the same serializer ([TuningParamsCodec.buildSavedTuningParams]) as cast-time
 * capture. The engine's auto sentinel (currentThreshold == 0) becomes a null threshold, which
 * is what the serializer omits `thresh` for; the percent fields rely on [TuningState]'s defaults
 * matching [JukeboxConfig]'s defaults (18/50/10) — asserted by EngineTuningStateTest.
 */
internal fun engineTuningState(
    config: JukeboxConfig,
    computedThreshold: Int?,
    deletedEdgeIds: List<Int> = emptyList(),
    anchorBranchId: Int? = null
): TuningState {
    return TuningState(
        threshold = config.currentThreshold.takeIf { it != 0 },
        computedThreshold = computedThreshold,
        minProb = valueToPercent(config.minRandomBranchChance, 1.0),
        maxProb = valueToPercent(config.maxRandomBranchChance, 1.0),
        ramp = valueToPercent(config.randomBranchChanceDelta, MAX_RANDOM_BRANCH_DELTA),
        justBackwards = config.justBackwards,
        minJumpDistancePercent = if (config.justLongBranches) config.minLongBranchPercent else 0,
        removeSequential = config.removeSequentialBranches,
        deletedEdgeIds = deletedEdgeIds,
        anchorBranchId = anchorBranchId
    )
}

private fun valueToPercent(value: Double, max: Double): Int {
    val safeValue = value.coerceIn(0.0, max)
    return ((100.0 * safeValue) / max).roundToInt()
}
