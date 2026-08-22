package com.foreverjukebox.app.audio

import com.foreverjukebox.app.engine.QuantumBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCowbellOverlayControllerTest {

    @Test
    fun enabledControllerSchedulesDownbeatAtBeatStart() {
        val scheduler = RecordingCowbellHitScheduler()
        val controller = NativeCowbellOverlayController(scheduler, CowbellOverlayPlanner { 0.5 })

        controller.setEnabled(true)
        controller.handleBeatEnter(beatIndex = 4, beat = beat(12.5), nextBeat = beat(13.0), playbackRate = 1.0)

        assertTrue(scheduler.preloadCalls >= 1)
        assertEquals(listOf(12.5), scheduler.hits.map { it.targetTimeSeconds })
    }

    @Test
    fun disabledControllerSchedulesNothingButStillClearsPendingHits() {
        val scheduler = RecordingCowbellHitScheduler()
        val controller = NativeCowbellOverlayController(scheduler, CowbellOverlayPlanner { 0.5 })

        controller.handleBeatEnter(beatIndex = 0, beat = beat(0.0), nextBeat = beat(0.5), playbackRate = 1.0)

        assertTrue(scheduler.hits.isEmpty())
        assertEquals(1, scheduler.cancelPendingCalls)
    }

    @Test
    fun disablingCancelsScheduledHits() {
        val scheduler = RecordingCowbellHitScheduler()
        val controller = NativeCowbellOverlayController(scheduler, CowbellOverlayPlanner { 0.5 })

        controller.setEnabled(true)
        controller.setEnabled(false)
        controller.handleBeatEnter(beatIndex = 0, beat = beat(0.0), nextBeat = beat(0.5), playbackRate = 1.0)

        assertEquals(1, scheduler.cancelAllCalls)
        assertTrue(scheduler.hits.isEmpty())
    }

    @Test
    fun releaseIsNotTerminal() {
        // The controller is owned by a process-wide singleton that outlives ViewModels, so a
        // release followed by a later enable must schedule hits again.
        val scheduler = RecordingCowbellHitScheduler()
        val controller = NativeCowbellOverlayController(scheduler, CowbellOverlayPlanner { 0.5 })

        controller.setEnabled(true)
        controller.release()
        controller.handleBeatEnter(beatIndex = 0, beat = beat(0.0), nextBeat = beat(0.5), playbackRate = 1.0)
        assertTrue(scheduler.hits.isEmpty())

        controller.setEnabled(true)
        controller.handleBeatEnter(beatIndex = 1, beat = beat(0.5), nextBeat = beat(1.0), playbackRate = 1.0)

        assertEquals(listOf(0.5), scheduler.hits.map { it.targetTimeSeconds })
    }

    @Test
    fun duckedVolumeScalesHitGain() {
        val scheduler = RecordingCowbellHitScheduler()
        val controller = NativeCowbellOverlayController(scheduler, CowbellOverlayPlanner { 0.5 })
        controller.setEnabled(true)

        controller.handleBeatEnter(beatIndex = 0, beat = beat(0.0), nextBeat = beat(0.5), playbackRate = 1.0)
        val full = scheduler.hits.single()
        scheduler.hits.clear()

        controller.setVolume(0.2)
        controller.handleBeatEnter(beatIndex = 1, beat = beat(0.5), nextBeat = beat(1.0), playbackRate = 1.0)
        val ducked = scheduler.hits.single()

        assertEquals(full.leftVolume * 0.2f, ducked.leftVolume, 1e-5f)
        assertEquals(full.rightVolume * 0.2f, ducked.rightVolume, 1e-5f)
        assertFalse(ducked.leftVolume == 0f)
    }

    private fun beat(start: Double): QuantumBase {
        return QuantumBase(start = start, duration = 0.5, confidence = 1.0, which = (start * 2).toInt())
    }

    private class RecordingCowbellHitScheduler : CowbellHitScheduler {
        val hits = mutableListOf<Hit>()
        var preloadCalls = 0
        var cancelPendingCalls = 0
        var cancelAllCalls = 0

        override fun preloadCowbellSamples() {
            preloadCalls += 1
        }

        override fun scheduleCowbellHit(
            sampleName: String,
            targetTimeSeconds: Double,
            leftVolume: Float,
            rightVolume: Float
        ) {
            hits += Hit(sampleName, targetTimeSeconds, leftVolume, rightVolume)
        }

        override fun cancelPendingCowbellHits() {
            cancelPendingCalls += 1
        }

        override fun cancelCowbellHits() {
            cancelAllCalls += 1
        }
    }

    private data class Hit(
        val sampleName: String,
        val targetTimeSeconds: Double,
        val leftVolume: Float,
        val rightVolume: Float
    )
}
