package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AnalysisResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ServerTrackLoadCoordinator(
    private val scope: CoroutineScope,
    private val playbackCoordinator: PlaybackCoordinator,
    private val getState: () -> UiState
) {
    private var serverTrackLoadJob: Job? = null

    fun isRunning(): Boolean = serverTrackLoadJob?.isActive == true

    fun launch(block: suspend () -> Unit) {
        cancel()
        serverTrackLoadJob = scope.launch {
            block()
        }
    }

    fun cancel() {
        serverTrackLoadJob?.cancel()
        serverTrackLoadJob = null
    }

    suspend fun loadOrPoll(response: AnalysisResponse, fallbackJobId: String? = null): Boolean {
        val jobId = response.id ?: fallbackJobId ?: return false
        playbackCoordinator.setLastJobId(jobId)
        playbackCoordinator.updateDeleteEligibility(response)

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
