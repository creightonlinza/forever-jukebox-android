package com.foreverjukebox.app.export

import android.content.Context
import com.foreverjukebox.app.audio.BufferedAudioCowbellHitScheduler
import com.foreverjukebox.app.audio.BufferedAudioPlayer
import com.foreverjukebox.app.audio.CowbellOverlayController
import com.foreverjukebox.app.audio.NativeCowbellOverlayController
import com.foreverjukebox.app.ui.JukeboxAudioMode
import kotlinx.coroutines.yield
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Renders a planned jukebox path through the native DSP pipeline faster than
 * real time. [offlinePlayer] is a streamless clone of the live player (sharing
 * mode, intensity, and the decoded PCM), so exports are processed by the exact
 * code path live playback uses — jumps, filters, reverb, limiter, cowbell
 * overlay, and rate included. The caller owns the clone's lifecycle.
 */
class OfflineJukeboxRenderer(
    private val context: Context,
    private val offlinePlayer: BufferedAudioPlayer,
    private val pathGenerator: JukeboxPathGenerator,
    private val sectionStartBeatIndices: Collection<Int>
) {

    /**
     * Renders [targetDurationSeconds] of output audio, feeding interleaved
     * 16-bit PCM chunks to [onPcmChunk]. The requested duration is output
     * time: rate-changing modes consume proportionally more or less of the
     * source timeline, exactly like live playback.
     */
    suspend fun render(
        targetDurationSeconds: Double,
        onPcmChunk: (buffer: ShortArray, frames: Int) -> Unit,
        onProgress: (renderedFrames: Long, totalFrames: Long) -> Unit
    ) {
        var cowbell: CowbellOverlayController? = null
        try {
            if (offlinePlayer.getJukeboxAudioMode() == JukeboxAudioMode.Cowbell) {
                cowbell = NativeCowbellOverlayController(
                    BufferedAudioCowbellHitScheduler(context, offlinePlayer)
                ).apply {
                    setSectionStartBeatIndices(sectionStartBeatIndices)
                    setVolume(1.0)
                    setEnabled(true)
                }
            }
            renderPath(offlinePlayer, cowbell, targetDurationSeconds, onPcmChunk, onProgress)
        } finally {
            cowbell?.release()
        }
    }

    private suspend fun renderPath(
        offline: BufferedAudioPlayer,
        cowbell: CowbellOverlayController?,
        targetDurationSeconds: Double,
        onPcmChunk: (buffer: ShortArray, frames: Int) -> Unit,
        onProgress: (renderedFrames: Long, totalFrames: Long) -> Unit
    ) {
        val sampleRate = offline.getSampleRate()
        val channelCount = offline.getChannelCount()
        val rate = offline.getPlaybackRate().takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val firstBeat = checkNotNull(pathGenerator.firstBeat()) { "Analysis contains no beats" }
        val totalFrames = (targetDurationSeconds * sampleRate).roundToLong()
        val chunk = ShortArray(CHUNK_FRAMES * channelCount)
        var rendered = 0L
        var stalledSteps = 0

        offline.seek(firstBeat.start)
        while (rendered < totalFrames) {
            // Cancellation point for the whole loop: steps that render zero
            // frames never reach the inner loop's yield, and a degenerate
            // analysis (non-monotonic beat times) could produce many in a row.
            yield()
            val step = pathGenerator.nextStep() ?: break
            var jumpScheduled = false
            step.jump?.let { jump ->
                jumpScheduled = offline.scheduleJump(jump.targetTime, jump.sourceBoundaryTime)
            }
            cowbell?.handleBeatEnter(
                beatIndex = step.beatIndex,
                beat = step.beat,
                nextBeat = step.nextBeatInTimeline,
                playbackRate = rate
            )
            // Frames until the source position crosses this beat's boundary,
            // using the same frame quantization as the native jump scheduler.
            val positionFrames = offline.getCurrentTime() * sampleRate
            val boundaryFrame = floor(step.boundarySourceTime * sampleRate)
            var framesForBeat = ceil((boundaryFrame - positionFrames) / rate).toLong()
                .coerceAtLeast(0L)
            if (step.jump != null && jumpScheduled) {
                // One extra frame carries the render across the boundary so the
                // pending jump is consumed before the next one is scheduled
                // (native holds a single pending-jump slot).
                framesForBeat += 1
            }
            framesForBeat = min(framesForBeat, totalFrames - rendered)
            if (framesForBeat <= 0L) {
                stalledSteps += 1
                check(stalledSteps < MAX_STALLED_STEPS) { "Export render made no progress" }
            } else {
                stalledSteps = 0
            }
            var remaining = framesForBeat
            while (remaining > 0) {
                val requested = min(CHUNK_FRAMES.toLong(), remaining).toInt()
                val got = offline.renderOffline(chunk, requested)
                if (got <= 0) break
                onPcmChunk(chunk, got)
                rendered += got
                remaining -= got
                onProgress(rendered, totalFrames)
                yield()
            }
            if (step.jump != null && !jumpScheduled && rendered < totalFrames) {
                // Rejected schedule (e.g. a boundary past the decoded audio):
                // fall back to a direct seek, mirroring the live engine's
                // seek-based wrap.
                offline.seek(step.jump.targetTime)
            }
        }
        onProgress(rendered, totalFrames)
    }

    private companion object {
        const val CHUNK_FRAMES = 8192
        const val MAX_STALLED_STEPS = 4096
    }
}
