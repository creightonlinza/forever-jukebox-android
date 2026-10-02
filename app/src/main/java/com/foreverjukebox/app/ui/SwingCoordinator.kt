package com.foreverjukebox.app.ui

import com.foreverjukebox.app.audio.SwingBeat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The player-side half of Swing: a blocking render that installs a swung copy of the track. */
internal interface SwingRenderer {
    fun hasSwingAudio(): Boolean

    /** Blocks until done; [onProgress] returning false cancels. True once the copy is installed. */
    fun renderSwing(beats: List<SwingBeat>, onProgress: (completed: Int, total: Int) -> Boolean): Boolean
}

/** Identifies the render a preparing track needs, so a track change restarts it. */
internal data class SwingRenderKey(
    val jobId: String?,
    val localSourceUri: String?,
    val beatCount: Int
)

/** Non-null exactly while the loaded track is waiting on its swung copy. */
internal fun PlaybackState.swingRenderKey(): SwingRenderKey? {
    if (!isPreparingSwing()) return null
    return SwingRenderKey(
        jobId = lastJobId,
        localSourceUri = localSourceUri,
        beatCount = vizData?.beats?.size ?: 0
    )
}

/**
 * Keeps the player's swung copy in step with playback state. [sync] is driven
 * by state changes: it starts a render when the loaded track is waiting on one
 * and cancels the render in flight when the track, mode or cast state moves on.
 * Playback is paused and blocked for the duration of a render.
 */
internal class SwingCoordinator(
    private val scope: CoroutineScope,
    private val renderer: SwingRenderer,
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
    private var renderKey: SwingRenderKey? = null
    private var resumeWhenReady = false

    fun sync() {
        val playback = getPlayback()
        val key = playback.swingRenderKey()
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

    /**
     * Re-arms a render when the player no longer holds the swung copy state
     * says it has, as happens when the audio was evicted and decoded again.
     */
    fun revalidate() {
        if (getPlayback().swingReady && !renderer.hasSwingAudio()) {
            updatePlayback { it.copy(swingReady = false) }
        }
        sync()
    }

    /** Stops the render in flight and lifts the playback block it holds. */
    fun close() {
        cancelRender()
    }

    private fun startRender(playback: PlaybackState, key: SwingRenderKey) {
        if (renderer.hasSwingAudio()) {
            updatePlayback { it.copy(swingReady = true, swingProgress = null) }
            onReady(false)
            return
        }
        val beats = playback.vizData?.beats.orEmpty().map { SwingBeat(it.start, it.duration) }
        resumeWhenReady = playback.isRunning
        setPlaybackBlocked(true)
        pausePlayback()
        updatePlayback { it.copy(swingProgress = 0) }
        renderKey = key
        renderJob = scope.launch {
            // Holds the CPU awake: a playlist skip can land on a Swing track
            // with the screen off.
            val installed = audioLoadHold.hold {
                withContext(renderDispatcher) {
                    renderer.renderSwing(beats) { completed, total ->
                        reportProgress(completed, total)
                        isActive
                    }
                }
            }
            finishRender(installed)
        }
    }

    private fun reportProgress(completed: Int, total: Int) {
        if (total <= 0) return
        val percent = (completed * PERCENT_MAX / total).coerceIn(0, PERCENT_MAX)
        updatePlayback {
            if (it.swingProgress == null || it.swingProgress == percent) it
            else it.copy(swingProgress = percent)
        }
    }

    private fun finishRender(installed: Boolean) {
        renderJob = null
        renderKey = null
        val resume = resumeWhenReady
        resumeWhenReady = false
        setPlaybackBlocked(false)
        if (installed) {
            updatePlayback { it.copy(swingReady = true, swingProgress = null) }
            onReady(resume)
        } else {
            updatePlayback { it.copy(swingProgress = null) }
            onFailed()
        }
    }

    private fun cancelRender() {
        val job = renderJob ?: return
        renderJob = null
        renderKey = null
        resumeWhenReady = false
        job.cancel()
        setPlaybackBlocked(false)
        updatePlayback { it.copy(swingProgress = null) }
    }

    private companion object {
        const val PERCENT_MAX = 100
    }
}
