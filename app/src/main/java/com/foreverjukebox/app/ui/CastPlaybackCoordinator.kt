package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.SOURCE_PROVIDER_YOUTUBE
import com.foreverjukebox.app.data.buildJobStableTrackId
import com.foreverjukebox.app.data.buildSourceStableTrackId
import com.foreverjukebox.app.data.canonicalStableTrackId
import com.foreverjukebox.app.data.parseTrackStableId
import com.foreverjukebox.app.data.sourceProviderFromRaw
import com.google.android.gms.cast.framework.CastSession

class CastPlaybackCoordinator(
    private val castController: CastController,
    private val getState: () -> UiState,
    private val updateState: ((UiState) -> UiState) -> Unit,
    private val onCastUnavailable: () -> Unit,
    private val onSyncCastNotification: (PlaybackState) -> Unit,
    private val castTrackLengthLimitErrorMessage: () -> String
) {
    fun resetStatusListener() {
        castController.resetStatusListener()
    }

    fun endSession() {
        castController.endSession()
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
        sourceProvider: String? = null,
        sourceId: String? = null,
        stableTrackId: String? = null,
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
        val session = castController.getSession()
        if (session == null) {
            return
        }
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
        val normalizedProvider = sourceProviderFromRaw(sourceProvider)
        val normalizedSourceId = sourceId?.trim().orEmpty().ifBlank { null }
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isBlank()) {
            return
        }
        val resolvedStableTrackId = canonicalStableTrackId(stableTrackId)
            ?: when {
                normalizedProvider != null && normalizedSourceId != null ->
                    buildSourceStableTrackId(normalizedProvider, normalizedSourceId)
                else -> buildJobStableTrackId(normalizedJobId)
            }
        val parsedIdentity = parseTrackStableId(resolvedStableTrackId)
        val resolvedProvider = parsedIdentity?.sourceProvider
        val resolvedSourceId = parsedIdentity?.sourceId
        val resolvedYoutubeId = when {
            resolvedProvider == SOURCE_PROVIDER_YOUTUBE -> resolvedSourceId
            else -> null
        }
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
                    lastSourceProvider = resolvedProvider,
                    lastSourceId = resolvedSourceId,
                    lastStableTrackId = resolvedStableTrackId,
                    lastYouTubeId = resolvedYoutubeId,
                    lastTrackCreatedAtEpochMs = null,
                    castPlaybackState = "loading",
                    lastJobId = normalizedJobId,
                    isCastLoading = true,
                    analysisInFlight = true,
                    analysisErrorMessage = null,
                    deleteEligible = false,
                    isRunning = true,
                    isPaused = false,
                    listenTime = "00:00:00",
                    beatsPlayed = 0
                )
            )
        }
        onSyncCastNotification(getState().playback)
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
        onSyncCastNotification(getState().playback)
    }

    private companion object {
        const val CAST_COMMAND_NAMESPACE = "urn:x-cast:com.foreverjukebox.app"
        const val CAST_TRACK_TOO_LONG_ERROR_CODE = "cast_track_too_long"
        const val CAST_TRACK_DURATION_UNKNOWN_ERROR_CODE = "cast_track_duration_unknown"
    }
}
