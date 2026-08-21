package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.canonicalJobId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RemoteTrackLoadCoordinator(
    private val scope: CoroutineScope,
    private val playbackCoordinator: PlaybackCoordinator,
    private val getState: () -> UiState
) {
    private var remoteTrackLoadJob: Job? = null

    fun isRunning(): Boolean = remoteTrackLoadJob?.isActive == true

    fun launch(block: suspend () -> Unit) {
        cancel()
        remoteTrackLoadJob = scope.launch {
            block()
        }
    }

    fun cancel() {
        remoteTrackLoadJob?.cancel()
        remoteTrackLoadJob = null
    }

    suspend fun loadOrPoll(response: TrackAnalysisResult, fallbackJobId: String? = null): Boolean {
        val jobId = canonicalJobId(response.id) ?: canonicalJobId(fallbackJobId) ?: return false
        playbackCoordinator.setLastJobId(jobId)
        playbackCoordinator.updateDeleteEligibility(response)
        if (!getState().playback.audioLoaded && playbackCoordinator.tryLoadCachedTrack(jobId)) {
            return true
        }

        if (response.status == "failed") {
            playbackCoordinator.setAnalysisError(
                ErrorDisplay.format(
                    raw = response.error,
                    errorCode = response.errorCode,
                    sourceProvider = response.sourceProvider,
                    fallback = "Loading failed."
                )
            )
            return true
        }

        if (response.status == "complete" && response.result != null) {
            if (!getState().playback.audioLoaded) {
                val loaded = playbackCoordinator.loadAudioFromJob(jobId)
                if (!loaded) {
                    playbackCoordinator.startPoll(jobId)
                    return true
                }
            }
            playbackCoordinator.applyAnalysisResult(response)
            return true
        }

        playbackCoordinator.startPoll(jobId)
        return true
    }
}
