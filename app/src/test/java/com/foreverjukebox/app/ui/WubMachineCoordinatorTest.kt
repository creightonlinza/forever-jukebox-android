package com.foreverjukebox.app.ui

import com.foreverjukebox.app.engine.TrackMeta
import com.foreverjukebox.app.wubmachine.DubstepAnalysis
import com.foreverjukebox.app.wubmachine.DubstepPartKind
import com.foreverjukebox.app.wubmachine.WubRemix
import com.foreverjukebox.app.wubmachine.WubRenderedPart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WubMachineCoordinatorTest {

    private val remix = WubRemix(
        parts = listOf(
            WubRenderedPart(DubstepPartKind.Intro, "intro", 0.0, 13.7),
            WubRenderedPart(DubstepPartKind.Ending, "ending", 13.7, 2.0)
        ),
        durationSeconds = 15.7,
        peaks = listOf(0.1f, 0.5f)
    )

    private class FakeRenderer(private val remix: WubRemix) : WubMachineRenderer {
        var installed = false
        var result = true
        val renders = mutableListOf<DubstepAnalysis>()

        // Runs between the two progress reports, standing in for state changes
        // that land while a slice is being stretched.
        var midRender: () -> Unit = {}

        override fun hasRemix(): Boolean = installed

        override fun currentRemix(): WubRemix? = remix.takeIf { installed }

        override fun render(
            analysis: DubstepAnalysis,
            onProgress: (completed: Int, total: Int) -> Boolean
        ): WubRemix? {
            renders += analysis
            val keepGoing = onProgress(1, 2)
            midRender()
            if (!keepGoing || !onProgress(2, 2)) return null
            installed = result
            return remix.takeIf { result }
        }
    }

    private object PassThroughHold : AudioLoadHold {
        override suspend fun <T> hold(block: suspend () -> T): T = block()
    }

    private inner class Harness(scope: TestScope) {
        val renderer = FakeRenderer(remix)
        var analysis: DubstepAnalysis? = DubstepAnalysis(
            sections = emptyList(),
            beats = emptyList(),
            segments = emptyList(),
            track = TrackMeta(duration = 10.0)
        )
        var playback = PlaybackState()
            private set
        val progressHistory = mutableListOf<Int?>()
        val blocked = mutableListOf<Boolean>()
        val ready = mutableListOf<Boolean>()
        var failures = 0
        var pauses = 0
        val coordinator = WubMachineCoordinator(
            scope = scope,
            renderer = renderer,
            getAnalysis = { analysis },
            getPlayback = { playback },
            updatePlayback = { transform -> update(transform) },
            setPlaybackBlocked = { blocked += it },
            pausePlayback = {
                pauses += 1
                if (playback.isRunning) {
                    update { it.copy(isRunning = false, isPaused = true) }
                }
            },
            onReady = { ready += it },
            onFailed = { failures += 1 },
            audioLoadHold = PassThroughHold,
            renderDispatcher = StandardTestDispatcher(scope.testScheduler)
        )

        fun update(transform: (PlaybackState) -> PlaybackState) {
            val next = transform(playback)
            if (next.wubProgress != playback.wubProgress) {
                progressHistory += next.wubProgress
            }
            playback = next
        }
    }

    private fun wubTrack(jobId: String = "job-a"): PlaybackState {
        return PlaybackState(
            playMode = PlaybackMode.WubMachine,
            audioLoaded = true,
            analysisLoaded = true,
            lastJobId = jobId
        )
    }

    @Test
    fun rendersLoadedTrackAndMarksItReady() = runTest {
        val harness = Harness(this)
        harness.update { wubTrack() }

        harness.coordinator.sync()

        assertEquals(listOf(true), harness.blocked)
        assertEquals(0, harness.playback.wubProgress)
        assertTrue(harness.playback.isPreparingWubMachine())

        advanceUntilIdle()

        assertEquals(1, harness.renderer.renders.size)
        assertEquals(listOf(true, false), harness.blocked)
        assertTrue(harness.playback.wubReady)
        assertNull(harness.playback.wubProgress)
        assertEquals(remix.parts, harness.playback.wubMachine.parts)
        assertEquals(remix.peaks, harness.playback.wubMachine.peaks)
        assertEquals(15.7, harness.playback.wubMachine.durationSeconds, 0.0)
        assertFalse(harness.playback.isPreparingWubMachine())
        assertEquals(listOf(false), harness.ready)
        assertEquals(0, harness.failures)
    }

    @Test
    fun pausesRunningPlaybackAndResumesItWhenReady() = runTest {
        val harness = Harness(this)
        harness.update { wubTrack().copy(isRunning = true) }

        harness.coordinator.sync()

        assertEquals(1, harness.pauses)
        assertFalse(harness.playback.isRunning)

        advanceUntilIdle()

        assertEquals(listOf(true), harness.ready)
    }

    @Test
    fun playRequestedWhilePreparingStartsWhenReady() = runTest {
        val harness = Harness(this)
        harness.update { wubTrack() }

        harness.coordinator.sync()
        harness.coordinator.playWhenReady()
        advanceUntilIdle()

        assertEquals(listOf(true), harness.ready)
    }

    @Test
    fun doesNothingUntilTrackHasLoaded() = runTest {
        val harness = Harness(this)
        harness.update { wubTrack().copy(analysisLoaded = false) }

        harness.coordinator.sync()
        advanceUntilIdle()

        assertTrue(harness.renderer.renders.isEmpty())
        assertTrue(harness.blocked.isEmpty())
    }

    @Test
    fun leavingTheModeCancelsRenderInFlight() = runTest {
        val harness = Harness(this)
        harness.update { wubTrack().copy(isRunning = true) }

        harness.coordinator.sync()
        harness.update { harness.playback.copy(playMode = PlaybackMode.Jukebox) }
        harness.coordinator.sync()
        advanceUntilIdle()

        assertTrue(harness.renderer.renders.isEmpty())
        assertEquals(listOf(true, false), harness.blocked)
        assertNull(harness.playback.wubProgress)
        assertFalse(harness.playback.wubReady)
        assertTrue(harness.ready.isEmpty())
        assertEquals(0, harness.failures)
    }

    @Test
    fun castingCancelsRenderInFlight() = runTest {
        val harness = Harness(this)
        harness.update { wubTrack() }

        harness.coordinator.sync()
        harness.update { harness.playback.copy(isCasting = true) }
        harness.coordinator.sync()
        advanceUntilIdle()

        assertTrue(harness.renderer.renders.isEmpty())
        assertEquals(listOf(true, false), harness.blocked)
    }

    @Test
    fun closeCancelsRenderAndLiftsPlaybackBlock() = runTest {
        val harness = Harness(this)
        harness.update { wubTrack() }

        harness.coordinator.sync()
        harness.coordinator.close()
        advanceUntilIdle()

        assertTrue(harness.renderer.renders.isEmpty())
        assertEquals(listOf(true, false), harness.blocked)
        assertTrue(harness.ready.isEmpty())
        assertEquals(0, harness.failures)
    }

    @Test
    fun trackChangeRestartsRenderForNewTrack() = runTest {
        val harness = Harness(this)
        harness.update { wubTrack(jobId = "job-a") }

        harness.coordinator.sync()
        harness.update { wubTrack(jobId = "job-b") }
        harness.coordinator.sync()
        advanceUntilIdle()

        assertEquals(1, harness.renderer.renders.size)
        assertEquals(listOf(false), harness.ready)
        assertTrue(harness.playback.wubReady)
    }

    @Test
    fun cancelledRenderDoesNotReportProgressOverItsReplacement() = runTest {
        val harness = Harness(this)
        harness.update { wubTrack(jobId = "job-a") }
        harness.renderer.midRender = {
            // Track B takes over while A is still stretching a slice.
            harness.renderer.midRender = {}
            harness.update { wubTrack(jobId = "job-b") }
            harness.coordinator.sync()
            assertEquals(0, harness.playback.wubProgress)
        }

        harness.coordinator.sync()
        advanceUntilIdle()

        assertEquals(2, harness.renderer.renders.size)
        assertTrue(harness.playback.wubReady)
        // A's report of its second slice lands after B started and is dropped.
        assertEquals(listOf(0, 50, null, 0, 50, 100, null), harness.progressHistory)
    }

    @Test
    fun repeatedSyncDoesNotRestartSameRender() = runTest {
        val harness = Harness(this)
        harness.update { wubTrack() }

        harness.coordinator.sync()
        harness.coordinator.sync()
        advanceUntilIdle()

        assertEquals(1, harness.renderer.renders.size)
    }

    @Test
    fun failedRenderMarksTrackAndStopsWaiting() = runTest {
        val harness = Harness(this)
        harness.renderer.result = false
        harness.update { wubTrack() }

        harness.coordinator.sync()
        advanceUntilIdle()

        assertEquals(1, harness.failures)
        assertTrue(harness.ready.isEmpty())
        assertFalse(harness.playback.wubReady)
        assertTrue(harness.playback.wubRenderFailed)
        assertNull(harness.playback.wubProgress)
        assertFalse(harness.playback.isPreparingWubMachine())
        assertEquals(listOf(true, false), harness.blocked)

        // The failure is sticky: syncing again does not loop on the broken render.
        harness.coordinator.sync()
        advanceUntilIdle()
        assertEquals(1, harness.renderer.renders.size)
    }

    @Test
    fun retryClearsFailureAndRendersAgain() = runTest {
        val harness = Harness(this)
        harness.renderer.result = false
        harness.update { wubTrack() }
        harness.coordinator.sync()
        advanceUntilIdle()

        harness.renderer.result = true
        harness.coordinator.retry()
        harness.coordinator.playWhenReady()
        advanceUntilIdle()

        assertEquals(2, harness.renderer.renders.size)
        assertTrue(harness.playback.wubReady)
        assertFalse(harness.playback.wubRenderFailed)
        assertEquals(listOf(true), harness.ready)
    }

    @Test
    fun missingAnalysisFailsTheRender() = runTest {
        val harness = Harness(this)
        harness.analysis = null
        harness.update { wubTrack() }

        harness.coordinator.sync()
        advanceUntilIdle()

        assertTrue(harness.renderer.renders.isEmpty())
        assertEquals(1, harness.failures)
        assertTrue(harness.playback.wubRenderFailed)
        assertEquals(listOf(true, false), harness.blocked)
    }

    @Test
    fun adoptsRemixThePlayerAlreadyHolds() = runTest {
        val harness = Harness(this)
        harness.renderer.installed = true
        harness.update { wubTrack() }

        harness.coordinator.sync()
        advanceUntilIdle()

        assertTrue(harness.renderer.renders.isEmpty())
        assertTrue(harness.playback.wubReady)
        assertEquals(remix.parts, harness.playback.wubMachine.parts)
        assertEquals(listOf(false), harness.ready)
    }

    @Test
    fun revalidateRendersAgainWhenPlayerLostItsRemix() = runTest {
        val harness = Harness(this)
        harness.update { wubTrack().copy(wubReady = true) }

        harness.coordinator.revalidate()

        assertTrue(harness.playback.isPreparingWubMachine())

        advanceUntilIdle()

        assertEquals(1, harness.renderer.renders.size)
        assertTrue(harness.playback.wubReady)
    }
}
