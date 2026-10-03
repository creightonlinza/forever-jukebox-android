package com.foreverjukebox.app.ui

import com.foreverjukebox.app.wubmachine.DubstepAnalysis
import com.foreverjukebox.app.wubmachine.WubRemix
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The player-side half of the Wub Machine: a blocking render that installs the track's remix. */
internal interface WubMachineRenderer {
    fun hasRemix(): Boolean

    /** The remix the player already holds, or null. */
    fun currentRemix(): WubRemix?

    /** Blocks until done; [onProgress] returning false cancels. Null unless the remix is installed. */
    fun render(analysis: DubstepAnalysis, onProgress: (completed: Int, total: Int) -> Boolean): WubRemix?
}

/** Identifies the render a preparing track needs, so a track change restarts it. */
internal data class WubRenderKey(
    val jobId: String?,
    val localSourceUri: String?
)

/** Non-null exactly while the loaded track is waiting on its remix. */
internal fun PlaybackState.wubRenderKey(): WubRenderKey? {
    if (!isPreparingWubMachine()) return null
    return WubRenderKey(jobId = lastJobId, localSourceUri = localSourceUri)
}

/**
 * Keeps the remix player in step with playback state. [sync] is driven by state
 * changes: it starts a render when the loaded track is waiting on one and cancels
 * the render in flight when the track, mode or cast state moves on. Playback is
 * paused and blocked for the duration of a render. A failed render marks the
 * track so the wait ends; [retry] clears the mark and renders again.
 */
internal class WubMachineCoordinator(
    private val scope: CoroutineScope,
    private val renderer: WubMachineRenderer,
    private val getAnalysis: () -> DubstepAnalysis?,
    private val getPlayback: () -> PlaybackState,
    private val updatePlayback: ((PlaybackState) -> PlaybackState) -> Unit,
    private val setPlaybackBlocked: (Boolean) -> Unit,
    private val pausePlayback: () -> Unit,
    private val onReady: (resumePlayback: Boolean) -> Unit,
    private val onFailed: () -> Unit,
    private val audioLoadHold: AudioLoadHold,
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var renderJob: Job? = null
    private var renderKey: WubRenderKey? = null
    private var resumeWhenReady = false
    // Identifies the render whose progress may reach state: a cancelled render
    // keeps running until its next slice and must not report over its
    // replacement. Read from the render thread.
    @Volatile
    private var renderGeneration = 0

    fun sync() {
        val playback = getPlayback()
        val key = playback.wubRenderKey()
        if (key == null) {
            cancelRender()
            return
        }
        if (renderJob != null && renderKey == key) {
            return
        }
        cancelRender()
        startRender(playback, key)
    }

    /** Starts playback once the render in flight completes. */
    fun playWhenReady() {
        if (renderJob != null) {
            resumeWhenReady = true
        }
    }

    /** Clears a failed render and renders again. */
    fun retry() {
        if (getPlayback().wubRenderFailed) {
            updatePlayback { it.copy(wubRenderFailed = false) }
        }
        sync()
    }

    /**
     * Re-arms a render when the player no longer holds the remix state says it
     * has, as happens when the audio was evicted and decoded again.
     */
    fun revalidate() {
        if (getPlayback().wubReady && !renderer.hasRemix()) {
            updatePlayback { it.copy(wubReady = false) }
        }
        sync()
    }

    /** Stops the render in flight and lifts the playback block it holds. */
    fun close() {
        cancelRender()
    }

    private fun startRender(playback: PlaybackState, key: WubRenderKey) {
        renderer.currentRemix()?.let { remix ->
            updatePlayback { it.withRemix(remix) }
            onReady(false)
            return
        }
        resumeWhenReady = playback.isRunning
        setPlaybackBlocked(true)
        pausePlayback()
        updatePlayback { it.copy(wubProgress = 0) }
        renderKey = key
        renderGeneration += 1
        val generation = renderGeneration
        renderJob = scope.launch {
            // Holds the CPU awake: a playlist skip can land on a Wub Machine
            // track with the screen off.
            val remix = audioLoadHold.hold {
                withContext(renderDispatcher) {
                    val analysis = getAnalysis() ?: return@withContext null
                    renderer.render(analysis) { completed, total ->
                        reportProgress(generation, completed, total)
                        isActive
                    }
                }
            }
            finishRender(remix)
        }
    }

    private fun reportProgress(generation: Int, completed: Int, total: Int) {
        if (total <= 0 || generation != renderGeneration) return
        val percent = (completed * PERCENT_MAX / total).coerceIn(0, PERCENT_MAX)
        updatePlayback {
            if (it.wubProgress == null || it.wubProgress == percent) it
            else it.copy(wubProgress = percent)
        }
    }

    private fun finishRender(remix: WubRemix?) {
        renderJob = null
        renderKey = null
        val resume = resumeWhenReady
        resumeWhenReady = false
        setPlaybackBlocked(false)
        if (remix != null) {
            updatePlayback { it.withRemix(remix) }
            onReady(resume)
        } else {
            updatePlayback { it.copy(wubProgress = null, wubRenderFailed = true) }
            onFailed()
        }
    }

    private fun cancelRender() {
        val job = renderJob ?: return
        renderJob = null
        renderKey = null
        resumeWhenReady = false
        renderGeneration += 1
        job.cancel()
        setPlaybackBlocked(false)
        updatePlayback { it.copy(wubProgress = null) }
    }

    private fun PlaybackState.withRemix(remix: WubRemix): PlaybackState = copy(
        wubReady = true,
        wubProgress = null,
        wubRenderFailed = false,
        wubMachine = wubMachine.copy(
            parts = remix.parts,
            peaks = remix.peaks,
            durationSeconds = remix.durationSeconds,
            positionSeconds = 0.0
        )
    )

    private companion object {
        const val PERCENT_MAX = 100
    }
}
