package com.foreverjukebox.app.ui.loading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingModelTest {

    @Test
    fun jobStepMatchesTheWebMapping() {
        fun step(status: String, progress: Double?) = jobStep(status, progress, null).let { it.activeId to it.progress }
        assertEquals(LoadingStepId.Fetch to null, step("downloading", null))
        assertEquals(LoadingStepId.Fetch to 0.4f, step("downloading", 10.0))
        assertEquals(LoadingStepId.Beats to null, step("processing", 0.0))
        assertEquals(LoadingStepId.Beats to 0.5f, step("processing", 47.5))
        assertEquals(LoadingStepId.Features to 0.5f, step("processing", 90.0))
        assertEquals(LoadingStepId.Track to null, step("processing", 95.0))
    }

    @Test
    fun queuedStepCarriesTheQueuePosition() {
        assertEquals(3, jobStep("queued", 25.0, "Queued • 3 ahead of you").queuedAhead)
        assertNull(jobStep("queued", 25.0, null).queuedAhead)
        assertEquals("Queued • Next in line", serverJobLoadingStep("queued", 25.0, null).message)
    }

    private fun live(task: LoadingTask, key: String = "a", finished: Boolean = false) =
        LiveLoading(task, key, task.steps.first(), null, null, finished)

    @Test
    fun presentationQueuesModeRendersAndReplacesEverythingElse() {
        val analysis = ShownLoading(LoadingTask.ServerAnalysis, "a")
        assertEquals(analysis, presentNext(null, live(LoadingTask.ServerAnalysis)))
        assertNull(presentNext(null, live(LoadingTask.ServerAnalysis, finished = true)))
        // A Swing render for the same track waits until the analysis has played out.
        assertEquals(analysis, presentNext(analysis, live(LoadingTask.Swing)))
        // A non-mode task for the same track, or any task for another track, replaces it.
        assertEquals(ShownLoading(LoadingTask.Track, "a"), presentNext(analysis, live(LoadingTask.Track)))
        assertEquals(ShownLoading(LoadingTask.Track, "b"), presentNext(analysis, live(LoadingTask.Track, "b")))
        assertNull(presentNext(analysis, null))
        assertEquals(ShownLoading(LoadingTask.Swing, "a"), afterPresentation(live(LoadingTask.Swing)))
        assertNull(afterPresentation(live(LoadingTask.Swing, finished = true)))
    }

    private fun play(steps: Int, real: (Long) -> RealStep, finalDwellMs: Long): List<Pair<Long, PacedView>> {
        var view = PacedView(0, 0f, 0L, null)
        val frames = mutableListOf<Pair<Long, PacedView>>()
        val seen = mutableSetOf<Int>()
        var now = 0L
        while (view.doneAtMs == null && now < 60_000L) {
            now += LoadingTimings.TICK_MS
            val r = real(now)
            seen += r.index
            view = advanceView(view, r.copy(seen = seen), steps, now, LoadingTimings.TICK_MS, finalDwellMs)
            frames += now to view
        }
        return frames
    }

    private fun shownFor(frames: List<Pair<Long, PacedView>>, index: Int): Long {
        val times = frames.filter { it.second.index == index }.map { it.first }
        return times.last() - times.first() + LoadingTimings.TICK_MS
    }

    @Test
    fun pacingShowsEveryStepAndNeverMovesBackward() {
        val steps = LoadingTask.ServerAnalysis.steps
        // The real load jumps straight from fetching to done, so the middle steps are seen only on screen.
        val frames = play(steps.size, { t ->
            if (t < 1_000L) RealStep(0, null, false, emptySet()) else RealStep(4, null, true, emptySet())
        }, LoadingTimings.FINAL_STEP_MS)
        for (index in 1..3) {
            assertTrue("step $index shown long enough", shownFor(frames, index) >= LoadingTimings.SKIPPED_STEP_MS)
        }
        assertTrue(shownFor(frames, 4) >= LoadingTimings.FINAL_STEP_MS)
        val percents = frames.map { overallPercent(steps, it.second) }
        assertTrue(percents.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(100, percents.last())
    }

    @Test
    fun aRealStepWithProgressFillsTowardItWithoutAdvancing() {
        val view = PacedView(0, 0f, 0L, null)
        val next = advanceView(view, RealStep(0, 0.8f, false, setOf(0)), 3, 50L, 50L, 0L)
        assertEquals(0, next.index)
        assertTrue(next.fill > 0f && next.fill < 0.8f)
    }

    @Test
    fun ringArcsMatchTheWebSeed() {
        // Endpoints the web's createRng("seeded", 11) produces for the 12 arcs.
        val web = listOf(
            36 to 1, 43 to 11, 61 to 26, 5 to 42, 43 to 4, 34 to 15,
            30 to 2, 49 to 67, 12 to 36, 28 to 57, 41 to 57, 42 to 9
        )
        assertEquals(web.map { (a, b) -> ringPoint(a) to ringPoint(b) }, RING_BRANCHES.map { it.start to it.end })
        assertTrue(RING_BRANCHES[7].selected)
    }

    @Test
    fun arcsFinishBeforeTheLastStep() {
        val start = lastStepStartPercent(LoadingTask.ServerAnalysis.steps)
        assertEquals(90f, start, 0.001f)
        assertTrue(RING_BRANCHES.all { it.showAt * start < start })
    }
}
