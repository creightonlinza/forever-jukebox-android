package com.foreverjukebox.app.ui

import com.foreverjukebox.app.cast.CastUploadClient
import com.google.android.gms.cast.framework.CastSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class CastPlaybackCoordinator(
    private val castController: CastController,
    private val getState: () -> UiState,
    private val updateState: ((UiState) -> UiState) -> Unit,
    private val onCastUnavailable: () -> Unit,
    private val onSyncCastNotification: () -> Unit,
    private val castTrackLengthLimitErrorMessage: () -> String,
    private val scope: CoroutineScope,
    private val castUploadClient: CastUploadClient,
    private val buildUploadSource: CastLocalUploadSourceFactory,
    private val relayBaseUrl: String
) {
    /** Relay session reused for the life of the Cast connection; dropped on disconnect/endSession. */
    @Volatile
    var currentSessionId: String? = null
        private set

    private var lastLocalCastRequest: LocalCastRequest? = null
    private var localCastRetryUsed = false

    fun resetStatusListener() {
        castController.resetStatusListener()
    }

    fun endSession() {
        currentSessionId = null
        lastLocalCastRequest = null
        castController.endSession()
    }

    /** Clears the held relay session so the next Local cast starts a fresh one (on disconnect). */
    fun clearRelaySession() {
        currentSessionId = null
        lastLocalCastRequest = null
    }

    fun requestCastStatus() {
        if (!getState().castEnabled) {
            return
        }
        val session = castController.getSession() ?: return
        ensureCastStatusListener(session)
        castController.requestStatus(session, CAST_COMMAND_NAMESPACE)
    }

    fun castTrackId(
        jobId: String,
        title: String? = null,
        artist: String? = null,
        youtubeId: String? = null,
        tuningParams: String? = null
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
                    analysisInFlight = true,
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
        castController.loadTrack(
            session = session,
            baseUrl = baseUrl,
            jobId = normalizedJobId,
            title = title,
            artist = artist,
            tuningParams = resolvedCastTuningParams,
            vizIndex = currentState.playback.activeVizIndex
        )
    }

    /**
     * Cast a locally analyzed track: upload its audio + analysis to the relay, then LOAD by
     * `{sessionId, fingerprint}`. [cacheKey] is the fingerprint verbatim (see LocalAnalysisService).
     */
    fun castLocalTrack(
        cacheKey: String,
        sourceUri: String,
        title: String? = null,
        artist: String? = null,
        tuningParams: String? = null
    ) {
        localCastRetryUsed = false
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
        lastLocalCastRequest = LocalCastRequest(fingerprint, sourceUri, title, artist, tuningParams)
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
                    analysisInFlight = true,
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
        scope.launch {
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
        val source = try {
            buildUploadSource.build(sourceUri, fingerprint)
        } catch (_: CastSourceUnavailableException) {
            postCastError(CAST_SOURCE_UNAVAILABLE_MESSAGE)
            return
        }
        if (isLocalCastFileTooLarge(source.sizeBytes)) {
            postCastError(CAST_FILE_TOO_LARGE_MESSAGE)
            return
        }
        val result = castUploadClient.uploadForCast(
            baseUrl = relayBaseUrl,
            existingSessionId = currentSessionId,
            fingerprint = fingerprint,
            audioBody = source.audioBody,
            analysisBody = source.analysisBody
        )
        when (result) {
            is CastUploadClient.UploadResult.Success -> {
                currentSessionId = result.sessionId
                castController.loadLocalTrack(
                    session = session,
                    sessionId = result.sessionId,
                    fingerprint = fingerprint,
                    title = title,
                    artist = artist,
                    tuningParams = castTuningParams,
                    vizIndex = vizIndex
                )
            }
            CastUploadClient.UploadResult.TooLarge -> postCastError(CAST_FILE_TOO_LARGE_MESSAGE)
            CastUploadClient.UploadResult.Guard -> postCastError(CAST_RELAY_GUARD_MESSAGE)
            CastUploadClient.UploadResult.Unreachable -> postCastError(CAST_RELAY_UNREACHABLE_MESSAGE)
        }
    }

    private fun postCastError(message: String) {
        updateState {
            it.copy(
                playback = it.playback.copy(
                    isCastLoading = false,
                    analysisInFlight = false,
                    castPlaybackState = "error",
                    analysisErrorMessage = message
                )
            )
        }
        onSyncCastNotification()
    }

    /**
     * On a `cast_content_not_found` from the receiver (its GET 404'd after a mid-load relay wipe),
     * recreate the session and re-upload + re-LOAD once. Returns true when a retry was launched.
     */
    private fun maybeRetryLocalContentNotFound(): Boolean {
        val request = lastLocalCastRequest ?: return false
        if (getState().playback.localSourceUri == null) return false
        if (localCastRetryUsed) return false
        localCastRetryUsed = true
        currentSessionId = null
        castLocalTrackInternal(
            request.fingerprint,
            request.sourceUri,
            request.title,
            request.artist,
            request.tuningParams
        )
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
        if (status.errorCode == CAST_CONTENT_NOT_FOUND_ERROR_CODE && maybeRetryLocalContentNotFound()) {
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

    private data class LocalCastRequest(
        val fingerprint: String,
        val sourceUri: String,
        val title: String?,
        val artist: String?,
        val tuningParams: String?
    )

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
    }
}
