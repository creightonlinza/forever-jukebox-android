package com.foreverjukebox.app.ui

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
    // Engine tuning only applies to jukebox tracks; an autocanonizer track is handed off
    // to the receiver as a jukebox track with default tuning.
    val tuningParams = engineTuningParams?.takeIf { playback.playMode == PlaybackMode.Jukebox }
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
            audioMode = playback.jukeboxAudioMode,
            tuningParams = tuningParams
        )
    }
    val jobId = playback.lastJobId ?: return null
    return PreservedCastTrack.Server(
        jobId = jobId,
        youtubeId = playback.lastYouTubeId,
        title = playback.trackTitle,
        artist = playback.trackArtist,
        audioMode = playback.jukeboxAudioMode,
        tuningParams = tuningParams
    )
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

class CastSessionCoordinator(
    private val controller: PlaybackController,
    private val castPlaybackCoordinator: CastPlaybackCoordinator,
    private val playbackCoordinator: PlaybackCoordinator,
    private val cancelServerTrackLoad: () -> Unit,
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
        val playback = getState().playback
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
        playbackCoordinator.resetForNewTrack()

        if (preservedTrack != null) {
            // Engine tuning already carries the audio mode (`am`) when one is active; the
            // fallback keeps the audio-mode-only handoff for tracks with no other tuning.
            val tuningParams = preservedTrack.tuningParams
                ?: if (preservedTrack.audioMode == JukeboxAudioMode.Off) {
                    null
                } else {
                    TuningParamsCodec.buildAudioModeParam(preservedTrack.audioMode)
                }
            when (preservedTrack) {
                is PreservedCastTrack.Server -> {
                    updateState {
                        it.copy(
                            playback = it.playback.copy(
                                lastYouTubeId = preservedTrack.youtubeId,
                                lastJobId = preservedTrack.jobId,
                                trackTitle = preservedTrack.title,
                                trackArtist = preservedTrack.artist
                            )
                        )
                    }
                    castPlaybackCoordinator.castTrackId(
                        jobId = preservedTrack.jobId,
                        title = preservedTrack.title,
                        artist = preservedTrack.artist,
                        youtubeId = preservedTrack.youtubeId,
                        tuningParams = tuningParams
                    )
                }
                is PreservedCastTrack.Local -> {
                    castPlaybackCoordinator.castLocalTrack(
                        cacheKey = preservedTrack.cacheKey,
                        sourceUri = preservedTrack.sourceUri,
                        title = preservedTrack.title,
                        artist = preservedTrack.artist,
                        tuningParams = tuningParams
                    )
                }
            }
        }
        castPlaybackCoordinator.requestCastStatus()
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
