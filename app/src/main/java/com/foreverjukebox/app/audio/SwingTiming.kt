package com.foreverjukebox.app.audio

const val DEFAULT_SWING_AMOUNT = 0.33

private const val MIN_SWING_AMOUNT = -0.9
private const val MAX_SWING_AMOUNT = 0.9

// Smallest step above 1.0, keeping playback-rate divisions finite.
private val MIN_OUTPUT_DURATION = Math.ulp(1.0)

data class SwingBeat(
    val start: Double,
    val duration: Double
)

data class SwingSegment(
    val inputStart: Double,
    val inputDuration: Double,
    val outputDuration: Double,
    val playbackRate: Double
)

/**
 * One half-beat in whole frames: [inputFrameCount] source frames from
 * [inputStartFrame] are stretched to fill [outputFrameCount] frames at
 * [outputStartFrame].
 */
data class SwingFrameSegment(
    val inputStartFrame: Int,
    val inputFrameCount: Int,
    val outputStartFrame: Int,
    val outputFrameCount: Int
)

fun clampSwingAmount(swingAmount: Double): Double {
    if (!swingAmount.isFinite()) {
        return DEFAULT_SWING_AMOUNT
    }
    return swingAmount.coerceIn(MIN_SWING_AMOUNT, MAX_SWING_AMOUNT)
}

/**
 * Splits a beat into equal input halves and gives the first half
 * `(1 + swing)` of half the beat; the second half takes what remains, so the
 * beat keeps its total duration.
 */
fun swingSegmentsForBeat(
    beat: SwingBeat,
    swingAmount: Double = DEFAULT_SWING_AMOUNT
): Pair<SwingSegment, SwingSegment> {
    val duration = beat.duration.coerceAtLeast(0.0)
    val half = duration / 2
    val swing = clampSwingAmount(swingAmount)

    val outputADuration = half * (1 + swing)
    val outputBDuration = duration - outputADuration
    return SwingSegment(
        inputStart = beat.start,
        inputDuration = half,
        outputDuration = outputADuration,
        playbackRate = half / outputADuration.coerceAtLeast(MIN_OUTPUT_DURATION)
    ) to SwingSegment(
        inputStart = beat.start + half,
        inputDuration = half,
        outputDuration = outputBDuration,
        playbackRate = half / outputBDuration.coerceAtLeast(MIN_OUTPUT_DURATION)
    )
}

/**
 * Frame-accurate halves for [beat], or null when the beat falls outside the
 * source or is too short to split.
 */
fun swingFrameSegmentsForBeat(
    beat: SwingBeat,
    sampleRate: Int,
    sourceFrames: Int,
    swingAmount: Double = DEFAULT_SWING_AMOUNT
): Pair<SwingFrameSegment, SwingFrameSegment>? {
    val beatStartFrame = Math.round(beat.start * sampleRate)
    val beatEndFrame = Math.round((beat.start + beat.duration) * sampleRate)
    if (beatStartFrame < 0 || beatEndFrame > sourceFrames || beatEndFrame - beatStartFrame < 2) {
        return null
    }

    val beatFrameCount = (beatEndFrame - beatStartFrame).toInt()
    val inputAFrameCount = beatFrameCount / 2
    val outputAFrameCount = Math.round(
        swingSegmentsForBeat(beat, swingAmount).first.outputDuration * sampleRate
    ).coerceIn(1L, beatFrameCount - 1L).toInt()
    val startFrame = beatStartFrame.toInt()
    return SwingFrameSegment(
        inputStartFrame = startFrame,
        inputFrameCount = inputAFrameCount,
        outputStartFrame = startFrame,
        outputFrameCount = outputAFrameCount
    ) to SwingFrameSegment(
        inputStartFrame = startFrame + inputAFrameCount,
        inputFrameCount = beatFrameCount - inputAFrameCount,
        outputStartFrame = startFrame + outputAFrameCount,
        outputFrameCount = beatFrameCount - outputAFrameCount
    )
}

/** Two segments per renderable beat, in beat order; unrenderable beats are skipped. */
fun swingFrameSegments(
    beats: List<SwingBeat>,
    sampleRate: Int,
    sourceFrames: Int,
    swingAmount: Double = DEFAULT_SWING_AMOUNT
): List<SwingFrameSegment> {
    return beats.flatMap { beat ->
        swingFrameSegmentsForBeat(beat, sampleRate, sourceFrames, swingAmount)?.toList().orEmpty()
    }
}
