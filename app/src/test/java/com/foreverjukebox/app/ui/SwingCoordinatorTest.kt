package com.foreverjukebox.app.ui

import com.foreverjukebox.app.audio.SwingBeat
import com.foreverjukebox.app.engine.QuantumBase
import com.foreverjukebox.app.engine.VisualizationData
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
class SwingCoordinatorTest {

    private class FakeRenderer : SwingRenderer {
        var installed = false
        var result = true
        val renders = mutableListOf<List<SwingBeat>>()

        override fun hasSwingAudio(): Boolean = installed

        // Runs between the two progress reports, standing in for state changes
        // that land while a half-beat is being stretched.
        var midRender: () -> Unit = {}

        override fun renderSwing(
            beats: List<SwingBeat>,
            onProgress: (completed: Int, total: Int) -> Boolean
        ): Boolean {
            renders += beats
            val keepGoing = onProgress(1, 2)
            midRender()
            if (!keepGoing || !onProgress(2, 2)) return false
            installed = result
            return result
        }
    }

    private object PassThroughHold : AudioLoadHold {
        override suspend fun <T> hold(block: suspend () -> T): T = block()
    }

    private class Harness(scope: TestScope) {
        val renderer = FakeRenderer()
        var playback = PlaybackState()
            private set
        val progressHistory = mutableListOf<Int?>()
        val blocked = mutableListOf<Boolean>()
        val ready = mutableListOf<Boolean>()
        var failures = 0
        var pauses = 0
        val coordinator = SwingCoordinator(
            scope = scope,
            renderer = renderer,
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
            if (next.swingProgress != playback.swingProgress) {
                progressHistory += next.swingProgress
            }
            playback = next
        }
    }

    private fun swingTrack(jobId: String = "job-a", beatCount: Int = 2): PlaybackState {
        val beats = List(beatCount) { index ->
            QuantumBase(start = index * 0.5, duration = 0.5, confidence = null, which = index)
        }
        return PlaybackState(
            jukeboxAudioMode = JukeboxAudioMode.Swing,
            audioLoaded = true,
            analysisLoaded = true,
            lastJobId = jobId,
            vizData = VisualizationData(beats = beats, edges = mutableListOf())
        )
    }

    @Test
    fun rendersLoadedSwingTrackAndMarksItReady() = runTest {
        val harness = Harness(this)
        harness.update { swingTrack() }

        harness.coordinator.sync()

        assertEquals(listOf(true), harness.blocked)
        assertEquals(0, harness.playback.swingProgress)
        assertTrue(harness.playback.isPreparingSwing())

        advanceUntilIdle()

        assertEquals(
            listOf(listOf(SwingBeat(0.0, 0.5), SwingBeat(0.5, 0.5))),
            harness.renderer.renders
        )
        assertEquals(listOf(true, false), harness.blocked)
        assertTrue(harness.playback.swingReady)
        assertNull(harness.playback.swingProgress)
        assertFalse(harness.playback.isPreparingSwing())
        assertEquals(listOf(false), harness.ready)
        assertEquals(0, harness.failures)
    }

    @Test
    fun pausesRunningPlaybackAndResumesItWhenReady() = runTest {
        val harness = Harness(this)
        harness.update { swingTrack().copy(isRunning = true) }

        harness.coordinator.sync()

        assertEquals(1, harness.pauses)
        assertFalse(harness.playback.isRunning)

        advanceUntilIdle()

        assertEquals(listOf(true), harness.ready)
    }

    @Test
    fun playRequestedWhilePreparingStartsWhenReady() = runTest {
        val harness = Harness(this)
        harness.update { swingTrack() }

        harness.coordinator.sync()
        harness.coordinator.playWhenReady()
        advanceUntilIdle()

        assertEquals(listOf(true), harness.ready)
    }

    @Test
    fun doesNothingUntilTrackHasLoaded() = runTest {
        val harness = Harness(this)
        harness.update { swingTrack().copy(audioLoaded = false) }

        harness.coordinator.sync()
        advanceUntilIdle()

        assertTrue(harness.renderer.renders.isEmpty())
        assertTrue(harness.blocked.isEmpty())
    }

    @Test
    fun leavingSwingCancelsRenderInFlight() = runTest {
        val harness = Harness(this)
        harness.update { swingTrack().copy(isRunning = true) }

        harness.coordinator.sync()
        harness.update { harness.playback.copy(jukeboxAudioMode = JukeboxAudioMode.Off) }
        harness.coordinator.sync()
        advanceUntilIdle()

        assertTrue(harness.renderer.renders.isEmpty())
        assertEquals(listOf(true, false), harness.blocked)
        assertNull(harness.playback.swingProgress)
        assertFalse(harness.playback.swingReady)
        assertTrue(harness.ready.isEmpty())
        assertEquals(0, harness.failures)
    }

    @Test
    fun closeCancelsRenderAndLiftsPlaybackBlock() = runTest {
        val harness = Harness(this)
        harness.update { swingTrack() }

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
        harness.update { swingTrack(jobId = "job-a", beatCount = 2) }

        harness.coordinator.sync()
        harness.update { swingTrack(jobId = "job-b", beatCount = 3) }
        harness.coordinator.sync()
        advanceUntilIdle()

        assertEquals(listOf(3), harness.renderer.renders.map { it.size })
        assertEquals(listOf(false), harness.ready)
        assertTrue(harness.playback.swingReady)
    }

    @Test
    fun cancelledRenderDoesNotReportProgressOverItsReplacement() = runTest {
        val harness = Harness(this)
        harness.update { swingTrack(jobId = "job-a") }
        harness.renderer.midRender = {
            // Track B takes over while A is still stretching a half-beat.
            harness.renderer.midRender = {}
            harness.update { swingTrack(jobId = "job-b") }
            harness.coordinator.sync()
            assertEquals(0, harness.playback.swingProgress)
        }

        harness.coordinator.sync()
        advanceUntilIdle()

        assertEquals(2, harness.renderer.renders.size)
        assertTrue(harness.playback.swingReady)
        // A's report of its second half-beat lands after B started and is dropped.
        assertEquals(listOf(0, 50, null, 0, 50, 100, null), harness.progressHistory)
    }

    @Test
    fun repeatedSyncDoesNotRestartSameRender() = runTest {
        val harness = Harness(this)
        harness.update { swingTrack() }

        harness.coordinator.sync()
        harness.coordinator.sync()
        advanceUntilIdle()

        assertEquals(1, harness.renderer.renders.size)
    }

    @Test
    fun failedRenderReportsFailureAndLeavesTrackUnswung() = runTest {
        val harness = Harness(this)
        harness.renderer.result = false
        harness.update { swingTrack() }

        harness.coordinator.sync()
        advanceUntilIdle()

        assertEquals(1, harness.failures)
        assertTrue(harness.ready.isEmpty())
        assertFalse(harness.playback.swingReady)
        assertNull(harness.playback.swingProgress)
        assertEquals(listOf(true, false), harness.blocked)
    }

    @Test
    fun adoptsSwungCopyThePlayerAlreadyHolds() = runTest {
        val harness = Harness(this)
        harness.renderer.installed = true
        harness.update { swingTrack() }

        harness.coordinator.sync()
        advanceUntilIdle()

        assertTrue(harness.renderer.renders.isEmpty())
        assertTrue(harness.playback.swingReady)
        assertEquals(listOf(false), harness.ready)
    }

    @Test
    fun revalidateRendersAgainWhenPlayerLostItsSwungCopy() = runTest {
        val harness = Harness(this)
        harness.update { swingTrack().copy(swingReady = true) }

        harness.coordinator.revalidate()

        assertTrue(harness.playback.isPreparingSwing())

        advanceUntilIdle()

        assertEquals(1, harness.renderer.renders.size)
        assertTrue(harness.playback.swingReady)
    }
}
