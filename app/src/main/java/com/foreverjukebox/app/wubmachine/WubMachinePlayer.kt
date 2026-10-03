package com.foreverjukebox.app.wubmachine

import com.foreverjukebox.app.audio.BufferedAudioPlayer

/** A rendered remix: its parts, length and waveform, as the player reports them. */
data class WubRemix(
    val parts: List<WubRenderedPart>,
    val durationSeconds: Double,
    val peaks: List<Float>
)

const val WUB_PEAK_BINS = 2000

/**
 * Plays a rendered Wub Machine remix start to finish on its own player, so the jukebox player
 * and its audio modes never touch the remix. With looping on, the body (everything between the
 * intro and the ending) repeats seamlessly.
 */
class WubMachinePlayer(private val player: BufferedAudioPlayer = BufferedAudioPlayer()) {
    /** The player holding the remix PCM, for clones that render it offline. */
    val audioPlayer: BufferedAudioPlayer get() = player

    var parts: List<WubRenderedPart> = emptyList()
        private set
    var loopRegion: WubLoopRegion = WubLoopRegion(0.0, 0.0)
        private set
    var loop: Boolean = false
        private set

    /**
     * Loads the finished render into this player and lays its parts out over the resulting
     * duration; false leaves the player empty.
     */
    fun install(
        job: BufferedAudioPlayer.WubRenderJob,
        partsFor: (durationSeconds: Double) -> List<WubRenderedPart>
    ): Boolean {
        player.stop()
        if (!job.installInto(player)) {
            clear()
            return false
        }
        val duration = durationSeconds()
        parts = partsFor(duration)
        loopRegion = wubLoopRegion(parts, duration)
        applyLoop()
        return true
    }

    /** The rendered remix as the UI describes it, or null while the player is empty. */
    fun remix(): WubRemix? {
        if (!hasRemix()) return null
        return WubRemix(
            parts = parts,
            durationSeconds = durationSeconds(),
            peaks = player.computePeaks(WUB_PEAK_BINS).toList()
        )
    }

    fun hasRemix(): Boolean = player.hasAudio()

    fun durationSeconds(): Double = player.getDurationSeconds() ?: 0.0

    fun position(): Double = player.getCurrentTime()

    fun isPlaying(): Boolean = player.isPlaying()

    fun setLoop(enabled: Boolean) {
        loop = enabled
        applyLoop()
    }

    /** Starts at [from] seconds, or resumes from the current position. */
    fun play(from: Double? = null) {
        if (!hasRemix()) return
        if (from != null) {
            player.seek(from.coerceIn(0.0, durationSeconds()))
        }
        applyLoop()
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun stop() {
        player.stop()
    }

    fun seek(seconds: Double) {
        player.seek(seconds.coerceIn(0.0, durationSeconds()))
        applyLoop()
    }

    fun setVolume(volume: Double) {
        player.setGain(volume)
    }

    fun setDucking(active: Boolean) {
        player.setDucking(active)
    }

    fun describeLastStartFailure(): String? = player.describeLastStartFailure()

    fun clear() {
        player.clear()
        parts = emptyList()
        loopRegion = WubLoopRegion(0.0, 0.0)
    }

    fun release() {
        player.release()
    }

    // The native loop only engages from inside the body: a position already in the ending plays
    // out, and the controller restarts from the loop start when it ends.
    private fun applyLoop() {
        if (!hasRemix()) return
        player.setLoopRegion(
            startSeconds = loopRegion.start,
            endSeconds = loopRegion.end,
            enabled = loop && position() < loopRegion.end
        )
    }
}
