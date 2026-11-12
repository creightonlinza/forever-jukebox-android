package com.foreverjukebox.app.ui.onboarding

import androidx.compose.ui.geometry.Rect
import com.foreverjukebox.app.ui.TabId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTourTest {
    private val fresh = OnboardingRecord(emptySet(), skipped = false, baselineVersion = ONBOARDING_VERSION)

    private fun candidates(tab: TabId, playbackReady: Boolean) =
        OnboardingTourId.entries.filter { isTourScreenActive(it, tab, playbackReady) }

    private val allPlayback = setOf(
        OnboardingTarget.PlaybackMode,
        OnboardingTarget.VisualizationPicker,
        OnboardingTarget.ToolRow
    )

    @Test
    fun entryTourStartsOnInputTabWhenAddAudioIsPlaced() {
        val tour = resolveTourToStart(candidates(TabId.Input, false), fresh, setOf(OnboardingTarget.AddAudio))
        assertEquals(OnboardingTourId.Entry, tour)
    }

    @Test
    fun entryTourShowsAnalyzedTracksBeforeAddAudio() {
        val placed = setOf(OnboardingTarget.CompletedAnalysis, OnboardingTarget.AddAudio)
        assertEquals(0, firstPlacedStep(OnboardingTourId.Entry, 0, placed))
        assertEquals(1, firstPlacedStep(OnboardingTourId.Entry, 1, placed))
        assertNull(firstPlacedStep(OnboardingTourId.Entry, 2, placed))
    }

    @Test
    fun completedTourDoesNotStartAgain() {
        val record = fresh.copy(completed = setOf(OnboardingTourId.Entry))
        assertNull(resolveTourToStart(candidates(TabId.Input, false), record, setOf(OnboardingTarget.AddAudio)))
    }

    @Test
    fun skippingEndsEveryTourForGood() {
        val record = fresh.copy(skipped = true)
        assertTrue(OnboardingTourId.entries.all(record::isDone))
        assertNull(resolveTourToStart(OnboardingTourId.entries, record, OnboardingTarget.entries.toSet()))
    }

    @Test
    fun aTourAddedAfterTheFirstRunIsNotShown() {
        val laterTour = OnboardingTourId.entries.maxBy { it.sinceVersion }
        val record = fresh.copy(baselineVersion = laterTour.sinceVersion - 1)
        assertTrue(record.isDone(laterTour))
        assertFalse(fresh.isDone(laterTour))
    }

    @Test
    fun tuningTourRunsOnlyInsideItsDialog() {
        assertFalse(OnboardingTourId.Tuning in candidates(TabId.Play, true))
        val tuningTargets = OnboardingTours.steps(OnboardingTourId.Tuning).map { it.target }.toSet()
        assertEquals(
            OnboardingTourId.Tuning,
            resolveTourToStart(listOf(OnboardingTourId.Tuning), fresh, tuningTargets)
        )
        assertEquals(
            listOf(
                OnboardingTarget.TuningThreshold,
                OnboardingTarget.TuningMinJumpDistance,
                OnboardingTarget.TuningBranchProbability,
                OnboardingTarget.TuningAudioModeTab
            ),
            OnboardingTours.steps(OnboardingTourId.Tuning).map { it.target }
        )
    }

    @Test
    fun playbackTourWaitsForTheScreenToBeReady() {
        assertNull(resolveTourToStart(candidates(TabId.Play, false), fresh, allPlayback))
        assertEquals(
            OnboardingTourId.Playback,
            resolveTourToStart(candidates(TabId.Play, true), fresh, allPlayback)
        )
    }

    @Test
    fun playbackTourNeedsATargetOnScreen() {
        assertNull(resolveTourToStart(candidates(TabId.Play, true), fresh, emptySet()))
    }

    @Test
    fun nextStepSkipsStepsWhoseTargetIsMissing() {
        val placed = setOf(OnboardingTarget.PlaybackMode, OnboardingTarget.ToolRow)
        assertEquals(2, firstPlacedStep(OnboardingTourId.Playback, 1, placed))
    }

    @Test
    fun noPlacedStepAfterTheLastOneEndsTheTour() {
        assertNull(firstPlacedStep(OnboardingTourId.Playback, 1, setOf(OnboardingTarget.PlaybackMode)))
    }

    @Test
    fun calloutGoesBelowATargetNearTheTop() {
        val placement = placeCallout(
            containerWidth = 400f,
            containerHeight = 800f,
            anchor = Rect(20f, 40f, 120f, 80f),
            calloutWidth = 300f,
            calloutHeight = 150f,
            margin = 16f,
            gap = 10f,
            caretInset = 20f
        )
        assertTrue(placement.below)
        assertEquals(90f, placement.y)
        assertEquals(16f, placement.x)
        assertEquals(54f, placement.caretX)
    }

    @Test
    fun calloutGoesAboveATargetNearTheBottom() {
        val placement = placeCallout(
            containerWidth = 400f,
            containerHeight = 800f,
            anchor = Rect(300f, 700f, 390f, 760f),
            calloutWidth = 300f,
            calloutHeight = 150f,
            margin = 16f,
            gap = 10f,
            caretInset = 20f
        )
        assertFalse(placement.below)
        assertEquals(540f, placement.y)
        assertEquals(84f, placement.x)
        assertEquals(261f, placement.caretX)
    }

    @Test
    fun tagsPushedInFromTheEdgeMoveTheirNeighbors() {
        val lefts = spreadTags(
            centers = listOf(800f, 960f, 1010f),
            widths = listOf(60f, 50f, 100f),
            containerWidth = 1040f,
            gap = 4f
        )
        assertEquals(listOf(770f, 886f, 940f), lefts)
    }
}
