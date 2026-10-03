package com.foreverjukebox.app.wubmachine

import com.foreverjukebox.app.engine.Segment
import com.foreverjukebox.app.engine.TrackAnalysis
import com.foreverjukebox.app.engine.TrackMeta
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Dubstep arrangement of the Wub Machine (psobot/wub-machine, MIT): an intro built from the
// first 16 beats, then two 8-bar parts per section, then a tail. Mirrors dubstepArrangement.ts
// in the web repo; engine-parity/wub-arrangement-cases.json pins the shared behavior.

const val DUBSTEP_TEMPO = 140.0
const val DUBSTEP_PART_BEATS = 32

private const val MIXPOINT = 18.0
private const val MIX_A = 89.0 / 1.5 + MIXPOINT
private const val MIX_B = 188.0 / 1.5 + MIXPOINT
private const val MIN_MIX = 0.3
private const val MAX_MIX = 0.8
private const val MIX_TILT = 0.15
private const val PITCH_CLASSES = 12
private const val INTRO_BEATS = 16
private const val PHRASE_BEATS = 16
private const val MIN_SECTION_BEATS = 16
private const val QUIET_SECTION_DB = 15.0
private const val SPLASH_END_COUNT = 4
private const val DENOMINATOR_EPSILON = 1e-9

private val KEY_FILES = listOf(
    "c", "c-sharp", "d", "d-sharp", "e", "f", "f-sharp", "g", "g-sharp", "a", "a-sharp", "b"
)
private val SPLASH_ORDER = listOf(3, 4, 2, 1, 5, 7, 6, 8, 10, 9, 11)

// Krumhansl-Kessler key profiles, indexed by semitones above the tonic.
private val MAJOR_PROFILE = listOf(
    6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88
)
private val MINOR_PROFILE = listOf(
    6.33, 2.68, 3.52, 5.38, 2.6, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17
)

private fun splashName(index: Int): String = "splashes/splash_" + index.toString().padStart(2, '0')

/** Every sample a plan can name, relative to the dubstep sample root. */
val DUBSTEP_SAMPLE_NAMES: List<String> = listOf("intro-eight", "hats") +
    KEY_FILES.flatMap { key -> listOf("wubs/$key", "break-ends/$key") } +
    SPLASH_ORDER.map(::splashName) +
    (1..SPLASH_END_COUNT).map { "splash-ends/$it" }

interface Span {
    val start: Double
    val duration: Double
}

val Span.end: Double get() = start + duration

private val Segment.end: Double get() = start + duration

data class Quantum(override val start: Double, override val duration: Double) : Span

/** A stretch of source audio and the number of remix beats it fills. */
data class SourceSlice(
    override val start: Double,
    override val duration: Double,
    val beats: Double
) : Span

data class DubstepAnalysis(
    val sections: List<Quantum>,
    val beats: List<Quantum>,
    val segments: List<Segment>,
    val bars: List<Quantum>? = null,
    val track: TrackMeta? = null
) {
    companion object {
        fun from(analysis: TrackAnalysis): DubstepAnalysis = DubstepAnalysis(
            sections = analysis.sections.map { Quantum(it.start, it.duration) },
            beats = analysis.beats.map { Quantum(it.start, it.duration) },
            segments = analysis.segments.toList(),
            bars = analysis.bars.map { Quantum(it.start, it.duration) },
            track = analysis.track
        )
    }
}

enum class DubstepPartKind(val wireName: String) {
    Intro("intro"),
    Drop("drop"),
    Break("break"),
    Ending("ending")
}

data class DubstepPart(
    val kind: DubstepPartKind,
    val label: String,
    /** Sample names relative to the dubstep sample root, averaged into one bed. */
    val samples: List<String>,
    /** Source audio laid end to end under the bed; a drop and its break may share one list. */
    val slices: List<SourceSlice>,
    /** Bed gain; the source gets 1 - mix. */
    val mix: Double
)

data class DubstepPlan(
    val tonic: Int,
    val parts: List<DubstepPart>
)

data class DubstepPlanOptions(
    /** Play runs of consecutive beats instead of cycling through scattered ones. */
    val contiguous: Boolean = false,
    /** Pitch class (0 = C) to remix in; estimated from the track when null. */
    val tonic: Int? = null,
    /**
     * Size each section's share of the remix by its length instead of a fixed drop and break,
     * folding very short sections into their neighbours.
     */
    val sectionBudget: Boolean = false,
    /** Leave out sections far quieter than the track. */
    val skipQuiet: Boolean = false,
    /** Push the samples forward in drops and the song forward in breaks. */
    val contrast: Boolean = false,
    /** Stutter the last two beats before each drop. */
    val fills: Boolean = false
)

/** The house arrangement, shared with the web app's WUB_MACHINE_ARRANGEMENT. */
val WUB_MACHINE_PLAN_OPTIONS = DubstepPlanOptions(
    contiguous = true,
    sectionBudget = true,
    skipQuiet = true,
    contrast = true,
    fills = true
)

private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())

private fun <T> identitySetOf(items: Iterable<T>): Set<T> = identitySet<T>().apply { addAll(items) }

private fun correlation(a: List<Double>, b: List<Double>): Double {
    val meanA = a.sum() / a.size
    val meanB = b.sum() / b.size
    var cross = 0.0
    var powerA = 0.0
    var powerB = 0.0
    for (i in a.indices) {
        val da = a[i] - meanA
        val db = b[i] - meanB
        cross += da * db
        powerA += da * da
        powerB += db * db
    }
    return if (powerA > 0 && powerB > 0) cross / sqrt(powerA * powerB) else 0.0
}

/** Pitch class (0 = C) of the best-correlated major or minor key. */
fun estimateTonic(segments: List<Segment>): Int {
    val chroma = DoubleArray(PITCH_CLASSES)
    for (segment in segments) {
        for (pitch in 0 until PITCH_CLASSES) {
            chroma[pitch] += segment.pitches.getOrElse(pitch) { 0.0 } * segment.duration
        }
    }
    var best = 0
    var bestScore = Double.NEGATIVE_INFINITY
    for (tonic in 0 until PITCH_CLASSES) {
        val rotated = List(PITCH_CLASSES) { i -> chroma[(i + tonic) % PITCH_CLASSES] }
        val score = max(correlation(rotated, MAJOR_PROFILE), correlation(rotated, MINOR_PROFILE))
        if (score > bestScore) {
            bestScore = score
            best = tonic
        }
    }
    return best
}

private fun hasPitchMax(segment: Segment, pitch: Int): Boolean {
    val value = segment.pitches.getOrElse(pitch) { 0.0 }
    return segment.pitches.all { other -> value >= other }
}

private fun beatsInSection(analysis: DubstepAnalysis, section: Quantum): List<Quantum> =
    analysis.beats.filter { beat -> beat.start >= section.start && beat.start < section.end }

// Beats of the section that contain the end of a segment whose strongest pitch is `pitch` and
// which spans the start of a beat of the section.
private fun getSamples(analysis: DubstepAnalysis, section: Quantum, pitch: Int): List<Quantum> {
    val beats = beatsInSection(analysis, section)
    val segmentEnds = analysis.segments
        .filter { segment ->
            hasPitchMax(segment, pitch) &&
                beats.any { beat -> segment.start <= beat.start && segment.end >= beat.start }
        }
        .map { it.end }
    return beats.filter { beat ->
        segmentEnds.any { segmentEnd -> beat.start <= segmentEnd && beat.end >= segmentEnd }
    }
}

// Walks up in fifths within the section, then in whole tones through the following sections,
// until some beats match.
private fun searchSamples(analysis: DubstepAnalysis, sectionIndex: Int, pitch: Int): List<Quantum> {
    val sections = analysis.sections
    var j = sectionIndex
    var key = pitch
    var found = getSamples(analysis, sections[j], key)
    repeat(SEARCH_TRIES) {
        if (found.isEmpty()) {
            key = (key + FIFTH) % PITCH_CLASSES
            found = getSamples(analysis, sections[j], key)
        }
    }
    repeat(SEARCH_TRIES) {
        if (found.isEmpty()) {
            j = (j + 1) % sections.size
            key = (key + WHOLE_TONE) % PITCH_CLASSES
            found = getSamples(analysis, sections[j], key)
        }
    }
    return found
}

private const val SEARCH_TRIES = 5
private const val FIFTH = 7
private const val WHOLE_TONE = 2
private const val MINOR_THIRD = 3
private const val MAJOR_SIXTH = 9

private fun trackLoudness(segments: List<Segment>): Double {
    if (segments.isEmpty()) return 0.0
    return segments.sumOf { it.loudnessMax } / segments.size
}

/** Bed gain from the mean peak loudness (dB) of the segments the slices span. */
fun mixFactor(analysis: DubstepAnalysis, slices: List<Span>): Double {
    val rangeStart = slices.first().start
    val rangeEnd = slices.last().end
    val spanned = analysis.segments.filter { segment ->
        segment.end > rangeStart && segment.start < rangeEnd
    }
    val spannedLoudness = trackLoudness(spanned)
    val loud = if (spannedLoudness == 0.0 || spannedLoudness.isNaN()) {
        trackLoudness(analysis.segments)
    } else {
        spannedLoudness
    }
    val denominator = loud + MIX_B
    val mix = if (abs(denominator) < DENOMINATOR_EPSILON) 0.0 else (loud + MIX_A) / denominator
    return clampMix(mix)
}

private fun clampMix(mix: Double): Double = max(MIN_MIX, min(MAX_MIX, mix))

private fun wholeBeat(beat: Quantum): SourceSlice = SourceSlice(beat.start, beat.duration, 1.0)

private fun cut(slice: SourceSlice, divisor: Double): SourceSlice =
    SourceSlice(slice.start, slice.duration / divisor, slice.beats / divisor)

private fun <T> repeated(item: T, count: Int): List<T> = List(count) { item }

private fun introSlices(analysis: DubstepAnalysis, duration: Double): List<SourceSlice> {
    var beats = analysis.beats.take(INTRO_BEATS).map(::wholeBeat)
    if (beats.size < INTRO_BEATS) {
        val length = duration / INTRO_BEATS
        beats = List(INTRO_BEATS) { i -> SourceSlice(i * length, length, 1.0) }
    }
    return beats +
        repeated(beats[0], 4) +
        repeated(beats[4], 4) +
        repeated(cut(beats[8], 2.0), 8) +
        repeated(cut(beats[12], 4.0), 8) +
        repeated(cut(beats[14], 4.0), 8)
}

// Pool index that beat `i` of a 16-beat phrase draws from.
private fun poolSlot(i: Int): Int = when {
    i < 8 -> 0
    i < 12 -> 1
    else -> 2
}

private typealias Pools = List<List<Quantum>>

// Start of the unused 16-beat window that best follows the pool order (8 tonic beats, 4 a minor
// third up, 4 a major sixth up); ties go to a window starting on a bar line, then to the
// earliest. Null once no window is free.
private fun bestWindow(
    beats: List<Quantum>,
    pools: Pools,
    barStarts: Set<Double>,
    used: Set<Quantum>
): Int? {
    val members = pools.map { pool -> identitySetOf(pool) }
    var best: Int? = null
    var bestScore = -1.0
    for (start in 0..(beats.size - PHRASE_BEATS)) {
        val window = beats.subList(start, start + PHRASE_BEATS)
        if (window.any { beat -> beat in used }) continue
        var score = if (window[0].start in barStarts) 0.5 else 0.0
        window.forEachIndexed { i, beat ->
            if (beat in members[poolSlot(i)]) score += 1
        }
        if (score > bestScore) {
            bestScore = score
            best = start
        }
    }
    return best
}

private fun cycle(beats: List<Quantum>, count: Int): List<Quantum> = List(count) { i -> beats[i % beats.size] }

// Up to `count` non-overlapping phrases of the section, chosen by pool fit and played in song
// order. A section shorter than a phrase cycles its beats; one without beats yields null.
private fun contiguousPhrases(
    analysis: DubstepAnalysis,
    section: Quantum,
    pools: Pools,
    count: Int
): List<List<Quantum>>? {
    val beats = beatsInSection(analysis, section)
    if (beats.isEmpty()) return null
    val barStarts = analysis.bars.orEmpty().map { it.start }.toSet()
    val used = identitySet<Quantum>()
    val starts = mutableListOf<Int>()
    for (i in 0 until count) {
        val start = bestWindow(beats, pools, barStarts, used) ?: break
        starts += start
        used.addAll(beats.subList(start, start + PHRASE_BEATS))
    }
    if (starts.isEmpty()) return listOf(cycle(beats, PHRASE_BEATS))
    starts.sort()
    return starts.map { start -> beats.subList(start, start + PHRASE_BEATS).toList() }
}

// Beat pools for the section: tonic, +3 and +9, each falling back to the fullest pool, then to
// any pitch, then to every beat of the section.
private fun sectionPools(analysis: DubstepAnalysis, sectionIndex: Int, tonic: Int): Pools? {
    fun find(pitch: Int) = searchSamples(analysis, sectionIndex, pitch)
    val s1 = find(tonic)
    val s2 = find((tonic + MINOR_THIRD) % PITCH_CLASSES)
    val s3 = find((tonic + MAJOR_SIXTH) % PITCH_CLASSES)
    var biggest = listOf(s2, s3).fold(s1) { a, b -> if (b.size > a.size) b else a }
    var i = 0
    while (i < PITCH_CLASSES && biggest.isEmpty()) {
        biggest = find((tonic + i) % PITCH_CLASSES)
        i += 1
    }
    if (biggest.isEmpty()) {
        biggest = beatsInSection(analysis, analysis.sections[sectionIndex])
    }
    if (biggest.isEmpty()) return null
    return listOf(
        s1.ifEmpty { biggest },
        s2.ifEmpty { biggest },
        s3.ifEmpty { biggest }
    )
}

// Source beats for each of the section's `partCount` parts, 32 per part. The original cycles
// through each pool in order and repeats one 16-beat bar everywhere; `contiguous` instead walks
// the section phrase by phrase.
private fun sectionParts(
    analysis: DubstepAnalysis,
    sectionIndex: Int,
    tonic: Int,
    partCount: Int,
    contiguous: Boolean
): List<List<SourceSlice>>? {
    val pools = sectionPools(analysis, sectionIndex, tonic) ?: return null
    if (!contiguous) {
        val bar = List(PHRASE_BEATS) { i ->
            val pool = pools[poolSlot(i)]
            pool[i % pool.size]
        }
        val slices = (bar + bar).map(::wholeBeat)
        return List(partCount) { slices }
    }
    val phrases = contiguousPhrases(
        analysis,
        analysis.sections[sectionIndex],
        pools,
        partCount * 2
    ) ?: return null
    return List(partCount) { part ->
        (phrases[(part * 2) % phrases.size] + phrases[(part * 2 + 1) % phrases.size]).map(::wholeBeat)
    }
}

// Parts a section earns by length: under 12 bars a drop alone, under 24 bars a drop and a break,
// longer sections two of each.
private fun sectionPartCount(beatCount: Int): Int = when {
    beatCount < SINGLE_PART_MAX_BEATS -> 1
    beatCount < TWO_PART_MAX_BEATS -> 2
    else -> 4
}

private const val SINGLE_PART_MAX_BEATS = 48
private const val TWO_PART_MAX_BEATS = 96

// Folds sections shorter than four bars into the section after them (the last one into the
// section before it).
private fun mergeShortSections(analysis: DubstepAnalysis): List<Quantum> {
    val merged = mutableListOf<Quantum>()
    var pending: Quantum? = null
    for (section in analysis.sections) {
        val combined = pending?.let { Quantum(it.start, section.end - it.start) } ?: section
        if (beatsInSection(analysis, combined).size < MIN_SECTION_BEATS) {
            pending = combined
            continue
        }
        merged += combined
        pending = null
    }
    pending?.let { tail ->
        val last = merged.removeLastOrNull()
        merged += if (last != null) Quantum(last.start, tail.end - last.start) else tail
    }
    return merged
}

// Drops sections whose peak loudness sits well under the track's.
private fun dropQuietSections(analysis: DubstepAnalysis): List<Quantum> {
    val reference = trackLoudness(analysis.segments)
    return analysis.sections.filter { section ->
        val spanned = analysis.segments.filter { segment ->
            segment.end > section.start && segment.start < section.end
        }
        spanned.isNotEmpty() && trackLoudness(spanned) >= reference - QUIET_SECTION_DB
    }
}

// Replaces the last two beats with a ramping stutter: the second-last beat as two halves, the
// last as four quarters.
private fun withFill(slices: List<SourceSlice>): List<SourceSlice> {
    if (slices.size < 2) return slices
    val half = slices[slices.size - 2]
    val quarter = slices[slices.size - 1]
    return slices.dropLast(2) + repeated(cut(half, 2.0), 2) + repeated(cut(quarter, 4.0), 4)
}

private class PartBuilder(
    private val analysis: DubstepAnalysis,
    private val options: DubstepPlanOptions,
    private val key: String
) {
    val parts = mutableListOf<DubstepPart>()
    private var consecutiveDrops = 0

    fun addSection(sectionIndex: Int, section: Quantum, tonic: Int) {
        val partCount = if (options.sectionBudget) {
            sectionPartCount(beatsInSection(analysis, section).size)
        } else {
            2
        }
        val sectionSlices = sectionParts(analysis, sectionIndex, tonic, partCount, options.contiguous)
            ?: return
        val splash = SPLASH_ORDER[(sectionIndex + 1) % SPLASH_ORDER.size]
        sectionSlices.forEachIndexed { p, slices ->
            // Drops and breaks alternate within a section; drop-only sections never stack more
            // than two drops in a row.
            val drop = p % 2 == 0 && consecutiveDrops < 2
            consecutiveDrops = if (drop) consecutiveDrops + 1 else 0
            val mix = mixFactor(analysis, slices)
            parts += if (drop) {
                DubstepPart(
                    kind = DubstepPartKind.Drop,
                    label = "section ${sectionIndex + 1} drop",
                    samples = listOf("wubs/$key", splashName(splash)),
                    slices = slices,
                    mix = if (options.contrast) clampMix(mix + MIX_TILT) else mix
                )
            } else {
                DubstepPart(
                    kind = DubstepPartKind.Break,
                    label = "section ${sectionIndex + 1} break",
                    samples = listOf("break-ends/$key", "hats"),
                    slices = slices,
                    mix = if (options.contrast) clampMix(mix - MIX_TILT) else mix
                )
            }
        }
    }

    fun applyFills() {
        for (i in parts.indices) {
            val part = parts[i]
            val next = parts.getOrNull(i + 1)
            if (part.kind != DubstepPartKind.Intro && next?.kind == DubstepPartKind.Drop) {
                parts[i] = part.copy(slices = withFill(part.slices))
            }
        }
    }
}

fun planDubstepRemix(
    input: DubstepAnalysis,
    options: DubstepPlanOptions = DubstepPlanOptions()
): DubstepPlan {
    var analysis = input
    if (options.sectionBudget) {
        analysis = analysis.copy(sections = mergeShortSections(analysis))
    }
    if (options.skipQuiet) {
        analysis = analysis.copy(sections = dropQuietSections(analysis))
    }
    val duration = analysis.track?.duration ?: (analysis.segments.lastOrNull()?.end ?: 0.0)
    val tonic = options.tonic ?: estimateTonic(analysis.segments)
    require(tonic in 0 until PITCH_CLASSES) { "tonic must be a pitch class 0..11, got $tonic" }
    val key = KEY_FILES[tonic]

    val intro = introSlices(analysis, duration)
    val builder = PartBuilder(analysis, options, key)
    builder.parts += DubstepPart(
        kind = DubstepPartKind.Intro,
        label = "intro",
        samples = listOf("intro-eight"),
        slices = intro,
        mix = mixFactor(analysis, intro)
    )
    analysis.sections.forEachIndexed { j, section -> builder.addSection(j, section, tonic) }
    if (options.fills) {
        builder.applyFills()
    }
    builder.parts += DubstepPart(
        kind = DubstepPartKind.Ending,
        label = "ending",
        samples = listOf("splash-ends/${(max(analysis.sections.size, 1) % SPLASH_END_COUNT) + 1}"),
        slices = emptyList(),
        mix = 1.0
    )

    return DubstepPlan(tonic = tonic, parts = builder.parts.toList())
}
