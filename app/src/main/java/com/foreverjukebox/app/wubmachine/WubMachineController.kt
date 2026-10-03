package com.foreverjukebox.app.wubmachine

import com.foreverjukebox.app.audio.BufferedAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Transport for the Wub Machine remix: drives [WubMachinePlayer], reports the playhead while
 * playing and notices the end of the remix. Mirrors WubMachineController.ts in the web repo.
 */
class WubMachineController(
    val player: WubMachinePlayer,
    private val scope: CoroutineScope,
    private val tickMillis: Long = DEFAULT_TICK_MILLIS
) {
    private var tickJob: Job? = null
    private var onTick: ((seconds: Double) -> Unit)? = null
    private var onEnded: (() -> Unit)? = null

    fun setOnTick(handler: ((seconds: Double) -> Unit)?) {
        onTick = handler
    }

    fun setOnEnded(handler: (() -> Unit)?) {
        onEnded = handler
    }

    fun isReady(): Boolean = player.hasRemix()

    fun install(
        job: BufferedAudioPlayer.WubRenderJob,
        partsFor: (durationSeconds: Double) -> List<WubRenderedPart>
    ): Boolean {
        stop()
        return player.install(job, partsFor)
    }

    fun remix(): WubRemix? = player.remix()

    fun setLoop(enabled: Boolean) {
        player.setLoop(enabled)
    }

    fun setVolume(volume: Double) {
        player.setVolume(volume)
    }

    fun setDucking(active: Boolean) {
        player.setDucking(active)
    }

    fun position(): Double = player.position()

    fun durationSeconds(): Double = player.durationSeconds()

    /** Starts at [from] seconds, or resumes from the paused position. True once audio is running. */
    fun play(from: Double? = null): Boolean {
        if (!isReady()) return false
        player.play(from)
        if (!player.isPlaying()) return false
        startTicking()
        return true
    }

    fun pause() {
        player.pause()
        stopTicking()
        onTick?.invoke(player.position())
    }

    fun stop() {
        player.stop()
        stopTicking()
        onTick?.invoke(0.0)
    }

    /** Drops the remix; the next track renders its own. */
    fun clear() {
        stopTicking()
        player.clear()
    }

    fun release() {
        stopTicking()
        player.release()
    }

    private fun startTicking() {
        if (tickJob != null) return
        tickJob = scope.launch {
            while (isActive) {
                val seconds = player.position()
                val duration = player.durationSeconds()
                if (duration > 0 && seconds >= duration) {
                    tickJob = null
                    handleEnded()
                    return@launch
                }
                onTick?.invoke(seconds)
                delay(tickMillis)
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    // The remix played to its end: loop back into the body, or report the end.
    private fun handleEnded() {
        player.stop()
        if (player.loop) {
            play(player.loopRegion.start)
            return
        }
        onTick?.invoke(0.0)
        onEnded?.invoke()
    }

    private companion object {
        const val DEFAULT_TICK_MILLIS = 50L
    }
}
