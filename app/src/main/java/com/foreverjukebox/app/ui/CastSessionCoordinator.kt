package com.foreverjukebox.app.ui

import android.app.Application
import com.foreverjukebox.app.playback.ForegroundPlaybackService
import com.foreverjukebox.app.playback.PlaybackController
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class PreservedCastTrack(
    val jobId: String,
    val sourceProvider: String?,
    val sourceId: String?,
    val stableTrackId: String?,
    val youtubeId: String?,
    val title: String?,
    val artist: String?
)

internal fun capturePreservedCastTrack(playback: PlaybackState): PreservedCastTrack? {
    val jobId = playback.lastJobId ?: return null
    val shouldAutoCast = playback.audioLoaded && playback.analysisLoaded
    if (!shouldAutoCast) {
        return null
    }
    return PreservedCastTrack(
        jobId = jobId,
        sourceProvider = playback.lastSourceProvider,
        sourceId = playback.lastSourceId,
        stableTrackId = playback.stableTrackIdOrNull(),
        youtubeId = playback.lastYouTubeId,
        title = playback.trackTitle,
        artist = playback.trackArtist
    )
}

class CastSessionCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val controller: PlaybackController,
    private val castPlaybackCoordinator: CastPlaybackCoordinator,
    private val playbackCoordinator: PlaybackCoordinator,
    private val serverTrackLoadCoordinator: ServerTrackLoadCoordinator,
    private val getState: () -> UiState,
    private val updateState: ((UiState) -> UiState) -> Unit,
    private val applyActiveTab: (TabId, Boolean) -> Unit,
    private val notifyCastUnavailable: () -> Unit,
    private val setPlaybackMode: (PlaybackMode) -> Unit,
    private val syncCastNotification: (PlaybackState) -> Unit,
    private val showToast: suspend (String) -> Unit
) {
    fun castCurrentTrack() {
        if (!getState().castEnabled) {
            notifyCastUnavailable()
            return
        }
        if (getState().playback.playMode == PlaybackMode.Autocanonizer) {
            setPlaybackMode(PlaybackMode.Jukebox)
        }
        val baseUrl = getState().baseUrl.trim()
        if (baseUrl.isBlank()) {
            scope.launch { showToast("Set a base URL before casting.") }
            return
        }
        val playback = getState().playback
        val jobId = playback.lastJobId
        if (jobId.isNullOrBlank()) {
            scope.launch { showToast("Load a track before casting.") }
            return
        }
        val castContext = try {
            CastContext.getSharedInstance(application)
        } catch (_: Exception) {
            scope.launch { showToast("Cast is unavailable on this device.") }
            return
        }
        val session = castContext.sessionManager.currentCastSession
        if (session == null) {
            scope.launch { showToast("Connect to a Cast device first.") }
            return
        }
        castPlaybackCoordinator.castTrackId(
            jobId = jobId,
            title = playback.trackTitle,
            artist = playback.trackArtist,
            sourceProvider = playback.lastSourceProvider,
            sourceId = playback.lastSourceId,
            stableTrackId = playback.stableTrackIdOrNull()
        )
    }

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
            syncCastNotification(getState().playback)
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
        serverTrackLoadCoordinator.cancel()
        playbackCoordinator.resetForNewTrack()

        if (preservedTrack != null) {
            updateState {
                it.copy(
                    playback = it.playback.copy(
                        lastSourceProvider = preservedTrack.sourceProvider,
                        lastSourceId = preservedTrack.sourceId,
                        lastStableTrackId = preservedTrack.stableTrackId,
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
                sourceProvider = preservedTrack.sourceProvider,
                sourceId = preservedTrack.sourceId,
                stableTrackId = preservedTrack.stableTrackId
            )
        }
        castPlaybackCoordinator.requestCastStatus()
    }

    private fun handleCastingDisconnected() {
        if (!getState().playback.isCasting) {
            return
        }
        updateState {
            it.copy(
                playback = it.playback.copy(
                    isCasting = false,
                    castDeviceName = null
                )
            )
        }
        castPlaybackCoordinator.resetStatusListener()
        serverTrackLoadCoordinator.cancel()
        playbackCoordinator.resetForNewTrack()
        applyActiveTab(TabId.Top, true)
        ForegroundPlaybackService.stop(application)
    }
}
