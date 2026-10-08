package com.foreverjukebox.app.wubmachine

import com.foreverjukebox.app.engine.Segment
import com.foreverjukebox.app.engine.TrackMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/** Mirrors dubstepArrangement.test.ts in the web repo. */
class DubstepArrangementTest {

    private fun segment(start: Double, pitch: Int, loudness: Double = -10.0): Segment {
        val pitches = MutableList(12) { 0.1 }
        pitches[pitch] = 1.0
        return Segment(
            start = start,
            duration = 0.5,
            confidence = 1.0,
            loudnessStart = -60.0,
            loudnessMax = loudness,
            loudnessMaxTime = 0.0,
            pitches = pitches,
            timbre = List(12) { 0.0 },
            which = 0
        )
    }

    // 64 half-second beats in two sections; one segment per beat, starting a quarter beat late
    // so each segment ends inside the following beat.
    private fun makeAnalysis(pitchAt: (Int) -> Int): DubstepAnalysis {
        val beats = List(64) { i -> Quantum(i * 0.5, 0.5) }
        return DubstepAnalysis(
            sections = listOf(Quantum(0.0, 16.0), Quantum(16.0, 16.0)),
            beats = beats,
            segments = beats.mapIndexed { i, beat -> segment(beat.start + 0.125, pitchAt(i)) },
            track = TrackMeta(duration = 32.0, tempo = 120.0, timeSignature = 4.0)
        )
    }

    private fun makeLongAnalysis(beatCount: Int): DubstepAnalysis {
        val beats = List(beatCount) { i -> Quantum(i * 0.5, 0.5) }
        return DubstepAnalysis(
            sections = listOf(Quantum(0.0, beatCount * 0.5)),
            beats = beats,
            segments = beats.map { beat -> segment(beat.start + 0.125, 0) },
            track = TrackMeta(duration = beatCount * 0.5, tempo = 120.0, timeSignature = 4.0)
        )
    }

    private fun withSections(analysis: DubstepAnalysis, beatCounts: List<Int>): DubstepAnalysis {
        var start = 0.0
        val sections = beatCounts.map { count ->
            val section = Quantum(start, count * 0.5)
            start += count * 0.5
            section
        }
        return analysis.copy(sections = sections)
    }

    // Per bar: the tonic, its minor third, its minor seventh and its fifth.
    private val analysis = makeAnalysis { beat -> listOf(0, 3, 10, 7)[beat % 4] }
    private val plan = planDubstepRemix(analysis)

    @Test
    fun estimateTonicFindsTheTonicOfAMinorScaleWeightedTowardItsTriad() {
        val weights = listOf(0, 3, 7, 0, 3, 7, 0, 2, 5, 8, 10)
        val segments = weights.mapIndexed { i, degree -> segment(i.toDouble(), (degree + 2) % 12) }
        assertEquals(2, estimateTonic(segments))
    }

    @Test
    fun estimateTonicAnswersAMajorKeyWithItsRelativeMinor() {
        val weights = listOf(0, 4, 7, 0, 4, 7, 0, 2, 5, 9, 11)
        val segments = weights.mapIndexed { i, degree -> segment(i.toDouble(), (degree + 2) % 12) }
        assertEquals(11, estimateTonic(segments))
    }

    @Test
    fun mixFactorFollowsTheLoudnessOfTheSpannedSegmentsAndClamps() {
        val loud = makeAnalysis { 0 }
        val slices = listOf(loud.beats[0], loud.beats[3])
        assertEquals((-10 + 89 / 1.5 + 18) / (-10 + 188 / 1.5 + 18), mixFactor(loud, slices), 1e-9)
        val quiet = loud.copy(segments = loud.segments.map { it.copy(loudnessMax = -77.0) })
        assertEquals(0.3, mixFactor(quiet, slices), 0.0)
    }

    @Test
    fun buildsAnIntroTwoPartsPerSectionAndAnEnding() {
        assertEquals(
            listOf("intro", "section 1 drop", "section 1 break", "section 2 drop", "section 2 break", "ending"),
            plan.parts.map { it.label }
        )
    }

    @Test
    fun stuttersTheIntroOver32Beats() {
        val intro = plan.parts[0]
        assertEquals(listOf("intro-eight"), intro.samples)
        assertEquals(48, intro.slices.size)
        assertEquals(32 * 0.5, intro.slices.sumOf { it.duration }, 1e-9)
        assertEquals(SourceSlice(0.0, 0.5, 1.0), intro.slices[16])
        assertEquals(SourceSlice(4.0, 0.25, 0.5), intro.slices[24])
        assertEquals(SourceSlice(7.0, 0.125, 0.25), intro.slices[40])
        assertEquals(32.0, intro.slices.sumOf { it.beats }, 0.0)
    }

    @Test
    fun picksSectionBeatsBySegmentPitchTonicPlus3Plus10() {
        val drop = plan.parts[3]
        assertEquals(0, plan.tonic)
        assertEquals(32, drop.slices.size)
        assertSame(drop.slices, plan.parts[4].slices)
        // A segment ends in the beat after the one it starts in.
        fun pitchOf(start: Double) = listOf(0, 3, 10, 7)[((start / 0.5).toInt() - 1) % 4]
        val pitches = drop.slices.take(16).map { pitchOf(it.start) }
        assertEquals(List(8) { 0 } + listOf(3, 3, 3, 3, 10, 10, 10, 10), pitches)
        assertTrue(drop.slices.all { it.start >= 16 })
        assertEquals(drop.slices.take(16), drop.slices.drop(16))
    }

    @Test
    fun namesSamplesByKeySectionIndexAndSectionCount() {
        // Segment pitches start at A, so tonic 0 is A and tonic 2 is B.
        assertEquals(listOf("wubs/a", "splashes/splash_04"), plan.parts[1].samples)
        assertEquals(listOf("break-ends/a", "hats"), plan.parts[2].samples)
        assertEquals("wubs/b", planDubstepRemix(analysis, DubstepPlanOptions(tonic = 2)).parts[1].samples[0])
        assertEquals("splashes/splash_02", plan.parts[3].samples[1])
        assertEquals(
            DubstepPart(
                kind = DubstepPartKind.Ending,
                label = "ending",
                samples = listOf("splash-ends/3"),
                slices = emptyList(),
                mix = 1.0
            ),
            plan.parts[5]
        )
    }

    @Test
    fun walksTheSectionIn16BeatPhrasesWhenContiguous() {
        val bars = List(16) { i -> Quantum(i * 2.0, 2.0) }
        val contiguous = planDubstepRemix(analysis.copy(bars = bars), DubstepPlanOptions(contiguous = true))
        val drop = contiguous.parts[3]
        val starts = drop.slices.map { (it.start / 0.5).toInt() }
        // Section 2 holds exactly two phrases; the drop plays them in order and the break, with
        // no phrases left, plays the same two again.
        assertEquals(List(32) { i -> 32 + i }, starts)
        assertEquals(drop.slices, contiguous.parts[4].slices)
        assertTrue(drop.slices.all { it.beats == 1.0 })
    }

    @Test
    fun favoursTheWindowWithTheMostMatchingBeats() {
        // Tonic only on beats 44..51 of section 2; everything else is G.
        val sparse = makeAnalysis { beat -> if (beat >= 43 && beat < 51) 0 else 7 }
        val starts = planDubstepRemix(sparse, DubstepPlanOptions(contiguous = true, tonic = 0))
            .parts[3].slices.take(16).map { (it.start / 0.5).toInt() }
        assertTrue(44 in starts)
        assertTrue(51 in starts)
        assertEquals(List(16) { i -> starts[0] + i }, starts)
    }

    @Test
    fun mergesTheSongDownToAHandfulOfSections() {
        // Twelve 40-beat sections, 480 beats: four remain, a drop and a break each.
        val long = withSections(makeLongAnalysis(480), List(12) { 40 })
        val merged = planDubstepRemix(
            long,
            DubstepPlanOptions(sectionBudget = true, contiguous = true, tonic = 0)
        )
        assertEquals(
            listOf(
                "intro",
                "section 1 drop",
                "section 1 break",
                "section 2 drop",
                "section 2 break",
                "section 3 drop",
                "section 3 break",
                "section 4 drop",
                "section 4 break",
                "ending"
            ),
            merged.parts.map { it.label }
        )
        // Sections of 160, 160, 80 and 80 beats, in song order.
        val starts = merged.parts.drop(1).dropLast(1).map { it.slices[0].start }
        assertEquals(starts.sorted(), starts)
        assertTrue(merged.parts[1].slices.all { it.start < 80 })
        assertTrue(merged.parts[7].slices.all { it.start >= 200 })
    }

    @Test
    fun sizesEachSectionsShareByItsLength() {
        // Sections of 8, 32 and 220 beats: the first folds into the second.
        val long = withSections(makeLongAnalysis(260), listOf(8, 32, 220))
        val budgeted = planDubstepRemix(long, DubstepPlanOptions(sectionBudget = true, tonic = 0))
        assertEquals(
            listOf(
                "intro",
                "section 1 drop",
                "section 2 drop",
                "section 2 break",
                "section 2 drop",
                "section 2 break",
                "ending"
            ),
            budgeted.parts.map { it.label }
        )
        assertTrue(budgeted.parts[1].slices.all { it.start < 20 })
    }

    @Test
    fun neverStacksMoreThanTwoDropsInARow() {
        val short = withSections(makeLongAnalysis(96), listOf(32, 32, 32))
        val budgeted = planDubstepRemix(short, DubstepPlanOptions(sectionBudget = true))
        assertEquals(
            listOf(
                DubstepPartKind.Intro,
                DubstepPartKind.Drop,
                DubstepPartKind.Drop,
                DubstepPartKind.Break,
                DubstepPartKind.Ending
            ),
            budgeted.parts.map { it.kind }
        )
    }

    private fun quietBetween(analysis: DubstepAnalysis, from: Double, until: Double): DubstepAnalysis {
        return analysis.copy(
            segments = analysis.segments.map {
                if (it.start >= from && it.start < until) it.copy(loudnessMax = -80.0) else it
            }
        )
    }

    private val budgetSkippingQuiet =
        DubstepPlanOptions(sectionBudget = true, skipQuiet = true, contiguous = true, tonic = 0)

    @Test
    fun neverMergesAcrossASkippedSection() {
        // Two 24-beat sections either side of a quiet one stay apart.
        val split = quietBetween(withSections(makeLongAnalysis(80), listOf(24, 32, 24)), 12.0, 28.0)
        val merged = planDubstepRemix(split, budgetSkippingQuiet)
        assertEquals(
            listOf("intro", "section 1 drop", "section 2 drop", "ending"),
            merged.parts.map { it.label }
        )
        assertTrue(merged.parts[1].slices.all { it.start < 12 })
        assertTrue(merged.parts[2].slices.all { it.start >= 28 })
    }

    @Test
    fun leavesOutAShortSectionStrandedByASkippedOne() {
        // 8 beats, a quiet section, then 40 beats: the 8 have no neighbour.
        val stranded = quietBetween(withSections(makeLongAnalysis(80), listOf(8, 32, 40)), 4.0, 20.0)
        val merged = planDubstepRemix(stranded, budgetSkippingQuiet)
        assertEquals(listOf("intro", "section 1 drop", "ending"), merged.parts.map { it.label })
        assertTrue(merged.parts[1].slices.all { it.start >= 20 })
    }

    @Test
    fun balancesEachPartAgainstItsSampleBed() {
        // Segments peak at -10 dB, so the song averages about -14.3 dBFS.
        val balanced = planDubstepRemix(analysis, DubstepPlanOptions(balance = true)).parts
        val (intro, drop, brk) = balanced
        // Song over bed (dB) once both are scaled by the mix.
        fun songOverBed(mix: Double, bedLevel: Double, peak: Double = -10.0) =
            20 * log10((1 - mix) / mix) + (peak - 4.3) - bedLevel
        assertEquals(0.0, songOverBed(intro.mix, -7.1), 1e-9)
        assertEquals(0.0, songOverBed(drop.mix, -12.9), 1e-9)
        // The break wants the song 8 dB over, more than the bed gain limit allows.
        assertEquals(0.3, brk.mix, 0.0)
        assertEquals(plan.parts[2].samples, brk.samples)

        // A louder song gets more of the bed; a quieter one less.
        val base = makeAnalysis { beat -> listOf(0, 3, 10, 7)[beat % 4] }
        val loud = base.copy(segments = base.segments.map { it.copy(loudnessMax = -4.0) })
        val loudDrop = planDubstepRemix(loud, DubstepPlanOptions(balance = true)).parts[1]
        assertTrue(loudDrop.mix > drop.mix)
        assertEquals(0.0, songOverBed(loudDrop.mix, -12.9, peak = -4.0), 1e-9)
    }

    @Test
    fun stuttersTheLastTwoBeatsBeforeEachDrop() {
        val filled = planDubstepRemix(analysis, DubstepPlanOptions(fills = true))
        val before = filled.parts[2].slices
        assertEquals(36, before.size)
        assertEquals(listOf(0.5, 0.5, 0.25, 0.25, 0.25, 0.25), before.drop(30).map { it.beats })
        assertEquals(before[30].start, before[31].start, 0.0)
        assertEquals(32.0, before.sumOf { it.beats }, 0.0)
        // The intro already stutters; the last break has no drop after it.
        assertEquals(48, filled.parts[0].slices.size)
        assertEquals(32, filled.parts[4].slices.size)
    }

    @Test
    fun copesWithTracksTheAnalysisBarelyDescribes() {
        val bare = planDubstepRemix(DubstepAnalysis(sections = emptyList(), beats = emptyList(), segments = emptyList()))
        assertEquals(listOf(DubstepPartKind.Intro, DubstepPartKind.Ending), bare.parts.map { it.kind })
        assertTrue(bare.parts[0].slices.all { it.duration == 0.0 })

        // Too few beats: the intro is cut from 16 equal slices of the track.
        val short = planDubstepRemix(
            DubstepAnalysis(
                sections = listOf(Quantum(0.0, 32.0)),
                beats = listOf(Quantum(0.0, 1.0)),
                segments = emptyList(),
                track = TrackMeta(duration = 32.0, tempo = 100.0)
            )
        )
        assertEquals(SourceSlice(2.0, 2.0, 1.0), short.parts[0].slices[1])
        // Beats exist but no segment matches: every section beat is used.
        assertTrue(short.parts[1].slices.all { it.start == 0.0 })
    }

    @Test
    fun leavesOutSectionsFarQuieterThanTheTrack() {
        val loud = makeAnalysis { 0 }
        val quiet = loud.copy(
            segments = loud.segments.map { if (it.start >= 16) it.copy(loudnessMax = -50.0) else it }
        )
        val skipped = planDubstepRemix(quiet, DubstepPlanOptions(skipQuiet = true))
        assertEquals(
            listOf("intro", "section 1 drop", "section 1 break", "ending"),
            skipped.parts.map { it.label }
        )
    }

    @Test
    fun fallsBackToOtherPitchesWhenASectionLacksTheTonic() {
        val sparse = makeAnalysis { beat -> if (beat < 32) 7 else 0 }
        val sparsePlan = planDubstepRemix(sparse)
        assertEquals(32, sparsePlan.parts[1].slices.size)
        assertTrue(sparsePlan.parts[1].slices.all { it.start < 16.5 })
    }

    @Test
    fun skipsASectionWithoutBeatsEvenWhenLaterSectionsMatch() {
        val gapped = makeAnalysis { 0 }.copy(
            sections = listOf(Quantum(0.1, 0.25), Quantum(0.35, 31.65))
        )
        val skipped = planDubstepRemix(gapped, DubstepPlanOptions(contiguous = true, tonic = 0))
        assertEquals(
            listOf("intro", "section 2 drop", "section 2 break", "ending"),
            skipped.parts.map { it.label }
        )
    }

    @Test
    fun listsEverySampleAPlanCanName() {
        assertEquals(41, DUBSTEP_SAMPLE_NAMES.size)
        assertEquals(DUBSTEP_SAMPLE_NAMES.size, DUBSTEP_SAMPLE_NAMES.toSet().size)
        val named = planDubstepRemix(analysis, WUB_MACHINE_PLAN_OPTIONS).parts.flatMap { it.samples }
        assertTrue(named.all { it in DUBSTEP_SAMPLE_NAMES })
    }
}
