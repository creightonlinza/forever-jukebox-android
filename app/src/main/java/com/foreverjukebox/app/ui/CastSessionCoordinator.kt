package com.foreverjukebox.app.ui

import com.foreverjukebox.app.playback.PlaybackController

internal data class PreservedCastTrack(
    val jobId: String,
    val youtubeId: String?,
    val title: String?,
    val artist: String?,
    val audioMode: JukeboxAudioMode
)

internal fun capturePreservedCastTrack(playback: PlaybackState): PreservedCastTrack? {
    val jobId = playback.lastJobId ?: return null
    val shouldAutoCast = playback.audioLoaded && playback.analysisLoaded
    if (!shouldAutoCast) {
        return null
    }
    return PreservedCastTrack(
        jobId = jobId,
        youtubeId = playback.lastYouTubeId,
        title = playback.trackTitle,
        artist = playback.trackArtist,
        audioMode = playback.jukeboxAudioMode
    )
}

internal fun stateAfterCastDisconnect(state: UiState): UiState {
    return state.copy(
        playback = state.playback.copy(
            isCasting = false,
            castDeviceName = null,
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

        val preservedTrack = capturePreservedCastTrack(playback)
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
                tuningParams = if (preservedTrack.audioMode == JukeboxAudioMode.Off) {
                    null
                } else {
                    TuningParamsCodec.buildAudioModeParam(preservedTrack.audioMode)
                }
            )
        }
        castPlaybackCoordinator.requestCastStatus()
    }

    private fun handleCastingDisconnected() {
        if (!getState().playback.isCasting) {
            return
        }
        updateState(::stateAfterCastDisconnect)
        castPlaybackCoordinator.resetStatusListener()
        cancelServerTrackLoad()
        playbackCoordinator.resetForNewTrack()
        applyActiveTab(TabId.Top, true)
    }
}
