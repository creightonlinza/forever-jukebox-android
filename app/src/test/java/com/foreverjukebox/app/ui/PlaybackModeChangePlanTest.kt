package com.foreverjukebox.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModeChangePlanTest {

    @Test
    fun runningPlanStopsAllTransports() {
        val plan = resolveModeTransportPlan(
            previousMode = PlaybackMode.Autocanonizer,
            isRunning = true
        )

        assertTrue(plan.stopAllTransports)
        assertFalse(plan.stopAutocanonizerWhileIdle)
        assertTrue(plan.invokeOnStopped)
    }

    @Test
    fun idleAutocanonizerPlanStopsOnlyAutocanonizerTransport() {
        val plan = resolveModeTransportPlan(
            previousMode = PlaybackMode.Autocanonizer,
            isRunning = false
        )

        assertFalse(plan.stopAllTransports)
        assertTrue(plan.stopAutocanonizerWhileIdle)
        assertFalse(plan.invokeOnStopped)
    }

    @Test
    fun idleJukeboxPlanDoesNothing() {
        val plan = resolveModeTransportPlan(
            previousMode = PlaybackMode.Jukebox,
            isRunning = false
        )

        assertFalse(plan.stopAllTransports)
        assertFalse(plan.stopAutocanonizerWhileIdle)
        assertFalse(plan.invokeOnStopped)
    }
}
