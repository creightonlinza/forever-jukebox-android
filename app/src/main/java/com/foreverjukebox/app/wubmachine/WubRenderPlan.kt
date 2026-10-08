package com.foreverjukebox.app.wubmachine

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Frame-level form of a DubstepPlan for the native renderer. Mirrors the frame
// arithmetic of dubstepRenderer.ts (beat-grid mode) so both platforms cut and
// stretch the same source frames.

/** Frames `[startFrame, stopFrame)` of the source stretched to [targetFrames] frames. */
data class WubSliceFrames(
    val startFrame: Int,
    val stopFrame: Int,
    val targetFrames: Int
)

data class WubRenderPart(
    val kind: DubstepPartKind,
    val label: String,
    /** Indices into [WubRenderRequest.sampleNames]. */
    val sampleIndices: List<Int>,
    val slices: List<WubSliceFrames>,
    /** Parts with equal slice lists share one stretched source. */
    val sliceGroup: Int,
    val mix: Double
)

data class WubRenderRequest(
    val sampleRate: Int,
    /** Length of every part over source audio: exactly 8 bars at 140 BPM. */
    val partFrames: Int,
    /** Every sample the render loads, in index order. */
    val sampleNames: List<String>,
    val parts: List<WubRenderPart>
)

/** Where a part landed in the rendered remix, in seconds. */
data class WubRenderedPart(
    val kind: DubstepPartKind,
    val label: String,
    val start: Double,
    val duration: Double
)

fun wubBeatFrames(sampleRate: Int): Double = sampleRate * 60.0 / DUBSTEP_TEMPO

fun wubPartFrames(sampleRate: Int): Int =
    Math.round(sampleRate * DUBSTEP_PART_BEATS * 60.0 / DUBSTEP_TEMPO).toInt()

/** Source frames of [slice], clamped to the source, and its share of the 140 BPM grid. */
fun wubSliceFrames(slice: SourceSlice, sampleRate: Int, sourceFrames: Int): WubSliceFrames {
    val start = min(sourceFrames.toLong(), floor(slice.start * sampleRate).toLong())
    val stop = min(sourceFrames.toLong(), floor((slice.start + slice.duration) * sampleRate).toLong())
    return WubSliceFrames(
        startFrame = start.toInt(),
        stopFrame = max(start, stop).toInt(),
        targetFrames = Math.round(slice.beats * wubBeatFrames(sampleRate)).toInt()
    )
}

fun buildWubRenderRequest(plan: DubstepPlan, sampleRate: Int, sourceFrames: Int): WubRenderRequest {
    val sampleNames = plan.parts.flatMap { it.samples }.distinct()
    val sliceGroups = HashMap<List<SourceSlice>, Int>()
    val parts = plan.parts.map { part ->
        WubRenderPart(
            kind = part.kind,
            label = part.label,
            sampleIndices = part.samples.map { sampleNames.indexOf(it) },
            slices = part.slices.map { wubSliceFrames(it, sampleRate, sourceFrames) },
            sliceGroup = sliceGroups.getOrPut(part.slices) { sliceGroups.size },
            mix = part.mix
        )
    }
    return WubRenderRequest(
        sampleRate = sampleRate,
        partFrames = wubPartFrames(sampleRate),
        sampleNames = sampleNames,
        parts = parts
    )
}

/**
 * Parts over source audio are [partFrames] each; the ending takes what remains of
 * [totalFrames]. Mirrors layoutParts in dubstepRenderer.ts.
 */
fun layoutWubParts(
    plan: DubstepPlan,
    sampleRate: Int,
    partFrames: Int,
    totalFrames: Int
): List<WubRenderedPart> {
    var offset = 0
    return plan.parts.map { part ->
        val frames = if (part.slices.isNotEmpty()) partFrames else max(0, totalFrames - offset)
        val rendered = WubRenderedPart(
            kind = part.kind,
            label = part.label,
            start = offset.toDouble() / sampleRate,
            duration = frames.toDouble() / sampleRate
        )
        offset += frames
        rendered
    }
}

/**
 * Where a looping remix jumps back to the start of the track: the start of the ending, which
 * never plays while looping.
 */
fun wubLoopEnd(parts: List<WubRenderedPart>, duration: Double): Double {
    return parts.firstOrNull { it.kind == DubstepPartKind.Ending }?.start ?: duration
}

/**
 * Playback position for [elapsed] seconds of audio since [offset], wrapping from [loopEnd] back
 * to the start while looping. Mirrors loopedPosition in WubMachineController.ts.
 */
fun loopedPosition(offset: Double, elapsed: Double, looping: Boolean, loopEnd: Double): Double {
    val raw = offset + elapsed
    if (!looping || raw < loopEnd || loopEnd <= 0) return raw
    return raw % loopEnd
}
