package com.foreverjukebox.app.ui

import com.foreverjukebox.app.cast.CastRelayClient
import com.google.android.gms.cast.framework.CastSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CastPlaybackCoordinator(
    private val castController: CastController,
    private val getState: () -> UiState,
    private val updateState: ((UiState) -> UiState) -> Unit,
    private val onCastUnavailable: () -> Unit,
    private val onSyncCastNotification: () -> Unit,
    private val castTrackLengthLimitErrorMessage: () -> String,
    private val scope: CoroutineScope,
    private val castRelayClient: CastRelayClient,
    private val buildUploadSource: CastLocalUploadSourceFactory,
    private val relayBaseUrl: String
) {
    private var lastCastRequest: CastLoadRequest? = null
    private var castRetryUsed = false
    private var castLoadJob: Job? = null

    fun resetStatusListener() {
        castController.resetStatusListener()
    }

    fun endSession() {
        clearPendingCastRequest()
        castController.endSession()
    }

    /** Drops the last cast request so a stale track can't be retried after a disconnect. */
    fun clearPendingCastRequest() {
        castLoadJob?.cancel()
        castLoadJob = null
        lastCastRequest = null
        castRetryUsed = false
    }

    fun requestCastStatus() {
        if (!getState().castEnabled) {
            return
        }
        val session = castController.getSession() ?: return
        ensureCastStatusListener(session)
        castController.requestStatus(session, CAST_COMMAND_NAMESPACE)
    }

    /**
     * Cast a server-analyzed track: register a relay pull for [jobId] (the relay fetches the
     * analysis/audio from the jukebox server itself), then LOAD by the jobId immediately — the
     * receiver shows live progress while the job runs.
     */
    fun castTrackId(
        jobId: String,
        title: String? = null,
        artist: String? = null,
        youtubeId: String? = null,
        tuningParams: String? = null
    ) {
        castRetryUsed = false
        castTrackIdInternal(jobId, title, artist, youtubeId, tuningParams)
    }

    private fun castTrackIdInternal(
        jobId: String,
        title: String?,
        artist: String?,
        youtubeId: String?,
        tuningParams: String?
    ) {
        if (!getState().castEnabled) {
            onCastUnavailable()
            return
        }
        val currentState = getState()
        val baseUrl = currentState.baseUrl.trim()
        if (baseUrl.isBlank()) {
            return
        }
        val session = castController.getSession() ?: return
        ensureCastStatusListener(session)
        val displayTitle = if (artist.isNullOrBlank()) {
            title?.takeIf { it.isNotBlank() } ?: "Unknown"
        } else {
            "${title?.takeIf { it.isNotBlank() } ?: "Unknown"} — $artist"
        }
        val resolvedCastTuningParams = TuningParamsCodec.buildCastLoadPayload(
            raw = tuningParams,
            highlightAnchorBranch = currentState.tuning.highlightAnchorBranch
        )
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isBlank()) {
            return
        }
        val resolvedYoutubeId = youtubeId?.trim().orEmpty().ifBlank { null }
        lastCastRequest = CastLoadRequest.Server(normalizedJobId, title, artist, resolvedYoutubeId, tuningParams)
        updateState {
            it.copy(
                playback = it.playback.copy(
                    playMode = PlaybackMode.Jukebox,
                    playTitle = displayTitle,
                    trackTitle = title,
                    trackArtist = artist,
                    trackDurationSeconds = null,
                    castTotalBeats = null,
                    castTotalBranches = null,
                    jukeboxAudioMode = JukeboxAudioMode.Off,
                    castAudioModeWireValue = JukeboxAudioMode.Off.wireValue,
                    castSupportedAudioModes = emptyList(),
                    lastYouTubeId = resolvedYoutubeId,
                    lastTrackCreatedAtEpochMs = null,
                    castPlaybackState = "loading",
                    lastJobId = normalizedJobId,
                    isCastLoading = true,
                    castTransfer = CastTransfer.WaitingForReceiver(normalizedJobId),
                    analysisInFlight = false,
                    analysisCalculating = false,
                    analysisProgress = null,
                    analysisMessage = null,
                    analysisErrorMessage = null,
                    deleteEligible = false,
                    playAfterLoaded = false,
                    isRunning = true,
                    isPaused = false,
                    listenTime = "00:00:00",
                    beatsPlayed = 0
                )
            )
        }
        onSyncCastNotification()
        castLoadJob?.cancel()
        castLoadJob = scope.launch {
            when (castRelayClient.registerPull(relayBaseUrl, normalizedJobId, baseUrl)) {
                CastRelayClient.PullResult.Ok -> castController.loadTrack(
                    session = session,
                    fingerprint = normalizedJobId,
                    title = title,
                    artist = artist,
                    tuningParams = resolvedCastTuningParams,
                    vizIndex = currentState.playback.activeVizIndex
                )
                CastRelayClient.PullResult.Forbidden -> postCastError(CAST_PULL_FORBIDDEN_MESSAGE)
                CastRelayClient.PullResult.Guard -> postCastError(CAST_RELAY_GUARD_MESSAGE)
                CastRelayClient.PullResult.BadRequest -> postCastError(CAST_PULL_FAILED_MESSAGE)
                CastRelayClient.PullResult.Unreachable -> postCastError(CAST_RELAY_UNREACHABLE_MESSAGE)
            }
        }
    }

    /**
     * Cast a locally analyzed track: upload its audio + analysis to the relay under [cacheKey], then
     * LOAD by `{fingerprint}`. [cacheKey] is the fingerprint verbatim (see LocalAnalysisService).
     */
    fun castLocalTrack(
        cacheKey: String,
        sourceUri: String,
        title: String? = null,
        artist: String? = null,
        tuningParams: String? = null
    ) {
        castRetryUsed = false
        castLocalTrackInternal(cacheKey, sourceUri, title, artist, tuningParams)
    }

    private fun castLocalTrackInternal(
        cacheKey: String,
        sourceUri: String,
        title: String?,
        artist: String?,
        tuningParams: String?
    ) {
        if (!getState().castEnabled) {
            onCastUnavailable()
            return
        }
        val fingerprint = cacheKey.trim()
        if (fingerprint.isBlank() || sourceUri.isBlank()) {
            return
        }
        val session = castController.getSession() ?: return
        ensureCastStatusListener(session)
        val currentState = getState()
        val displayTitle = if (artist.isNullOrBlank()) {
            title?.takeIf { it.isNotBlank() } ?: "Unknown"
        } else {
            "${title?.takeIf { it.isNotBlank() } ?: "Unknown"} — $artist"
        }
        val resolvedCastTuningParams = TuningParamsCodec.buildCastLoadPayload(
            raw = tuningParams,
            highlightAnchorBranch = currentState.tuning.highlightAnchorBranch
        )
        val vizIndex = currentState.playback.activeVizIndex
        lastCastRequest = CastLoadRequest.Local(fingerprint, sourceUri, title, artist, tuningParams)
        updateState {
            it.copy(
                playback = it.playback.copy(
                    playMode = PlaybackMode.Jukebox,
                    playTitle = displayTitle,
                    trackTitle = title,
                    trackArtist = artist,
                    trackDurationSeconds = null,
                    castTotalBeats = null,
                    castTotalBranches = null,
                    jukeboxAudioMode = JukeboxAudioMode.Off,
                    castAudioModeWireValue = JukeboxAudioMode.Off.wireValue,
                    castSupportedAudioModes = emptyList(),
                    // The relay receiver reports the bare fingerprint in the status jobId field.
                    lastJobId = fingerprint,
                    lastYouTubeId = null,
                    localSourceUri = sourceUri,
                    lastTrackCreatedAtEpochMs = null,
                    castPlaybackState = "loading",
                    isCastLoading = true,
                    // Analysis → transfer handoff: the analysis pipeline is done with its fields.
                    castTransfer = CastTransfer.Uploading(fingerprint, percent = 0),
                    analysisInFlight = false,
                    analysisCalculating = false,
                    analysisProgress = null,
                    analysisMessage = null,
                    analysisErrorMessage = null,
                    deleteEligible = false,
                    playAfterLoaded = false,
                    isRunning = true,
                    isPaused = false,
                    listenTime = "00:00:00",
                    beatsPlayed = 0
                )
            )
        }
        onSyncCastNotification()
        castLoadJob?.cancel()
        castLoadJob = scope.launch {
            performLocalUpload(session, fingerprint, sourceUri, title, artist, resolvedCastTuningParams, vizIndex)
        }
    }

    private suspend fun performLocalUpload(
        session: CastSession,
        fingerprint: String,
        sourceUri: String,
        title: String?,
        artist: String?,
        castTuningParams: String?,
        vizIndex: Int?
    ) {
        var lastReportedPercent = -1
        val source = try {
            buildUploadSource.build(sourceUri, fingerprint) { bytesSent, totalBytes ->
                val percent = castUploadPercent(bytesSent, totalBytes)
                // Runs on OkHttp's IO thread; updateState is a thread-safe StateFlow update. The
                // transfer guard drops stale callbacks after a cancel/replacement/phase change.
                if (percent != null && percent != lastReportedPercent) {
                    lastReportedPercent = percent
                    updateState { current ->
                        val transfer = current.playback.castTransfer
                        if (transfer is CastTransfer.Uploading && transfer.trackId == fingerprint) {
                            current.copy(
                                playback = current.playback.copy(
                                    castTransfer = transfer.copy(percent = percent)
                                )
                            )
                        } else {
                            current
                        }
                    }
                }
            }
        } catch (_: CastSourceUnavailableException) {
            postCastError(CAST_SOURCE_UNAVAILABLE_MESSAGE)
            return
        }
        if (isLocalCastFileTooLarge(source.sizeBytes)) {
            postCastError(CAST_FILE_TOO_LARGE_MESSAGE)
            return
        }
        val result = castRelayClient.uploadForCast(
            relayBaseUrl = relayBaseUrl,
            trackId = fingerprint,
            audioBody = source.audioBody,
            analysisBody = source.analysisBody
        )
        when (result) {
            CastRelayClient.UploadResult.Ok -> {
                updateState {
                    it.copy(
                        playback = it.playback.copy(
                            castTransfer = CastTransfer.WaitingForReceiver(fingerprint)
                        )
                    )
                }
                castController.loadTrack(
                    session = session,
                    fingerprint = fingerprint,
                    title = title,
                    artist = artist,
                    tuningParams = castTuningParams,
                    vizIndex = vizIndex
                )
            }
            CastRelayClient.UploadResult.TooLarge -> postCastError(CAST_FILE_TOO_LARGE_MESSAGE)
            CastRelayClient.UploadResult.Guard -> postCastError(CAST_RELAY_GUARD_MESSAGE)
            CastRelayClient.UploadResult.Unreachable -> postCastError(CAST_RELAY_UNREACHABLE_MESSAGE)
        }
    }

    private fun postCastError(message: String) {
        updateState {
            it.copy(
                playback = it.playback.copy(
                    isCastLoading = false,
                    castTransfer = null,
                    analysisInFlight = false,
                    castPlaybackState = "error",
                    analysisErrorMessage = message
                )
            )
        }
        onSyncCastNotification()
    }

    /** Re-runs the last cast request (upload or pull) after a surfaced error. No-op when none. */
    fun retryLastCastRequest() {
        when (val request = lastCastRequest) {
            is CastLoadRequest.Local -> castLocalTrackInternal(
                request.fingerprint,
                request.sourceUri,
                request.title,
                request.artist,
                request.tuningParams
            )
            is CastLoadRequest.Server -> castTrackIdInternal(
                request.jobId,
                request.title,
                request.artist,
                request.youtubeId,
                request.tuningParams
            )
            null -> Unit
        }
    }

    /**
     * On a `cast_content_not_found` from the receiver, the relay machine restarted and wiped its
     * ephemeral files and pull registrations. Recover once per request: Local re-uploads both files,
     * Server re-POSTs the pull registration; both then re-LOAD. Returns true when a retry launched.
     */
    private fun maybeRetryContentNotFound(): Boolean {
        val request = lastCastRequest ?: return false
        if (castRetryUsed) return false
        castRetryUsed = true
        when (request) {
            is CastLoadRequest.Local -> {
                if (getState().playback.localSourceUri == null) return false
                castLocalTrackInternal(
                    request.fingerprint,
                    request.sourceUri,
                    request.title,
                    request.artist,
                    request.tuningParams
                )
            }
            is CastLoadRequest.Server -> castTrackIdInternal(
                request.jobId,
                request.title,
                request.artist,
                request.youtubeId,
                request.tuningParams
            )
        }
        return true
    }

    fun sendCastCommand(command: String): Boolean {
        if (!getState().castEnabled) {
            onCastUnavailable()
            return false
        }
        return castController.sendCommand(CAST_COMMAND_NAMESPACE, command)
    }

    fun sendCastTuningParams(tuningParams: String?) {
        if (!getState().castEnabled) {
            onCastUnavailable()
            return
        }
        val sent = castController.sendTuningParams(CAST_COMMAND_NAMESPACE, tuningParams)
        if (!sent) {
            onCastUnavailable()
        }
    }

    fun sendCastVisualizationIndex(index: Int) {
        if (!getState().castEnabled) {
            onCastUnavailable()
            return
        }
        val sent = castController.sendVisualizationIndex(CAST_COMMAND_NAMESPACE, index)
        if (!sent) {
            onCastUnavailable()
        }
    }

    private fun ensureCastStatusListener(session: CastSession) {
        castController.ensureStatusListener(session, CAST_COMMAND_NAMESPACE, ::handleCastStatusMessage)
    }

    private fun handleCastStatusMessage(message: String) {
        val status = parseCastStatusMessage(message) ?: return
        if (status.errorCode == CAST_CONTENT_NOT_FOUND_ERROR_CODE && maybeRetryContentNotFound()) {
            return
        }
        updateState { current ->
            val reduced = reduceCastStatus(current, status)
            if (status.errorCode == CAST_TRACK_TOO_LONG_ERROR_CODE ||
                status.errorCode == CAST_TRACK_DURATION_UNKNOWN_ERROR_CODE
            ) {
                reduced.copy(
                    trackLengthLimitErrorMessage = status.error
                        .takeIf { it.isNotBlank() }
                        ?: castTrackLengthLimitErrorMessage()
                )
            } else {
                reduced
            }
        }
        onSyncCastNotification()
    }

    /** Snapshot of the last cast request, kept for the once-only content-not-found retry. */
    private sealed interface CastLoadRequest {
        data class Local(
            val fingerprint: String,
            val sourceUri: String,
            val title: String?,
            val artist: String?,
            val tuningParams: String?
        ) : CastLoadRequest

        data class Server(
            val jobId: String,
            val title: String?,
            val artist: String?,
            val youtubeId: String?,
            val tuningParams: String?
        ) : CastLoadRequest
    }

    private companion object {
        const val CAST_COMMAND_NAMESPACE = "urn:x-cast:com.foreverjukebox.app"
        const val CAST_TRACK_TOO_LONG_ERROR_CODE = "cast_track_too_long"
        const val CAST_TRACK_DURATION_UNKNOWN_ERROR_CODE = "cast_track_duration_unknown"
        const val CAST_CONTENT_NOT_FOUND_ERROR_CODE = "cast_content_not_found"

        const val CAST_FILE_TOO_LARGE_MESSAGE =
            "This track is too large to cast (over 20 MB)."
        const val CAST_RELAY_UNREACHABLE_MESSAGE =
            "Couldn't reach the cast relay. Check your connection and try again."
        const val CAST_RELAY_GUARD_MESSAGE =
            "The cast relay is temporarily full. Try again in a moment."
        const val CAST_SOURCE_UNAVAILABLE_MESSAGE =
            "This track's source file is no longer accessible. Use Add Audio to re-pick the file."
        const val CAST_PULL_FORBIDDEN_MESSAGE =
            "The cast relay doesn't allow this jukebox server. Check the app configuration."
        const val CAST_PULL_FAILED_MESSAGE =
            "Couldn't start casting this track. Try again."
    }
}
