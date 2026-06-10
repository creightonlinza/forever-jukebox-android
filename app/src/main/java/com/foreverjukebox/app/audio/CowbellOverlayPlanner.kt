package com.foreverjukebox.app.audio

import com.foreverjukebox.app.engine.QuantumBase

internal const val BASE_COWBELL_GAIN = 1.0
internal const val ACCENT_GAIN_MIN = 0.85
internal const val ACCENT_GAIN_MAX = 1.15
internal const val SUBDIVISION_GAIN_MIN = 0.55
internal const val SUBDIVISION_GAIN_MAX = 0.80
internal const val WALKEN_GAIN = 2.5
internal const val TRILL_GAIN = 1.35
internal const val WALKEN_EFFECT_PROBABILITY = 0.75
internal const val PAN_RANGE = 0.25
internal const val SUBDIVISION_BURST_PROBABILITY = 0.05
internal const val MIN_SUBDIVISION_BEAT_SECONDS = 0.30

internal val SUBDIVISION_BURST_TIMINGS = listOf(0.25, 0.5, 0.75)
internal val COWBELL_SAMPLE_NAMES = listOf("cowbell0.wav", "cowbell1.wav")
internal val WALKEN_SAMPLE_NAMES = (0..15).map { index -> "walken$index.wav" }
internal const val TRILL_SAMPLE_NAME = "trill.wav"

internal data class CowbellHitPlan(
    val sampleName: String,
    val delaySeconds: Double,
    val gain: Double,
    val pan: Double
)

internal class CowbellOverlayPlanner(
    private val random: () -> Double = { Math.random() }
) {
    fun planBeat(
        beatIndex: Int,
        beat: QuantumBase,
        nextBeat: QuantumBase?,
        playbackRate: Double,
        playerVolume: Double,
        sectionStartBeatIndices: Set<Int>
    ): List<CowbellHitPlan> {
        val safeVolume = playerVolume.coerceIn(0.0, 1.0)
        val plans = mutableListOf<CowbellHitPlan>()
        plans += CowbellHitPlan(
            sampleName = choose(COWBELL_SAMPLE_NAMES),
            delaySeconds = 0.0,
            gain = BASE_COWBELL_GAIN * safeVolume * randomRange(ACCENT_GAIN_MIN, ACCENT_GAIN_MAX),
            pan = randomPan()
        )

        val beatSeconds = realtimeBeatDuration(beat, nextBeat, playbackRate)
        if (
            beatSeconds >= MIN_SUBDIVISION_BEAT_SECONDS &&
            random() < SUBDIVISION_BURST_PROBABILITY
        ) {
            for (timing in SUBDIVISION_BURST_TIMINGS) {
                plans += CowbellHitPlan(
                    sampleName = choose(COWBELL_SAMPLE_NAMES),
                    delaySeconds = beatSeconds * timing,
                    gain = BASE_COWBELL_GAIN *
                        safeVolume *
                        randomRange(SUBDIVISION_GAIN_MIN, SUBDIVISION_GAIN_MAX),
                    pan = randomPan()
                )
            }
        }

        if (beatIndex in sectionStartBeatIndices) {
            plans += if (random() < WALKEN_EFFECT_PROBABILITY) {
                CowbellHitPlan(
                    sampleName = choose(WALKEN_SAMPLE_NAMES),
                    delaySeconds = 0.0,
                    gain = BASE_COWBELL_GAIN * safeVolume * WALKEN_GAIN,
                    pan = randomPan()
                )
            } else {
                CowbellHitPlan(
                    sampleName = TRILL_SAMPLE_NAME,
                    delaySeconds = 0.0,
                    gain = BASE_COWBELL_GAIN * safeVolume * TRILL_GAIN,
                    pan = randomPan()
                )
            }
        }
        return plans
    }

    private fun realtimeBeatDuration(
        beat: QuantumBase,
        nextBeat: QuantumBase?,
        playbackRate: Double
    ): Double {
        val rawDuration = if (nextBeat != null && nextBeat.start.isFinite()) {
            (nextBeat.start - beat.start).coerceAtLeast(0.0)
        } else {
            beat.duration
        }
        val safeRate = if (playbackRate.isFinite() && playbackRate > 0.0) playbackRate else 1.0
        return rawDuration / safeRate
    }

    private fun randomRange(min: Double, max: Double): Double {
        return min + (max - min) * random()
    }

    private fun randomPan(): Double {
        return (random() * 2.0 - 1.0) * PAN_RANGE
    }

    private fun choose(samples: List<String>): String {
        val index = (random() * samples.size).toInt().coerceIn(0, samples.lastIndex)
        return samples[index]
    }
}
