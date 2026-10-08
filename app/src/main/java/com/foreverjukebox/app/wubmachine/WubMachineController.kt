package com.foreverjukebox.app.wubmachine

import com.foreverjukebox.app.audio.BufferedAudioPlayer
import com.foreverjukebox.app.playback.ExternalTransport
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
) : ExternalTransport {
    // Serializes transport calls (main thread) with the tick coroutine, which reads the
    // player's native handle: a clear or release must not free that handle mid-read, and an
    // end-of-remix restart must not race a pause.
    private val lock = Any()
    private var tickJob: Job? = null
    @Volatile
    private var running = false
    @Volatile
    private var paused = false
    // Bumped whenever the remix is dropped, so a render that started against an earlier track
    // cannot install its result over the player afterwards.
    @Volatile
    private var remixGeneration = 0
    private var onTick: ((seconds: Double) -> Unit)? = null
    private var onEnded: (() -> Unit)? = null

    fun setOnTick(handler: ((seconds: Double) -> Unit)?) {
        onTick = handler
    }

    fun setOnEnded(handler: (() -> Unit)?) {
        onEnded = handler
    }

    fun isReady(): Boolean = player.hasRemix()

    override fun isRunning(): Boolean = running

    override fun isPaused(): Boolean = paused

    /** Identifies the remix slot a render targets; see [install]. */
    fun remixGeneration(): Int = remixGeneration

    /**
     * Installs a finished render, unless the remix was dropped (see [clear]) since [generation]
     * was read: a render for a track that is no longer loaded leaves the player untouched.
     */
    fun install(
        job: BufferedAudioPlayer.WubRenderJob,
        generation: Int,
        partsFor: (durationSeconds: Double) -> List<WubRenderedPart>
    ): Boolean {
        val installed = synchronized(lock) {
            if (generation != remixGeneration) return false
            haltLocked()
            player.stop()
            player.install(job, partsFor)
        }
        onTick?.invoke(0.0)
        return installed
    }

    fun remix(): WubRemix? = player.remix()

    fun setLoop(enabled: Boolean) {
        synchronized(lock) { player.setLoop(enabled) }
    }

    fun setVolume(volume: Double) {
        player.setVolume(volume)
    }

    fun setDucking(active: Boolean) {
        player.setDucking(active)
    }

    fun position(): Double = synchronized(lock) { player.position() }

    fun durationSeconds(): Double = synchronized(lock) { player.durationSeconds() }

    /** Starts at [from] seconds, or resumes from the paused position. True once audio is running. */
    fun play(from: Double? = null): Boolean {
        synchronized(lock) {
            if (!isReady()) return false
            player.play(from)
            if (!player.isPlaying()) return false
            running = true
            paused = false
            startTickingLocked()
        }
        return true
    }

    override fun resume(): Boolean {
        if (!paused) return false
        return play(null)
    }

    override fun pause() {
        val position = synchronized(lock) {
            stopTickingLocked()
            player.pause()
            if (running) {
                running = false
                paused = true
            }
            player.position()
        }
        onTick?.invoke(position)
    }

    override fun stop() {
        synchronized(lock) {
            haltLocked()
            player.stop()
        }
        onTick?.invoke(0.0)
    }

    /** Drops the remix; the next track renders its own. */
    fun clear() {
        synchronized(lock) {
            remixGeneration += 1
            haltLocked()
            player.clear()
        }
    }

    fun release() {
        synchronized(lock) {
            remixGeneration += 1
            haltLocked()
            player.release()
        }
    }

    private fun haltLocked() {
        stopTickingLocked()
        running = false
        paused = false
    }

    private fun startTickingLocked() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                when (val step = synchronized(lock) { stepLocked() }) {
                    is TickStep.Playing -> onTick?.invoke(step.seconds)
                    TickStep.Ended -> {
                        onTick?.invoke(0.0)
                        onEnded?.invoke()
                        return@launch
                    }
                    TickStep.Halted -> return@launch
                }
                delay(tickMillis)
            }
        }
    }

    private fun stopTickingLocked() {
        tickJob?.cancel()
        tickJob = null
    }

    // One tick of the playhead. At the end of the remix a looping track restarts from the top;
    // otherwise, or when the restart does not produce audio, the end is reported.
    private fun stepLocked(): TickStep {
        if (!running) return TickStep.Halted
        val seconds = player.position()
        val duration = player.durationSeconds()
        if (duration <= 0 || seconds < duration) return TickStep.Playing(seconds)
        player.stop()
        if (player.loop) {
            player.play(0.0)
            if (player.isPlaying()) return TickStep.Playing(0.0)
        }
        running = false
        paused = false
        return TickStep.Ended
    }

    private sealed interface TickStep {
        data class Playing(val seconds: Double) : TickStep
        data object Ended : TickStep
        data object Halted : TickStep
    }

    private companion object {
        const val DEFAULT_TICK_MILLIS = 50L
    }
}
