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
    val start = if (beat.start.isFinite()) beat.start else 0.0
    val duration = if (beat.duration.isFinite()) beat.duration.coerceAtLeast(0.0) else 0.0
    val half = duration / 2
    val swing = clampSwingAmount(swingAmount)

    val outputADuration = half * (1 + swing)
    val outputBDuration = duration - outputADuration
    return SwingSegment(
        inputStart = start,
        inputDuration = half,
        outputDuration = outputADuration,
        playbackRate = half / outputADuration.coerceAtLeast(MIN_OUTPUT_DURATION)
    ) to SwingSegment(
        inputStart = start + half,
        inputDuration = half,
        outputDuration = outputBDuration,
        playbackRate = half / outputBDuration.coerceAtLeast(MIN_OUTPUT_DURATION)
    )
}

/**
 * Frame-accurate halves for [beat], or null when its timing is not finite and
 * positive, it starts before the source, or too little of it lies inside the
 * source to split. A beat running past the end keeps its full swing timing,
 * clipped to the source: audio pushed past the end is cut off, as the rest of
 * a longer track would be.
 */
fun swingFrameSegmentsForBeat(
    beat: SwingBeat,
    sampleRate: Int,
    sourceFrames: Int,
    swingAmount: Double = DEFAULT_SWING_AMOUNT
): Pair<SwingFrameSegment, SwingFrameSegment>? {
    if (!beat.start.isFinite() || !beat.duration.isFinite() || beat.duration <= 0.0) {
        return null
    }
    val beatStartFrame = Math.round(beat.start * sampleRate)
    val beatEndFrame = Math.round((beat.start + beat.duration) * sampleRate)
    if (beatStartFrame < 0 || beatEndFrame - beatStartFrame < 2 || sourceFrames - beatStartFrame < 2) {
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
    ).clippedTo(sourceFrames) to SwingFrameSegment(
        inputStartFrame = startFrame + inputAFrameCount,
        inputFrameCount = beatFrameCount - inputAFrameCount,
        outputStartFrame = startFrame + outputAFrameCount,
        outputFrameCount = beatFrameCount - outputAFrameCount
    ).clippedTo(sourceFrames)
}

/**
 * The leading part of this half-beat whose output fits the source, at the same
 * stretch ratio; input it needs from past the end reads as silence in the
 * native render. A half wholly past the end keeps nothing and is pinned there.
 */
private fun SwingFrameSegment.clippedTo(sourceFrames: Int): SwingFrameSegment {
    val outputRoom = (sourceFrames - outputStartFrame).coerceAtLeast(0)
    val kept = minOf(1.0, outputRoom.toDouble() / outputFrameCount)
    if (kept >= 1.0) return this
    return SwingFrameSegment(
        inputStartFrame = inputStartFrame.coerceAtMost(sourceFrames),
        inputFrameCount = Math.round(inputFrameCount * kept).toInt(),
        outputStartFrame = outputStartFrame.coerceAtMost(sourceFrames),
        outputFrameCount = minOf(outputRoom, Math.round(outputFrameCount * kept).toInt())
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
