package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.data.LOCAL_TRACK_ID_PREFIX
import com.foreverjukebox.app.playback.PlaybackController

internal sealed interface PreservedCastTrack {
    val title: String?
    val artist: String?
    val audioMode: JukeboxAudioMode
    val tuningParams: String?

    data class Server(
        val jobId: String,
        val youtubeId: String?,
        override val title: String?,
        override val artist: String?,
        override val audioMode: JukeboxAudioMode,
        override val tuningParams: String? = null
    ) : PreservedCastTrack

    data class Local(
        val cacheKey: String,
        val sourceUri: String,
        override val title: String?,
        override val artist: String?,
        override val audioMode: JukeboxAudioMode,
        override val tuningParams: String? = null
    ) : PreservedCastTrack
}

internal fun capturePreservedCastTrack(
    playback: PlaybackState,
    engineTuningParams: String? = null
): PreservedCastTrack? {
    val shouldAutoCast = playback.audioLoaded && playback.analysisLoaded
    if (!shouldAutoCast) {
        return null
    }
    // Engine tuning and the audio mode only apply to jukebox tracks; an autocanonizer track
    // is handed off to the receiver as a jukebox track with default tuning. The state field
    // holds the user's jukebox audio-mode selection even during autocanonizer, so it must
    // not reach the receiver from a non-jukebox session.
    val tuningParams = engineTuningParams?.takeIf { playback.playMode == PlaybackMode.Jukebox }
    val audioMode = castHandoffAudioMode(playback)
    val localSourceUri = playback.localSourceUri
    if (localSourceUri != null) {
        val cacheKey = playback.lastJobId
            ?.removePrefix(LOCAL_TRACK_ID_PREFIX)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return PreservedCastTrack.Local(
            cacheKey = cacheKey,
            sourceUri = localSourceUri,
            title = playback.trackTitle,
            artist = playback.trackArtist,
            audioMode = audioMode,
            tuningParams = tuningParams
        )
    }
    val jobId = playback.lastJobId ?: return null
    return PreservedCastTrack.Server(
        jobId = jobId,
        youtubeId = playback.lastYouTubeId,
        title = playback.trackTitle,
        artist = playback.trackArtist,
        audioMode = audioMode,
        tuningParams = tuningParams
    )
}

private fun castHandoffAudioMode(playback: PlaybackState): JukeboxAudioMode {
    return if (playback.playMode == PlaybackMode.Jukebox) {
        playback.jukeboxAudioMode
    } else {
        JukeboxAudioMode.Off
    }
}

internal sealed interface PendingCastSelection {
    /** A load that already resolved its job id: the receiver can pull it directly. */
    data class ByJobId(val track: PreservedCastTrack.Server) : PendingCastSelection

    /** A load still keyed by its source: the job id takes a server round trip to resolve. */
    data class BySource(
        val youtubeId: String,
        val title: String?,
        val artist: String?,
        val tuningParams: String?
    ) : PendingCastSelection
}

/**
 * The track a running load was fetching, so connecting mid-load sends that track to the receiver
 * instead of dropping it. Only an in-flight load qualifies — a finished or failed load leaves its
 * ids in playback state, and casting those would start a track the user never asked for. Local
 * tracks are excluded: the relay needs an analysis artifact that does not exist until the local
 * analysis completes, and that path hands itself off.
 */
internal fun capturePendingCastSelection(
    playback: PlaybackState,
    pendingTuningParams: String? = null
): PendingCastSelection? {
    if (!playback.isLoading() || playback.localSourceUri != null) {
        return null
    }
    val youtubeId = playback.lastYouTubeId?.takeIf { it.isNotBlank() }
    val jobId = playback.lastJobId?.takeIf { it.isNotBlank() }
    if (jobId != null) {
        return PendingCastSelection.ByJobId(
            PreservedCastTrack.Server(
                jobId = jobId,
                youtubeId = youtubeId,
                title = playback.trackTitle,
                artist = playback.trackArtist,
                audioMode = castHandoffAudioMode(playback),
                tuningParams = pendingTuningParams
            )
        )
    }
    if (youtubeId != null) {
        return PendingCastSelection.BySource(
            youtubeId = youtubeId,
            title = playback.trackTitle,
            artist = playback.trackArtist,
            tuningParams = pendingTuningParams
        )
    }
    return null
}

internal fun stateAfterCastDisconnect(state: UiState): UiState {
    return state.copy(
        playback = state.playback.copy(
            isCasting = false,
            castDeviceName = null,
            castTransfer = null,
            localSourceUri = null,
            castAudioModeWireValue = JukeboxAudioMode.Off.wireValue,
            castSupportedAudioModes = emptyList()
        ),
        playlist = state.playlist.deactivate()
    )
}

internal class CastSessionCoordinator(
    private val controller: PlaybackController,
    private val castPlaybackCoordinator: CastPlaybackCoordinator,
    private val playbackCoordinator: PlaybackCoordinator,
    private val cancelServerTrackLoad: () -> Unit,
    private val castPendingSource: (PendingCastSelection.BySource) -> Unit,
    private val isLocalAnalysisRunning: () -> Boolean,
    private val getState: () -> UiState,
    private val updateState: ((UiState) -> UiState) -> Unit,
    private val applyActiveTab: (TabId, Boolean) -> Unit,
    private val syncCastNotification: () -> Unit
) {
    fun setCastingConnected(isConnected: Boolean, deviceName: String? = null) {
        if (isConnected) {
            handleCastingConnected(deviceName)
            return
        }
        handleCastingDisconnected()
    }

    fun stopCasting() {
        castPlaybackCoordinator.endSession()
        setCastingConnected(false)
    }

    fun requestCastStatus() {
        castPlaybackCoordinator.requestCastStatus()
    }

    private fun handleCastingConnected(deviceName: String?) {
        val state = getState()
        val playback = state.playback
        if (playback.playMode == PlaybackMode.Autocanonizer) {
            controller.autocanonizer.stop()
            controller.stopExternalPlayback()
            playbackCoordinator.stopListenTimer()
            playbackCoordinator.applyPlaybackMode(PlaybackMode.Jukebox)
        }
        if (playback.isCasting) {
            updateState {
                it.copy(
                    playback = it.playback.copy(
                        castDeviceName = deviceName
                    )
                )
            }
            castPlaybackCoordinator.resetStatusListener()
            castPlaybackCoordinator.requestCastStatus()
            syncCastNotification()
            return
        }

        // Snapshot engine tuning here: resetForNewTrack below restores the default config,
        // and the preserved track should keep playing on the receiver with its tuning.
        val preservedTrack = capturePreservedCastTrack(
            playback = playback,
            engineTuningParams = playbackCoordinator.buildTuningParamsString()
        )
        // A running local analysis owns the loading screen it is driving and hands its own
        // artifact to the receiver on completion, so it is left alone.
        val localAnalysisRunning = state.appMode == AppMode.Local && isLocalAnalysisRunning()
        val pendingSelection = if (preservedTrack == null && !localAnalysisRunning) {
            capturePendingCastSelection(
                playback = playback,
                pendingTuningParams = playbackCoordinator.getPendingTuningParams()
            )
        } else {
            null
        }
        updateState {
            it.copy(
                playback = it.playback.copy(
                    isCasting = true,
                    castDeviceName = deviceName
                )
            )
        }
        castPlaybackCoordinator.resetStatusListener()
        cancelServerTrackLoad()
        if (localAnalysisRunning) {
            syncCastNotification()
        } else {
            playbackCoordinator.resetForNewTrack()
        }

        when {
            preservedTrack != null -> castPreservedTrack(preservedTrack)
            pendingSelection is PendingCastSelection.ByJobId -> {
                castPreservedTrack(pendingSelection.track)
            }
            pendingSelection is PendingCastSelection.BySource -> {
                // Resolving the job id takes a server round trip, and the reset above left the
                // cast screen empty; this gives it something to show until the LOAD goes out.
                playbackCoordinator.setAnalysisQueued(null, "Preparing cast...")
                castPendingSource(pendingSelection)
            }
        }
        castPlaybackCoordinator.requestCastStatus()
    }

    private fun castPreservedTrack(track: PreservedCastTrack) {
        // Engine tuning already carries the audio mode (`am`) when one is active; the
        // fallback keeps the audio-mode-only handoff for tracks with no other tuning.
        val tuningParams = track.tuningParams
            ?: if (track.audioMode == JukeboxAudioMode.Off) {
                null
            } else {
                TuningParamsCodec.buildAudioModeParam(track.audioMode)
            }
        when (track) {
            is PreservedCastTrack.Server -> {
                updateState {
                    it.copy(
                        playback = it.playback.copy(
                            lastYouTubeId = track.youtubeId,
                            lastJobId = track.jobId,
                            trackTitle = track.title,
                            trackArtist = track.artist
                        )
                    )
                }
                castPlaybackCoordinator.castTrackId(
                    jobId = track.jobId,
                    title = track.title,
                    artist = track.artist,
                    youtubeId = track.youtubeId,
                    tuningParams = tuningParams
                )
            }
            is PreservedCastTrack.Local -> {
                castPlaybackCoordinator.castLocalTrack(
                    cacheKey = track.cacheKey,
                    sourceUri = track.sourceUri,
                    title = track.title,
                    artist = track.artist,
                    tuningParams = tuningParams
                )
            }
        }
    }

    private fun handleCastingDisconnected() {
        if (!getState().playback.isCasting) {
            return
        }
        updateState(::stateAfterCastDisconnect)
        castPlaybackCoordinator.clearPendingCastRequest()
        castPlaybackCoordinator.resetStatusListener()
        cancelServerTrackLoad()
        playbackCoordinator.resetForNewTrack()
        applyActiveTab(TabId.Top, true)
    }
}
