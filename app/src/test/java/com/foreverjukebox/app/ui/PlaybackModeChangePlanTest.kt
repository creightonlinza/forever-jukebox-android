package com.foreverjukebox.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModeChangePlanTest {

    @Test
    fun runningPlanStopsAllTransports() {
        val plan = resolveModeTransportPlan(
            previousMode = PlaybackMode.Autocanonizer,
            targetMode = PlaybackMode.Jukebox,
            isRunning = true
        )

        assertTrue(plan.stopAllTransports)
        assertFalse(plan.stopAutocanonizerWhileIdle)
        assertTrue(plan.invokeOnStopped)
        assertTrue(plan.clearAutocanonizerAudio)
    }

    @Test
    fun idleAutocanonizerPlanStopsOnlyAutocanonizerTransport() {
        val plan = resolveModeTransportPlan(
            previousMode = PlaybackMode.Autocanonizer,
            targetMode = PlaybackMode.Jukebox,
            isRunning = false
        )

        assertFalse(plan.stopAllTransports)
        assertTrue(plan.stopAutocanonizerWhileIdle)
        assertFalse(plan.invokeOnStopped)
        assertTrue(plan.clearAutocanonizerAudio)
    }

    @Test
    fun idleJukeboxPlanDoesNothing() {
        val plan = resolveModeTransportPlan(
            previousMode = PlaybackMode.Jukebox,
            targetMode = PlaybackMode.Autocanonizer,
            isRunning = false
        )

        assertFalse(plan.stopAllTransports)
        assertFalse(plan.stopAutocanonizerWhileIdle)
        assertFalse(plan.invokeOnStopped)
        assertFalse(plan.clearAutocanonizerAudio)
    }

    @Test
    fun jukeboxToAutocanonizerDoesNotClearAutocanonizerAudio() {
        val plan = resolveModeTransportPlan(
            previousMode = PlaybackMode.Jukebox,
            targetMode = PlaybackMode.Autocanonizer,
            isRunning = true
        )

        assertFalse(plan.clearAutocanonizerAudio)
    }
}
