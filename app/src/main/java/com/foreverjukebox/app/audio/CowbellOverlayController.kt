package com.foreverjukebox.app.audio

import android.content.Context
import com.foreverjukebox.app.engine.QuantumBase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal interface CowbellOverlayController {
    fun setEnabled(enabled: Boolean)
    fun setVolume(volume: Double)
    fun setSectionStartBeatIndices(indices: Collection<Int>)
    fun handleBeatEnter(
        beatIndex: Int,
        beat: QuantumBase,
        nextBeat: QuantumBase?,
        playbackRate: Double
    )
    fun cancelScheduledHits()
    fun release()
}

internal object NoOpCowbellOverlayController : CowbellOverlayController {
    override fun setEnabled(enabled: Boolean) = Unit
    override fun setVolume(volume: Double) = Unit
    override fun setSectionStartBeatIndices(indices: Collection<Int>) = Unit
    override fun handleBeatEnter(
        beatIndex: Int,
        beat: QuantumBase,
        nextBeat: QuantumBase?,
        playbackRate: Double
    ) = Unit
    override fun cancelScheduledHits() = Unit
    override fun release() = Unit
}

internal interface CowbellHitScheduler {
    fun preloadCowbellSamples()
    fun scheduleCowbellHit(
        sampleName: String,
        targetTimeSeconds: Double,
        leftVolume: Float,
        rightVolume: Float
    )
    fun cancelPendingCowbellHits()
    fun cancelCowbellHits()
}

internal class BufferedAudioCowbellHitScheduler(
    context: Context,
    private val player: BufferedAudioPlayer
) : CowbellHitScheduler {
    private val appContext = context.applicationContext

    override fun preloadCowbellSamples() {
        player.preloadCowbellSamples(appContext, NativeCowbellOverlayController.requiredSampleNames())
    }

    override fun scheduleCowbellHit(
        sampleName: String,
        targetTimeSeconds: Double,
        leftVolume: Float,
        rightVolume: Float
    ) {
        player.scheduleCowbellHit(
            sampleName = sampleName,
            targetTimeSeconds = targetTimeSeconds,
            leftVolume = leftVolume,
            rightVolume = rightVolume
        )
    }

    override fun cancelCowbellHits() {
        player.cancelCowbellHits()
    }

    override fun cancelPendingCowbellHits() {
        player.cancelPendingCowbellHits()
    }
}

internal class NativeCowbellOverlayController(
    private val scheduler: CowbellHitScheduler,
    private val planner: CowbellOverlayPlanner = CowbellOverlayPlanner()
) : CowbellOverlayController {
    private var sectionStartBeatIndices = emptySet<Int>()
    private var enabled = false
    private var volume = 1.0

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (enabled) {
            scheduler.preloadCowbellSamples()
        } else {
            scheduler.cancelCowbellHits()
        }
    }

    override fun setVolume(volume: Double) {
        this.volume = volume.coerceIn(0.0, 1.0)
    }

    override fun setSectionStartBeatIndices(indices: Collection<Int>) {
        sectionStartBeatIndices = indices.filter { it > 0 }.toSet()
    }

    override fun handleBeatEnter(
        beatIndex: Int,
        beat: QuantumBase,
        nextBeat: QuantumBase?,
        playbackRate: Double
    ) {
        scheduler.cancelPendingCowbellHits()
        if (!enabled) return
        scheduler.preloadCowbellSamples()
        val hits = planner.planBeat(
            beatIndex = beatIndex,
            beat = beat,
            nextBeat = nextBeat,
            playbackRate = playbackRate,
            playerVolume = volume,
            sectionStartBeatIndices = sectionStartBeatIndices
        )
        for (hit in hits) {
            scheduleHit(beat, hit, playbackRate)
        }
    }

    override fun cancelScheduledHits() {
        scheduler.cancelCowbellHits()
    }

    // Release is not terminal: the owning controller is a process singleton that can
    // be handed to a new ViewModel, and a later setEnabled(true) must bring hits back.
    override fun release() {
        enabled = false
        scheduler.cancelCowbellHits()
    }

    private fun scheduleHit(beat: QuantumBase, hit: CowbellHitPlan, playbackRate: Double) {
        val targetTimeSeconds = beat.start + hit.delaySeconds * safePlaybackRate(playbackRate)
        val (left, right) = stereoVolumes(hit.gain, hit.pan)
        scheduler.scheduleCowbellHit(
            sampleName = hit.sampleName,
            targetTimeSeconds = targetTimeSeconds,
            leftVolume = left,
            rightVolume = right
        )
    }

    private fun safePlaybackRate(playbackRate: Double): Double {
        return if (playbackRate.isFinite() && playbackRate > 0.0) playbackRate else 1.0
    }

    private fun stereoVolumes(gain: Double, pan: Double): Pair<Float, Float> {
        val safePan = pan.coerceIn(-1.0, 1.0)
        val angle = (safePan + 1.0) * PI * 0.25
        val left = (gain * cos(angle)).coerceAtLeast(0.0).toFloat()
        val right = (gain * sin(angle)).coerceAtLeast(0.0).toFloat()
        return left to right
    }

    companion object {
        fun requiredSampleNames(): List<String> {
            return COWBELL_SAMPLE_NAMES + listOf(TRILL_SAMPLE_NAME) + WALKEN_SAMPLE_NAMES
        }
    }
}
