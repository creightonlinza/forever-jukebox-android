package com.foreverjukebox.app.engine

const val DEFAULT_MIN_LONG_BRANCH_PERCENT = 20

/**
 * The lowest threshold a control or a wire param can express. Anything below it is not a threshold,
 * it is how "let the track decide" has always been spelled.
 *
 * Unrelated to the lowest value the engine's own sweep can land on, which is 10: a computed threshold
 * is only ever 10, 15, ... 75, or [MAX_THRESHOLD]. The 2..9 range is reachable only by choosing it,
 * and produces a sparse or branch-free graph.
 */
const val MIN_THRESHOLD = 2

/** The highest threshold with any effect: edges are precalculated only this far out. */
const val MAX_THRESHOLD = 80

/**
 * Reads a `thresh` wire value as a chosen threshold, or null for auto.
 *
 * Shared across the Forever Jukebox apps, so it matches `parsePinnedThreshold` in the web engine
 * case for case: a leading integer is taken from whatever else the string holds, anything below
 * [MIN_THRESHOLD] reads as auto rather than as an error, and anything above [MAX_THRESHOLD] is
 * clamped so the value reported is the value acting.
 */
fun parsePinnedThreshold(raw: String?): Int? = pinnedThresholdOrNull(parseLeadingLong(raw))

/** Reads an already-numeric threshold under the same rule, for payload fields typed as numbers. */
fun parsePinnedThreshold(raw: Int?): Int? = pinnedThresholdOrNull(raw?.toLong())

private fun pinnedThresholdOrNull(value: Long?): Int? {
    return when {
        value == null || value < MIN_THRESHOLD -> null
        value > MAX_THRESHOLD -> MAX_THRESHOLD
        else -> value.toInt()
    }
}

/**
 * The leading integer of a string, as the web app's `Number.parseInt` reads it: leading whitespace and
 * a sign are allowed, digits are taken until the first non-digit, and a string starting with no digits
 * has no value. Saturates rather than overflowing, since every caller clamps into a small range.
 */
private fun parseLeadingLong(raw: String?): Long? {
    val trimmed = raw?.trimStart() ?: return null
    val negative = trimmed.startsWith('-')
    val signed = negative || trimmed.startsWith('+')
    val digits = trimmed.drop(if (signed) 1 else 0).takeWhile { it in '0'..'9' }
    if (digits.isEmpty()) {
        return null
    }
    val significant = digits.trimStart('0')
    val magnitude = when {
        significant.isEmpty() -> 0L
        significant.length > 18 -> Long.MAX_VALUE
        else -> significant.toLong()
    }
    return if (negative) -magnitude else magnitude
}

data class TrackMeta(
    val title: String? = null,
    val artist: String? = null,
    val duration: Double? = null,
    val tempo: Double? = null,
    val timeSignature: Double? = null
)

data class JukeboxConfig(
    val maxBranches: Int = 4,
    val maxBranchThreshold: Int = MAX_THRESHOLD,
    val currentThreshold: Int = 0,
    val justBackwards: Boolean = false,
    val justLongBranches: Boolean = false,
    val removeSequentialBranches: Boolean = false,
    val minRandomBranchChance: Double = 0.18,
    val maxRandomBranchChance: Double = 0.5,
    val randomBranchChanceDelta: Double = 0.02,
    val minLongBranch: Int = 0,
    val minLongBranchPercent: Int = DEFAULT_MIN_LONG_BRANCH_PERCENT
)

data class JukeboxConfigUpdate(
    val maxBranches: Int? = null,
    val maxBranchThreshold: Int? = null,
    val currentThreshold: Int? = null,
    val justBackwards: Boolean? = null,
    val justLongBranches: Boolean? = null,
    val removeSequentialBranches: Boolean? = null,
    val minRandomBranchChance: Double? = null,
    val maxRandomBranchChance: Double? = null,
    val randomBranchChanceDelta: Double? = null,
    val minLongBranch: Int? = null,
    val minLongBranchPercent: Int? = null
)

fun JukeboxConfig.applyUpdate(update: JukeboxConfigUpdate): JukeboxConfig {
    return copy(
        maxBranches = update.maxBranches ?: maxBranches,
        maxBranchThreshold = update.maxBranchThreshold ?: maxBranchThreshold,
        currentThreshold = update.currentThreshold ?: currentThreshold,
        justBackwards = update.justBackwards ?: justBackwards,
        justLongBranches = update.justLongBranches ?: justLongBranches,
        removeSequentialBranches = update.removeSequentialBranches ?: removeSequentialBranches,
        minRandomBranchChance = update.minRandomBranchChance ?: minRandomBranchChance,
        maxRandomBranchChance = update.maxRandomBranchChance ?: maxRandomBranchChance,
        randomBranchChanceDelta = update.randomBranchChanceDelta ?: randomBranchChanceDelta,
        minLongBranch = update.minLongBranch ?: minLongBranch,
        minLongBranchPercent = update.minLongBranchPercent ?: minLongBranchPercent
    )
}

fun JukeboxConfig.toUpdate(): JukeboxConfigUpdate {
    return JukeboxConfigUpdate(
        maxBranches = maxBranches,
        maxBranchThreshold = maxBranchThreshold,
        currentThreshold = currentThreshold,
        justBackwards = justBackwards,
        justLongBranches = justLongBranches,
        removeSequentialBranches = removeSequentialBranches,
        minRandomBranchChance = minRandomBranchChance,
        maxRandomBranchChance = maxRandomBranchChance,
        randomBranchChanceDelta = randomBranchChanceDelta,
        minLongBranch = minLongBranch,
        minLongBranchPercent = minLongBranchPercent
    )
}

fun JukeboxConfig.withMinimumJumpDistancePercent(percent: Int): JukeboxConfig {
    return copy(
        justLongBranches = percent > 0,
        minLongBranchPercent = percent.takeIf { it > 0 } ?: DEFAULT_MIN_LONG_BRANCH_PERCENT
    )
}

data class JukeboxGraphState(
    val computedThreshold: Int,
    val currentThreshold: Int,
    val lastBranchPoint: Int,
    val totalBeats: Int,
    val longestReach: Double,
    val allEdges: MutableList<Edge>
)

data class JukeboxState(
    val currentBeatIndex: Int,
    val beatsPlayed: Int,
    val currentTime: Double,
    val lastJumped: Boolean,
    val lastJumpTime: Double?,
    val lastJumpFromIndex: Int?,
    val lastJumpToIndex: Int?,
    val currentThreshold: Int,
    val lastBranchPoint: Int,
    val curRandomBranchChance: Double
)

data class JumpEvent(
    val sourceStartTime: Double,
    val targetTime: Double
)

data class Segment(
    val start: Double,
    val duration: Double,
    val confidence: Double,
    val loudnessStart: Double,
    val loudnessMax: Double,
    val loudnessMaxTime: Double,
    val pitches: List<Double>,
    val timbre: List<Double>,
    var which: Int
)

data class QuantumBase(
    val start: Double,
    val duration: Double,
    val confidence: Double?,
    var which: Int,
    var prev: QuantumBase? = null,
    var next: QuantumBase? = null,
    var parent: QuantumBase? = null,
    var children: MutableList<QuantumBase> = mutableListOf(),
    var indexInParent: Int? = null,
    var overlappingSegments: MutableList<Segment> = mutableListOf(),
    var oseg: Segment? = null,
    var neighbors: MutableList<Edge> = mutableListOf(),
    var allNeighbors: MutableList<Edge> = mutableListOf(),
    var reach: Int? = null
)

data class Edge(
    var id: Int,
    val src: QuantumBase,
    val dest: QuantumBase,
    val distance: Double,
    var deleted: Boolean
)

data class TrackAnalysis(
    val sections: MutableList<QuantumBase>,
    val bars: MutableList<QuantumBase>,
    val beats: MutableList<QuantumBase>,
    val tatums: MutableList<QuantumBase>,
    val segments: MutableList<Segment>,
    val track: TrackMeta? = null
)
