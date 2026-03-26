package com.foreverjukebox.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerServiceBehaviorTest {

    @Test
    fun stopCommandClearsNotificationWhenTimerIsActive() {
        val command = resolveForegroundServiceStopCommand(isSleepTimerActive = true)

        assertEquals(ForegroundServiceStopCommand.ClearNotificationKeepTimer, command)
    }

    @Test
    fun stopCommandStopsServiceWhenTimerIsInactive() {
        val command = resolveForegroundServiceStopCommand(isSleepTimerActive = false)

        assertEquals(ForegroundServiceStopCommand.StopService, command)
    }

    @Test
    fun expiryBroadcastActionsIncludeFullscreenCloseAndExpiry() {
        val actions = sleepTimerExpiryBroadcastActions()

        assertTrue(actions.contains(ForegroundPlaybackService.ACTION_SLEEP_TIMER_EXPIRED))
        assertTrue(actions.contains(ForegroundPlaybackService.ACTION_CLOSE_FULLSCREEN))
        assertEquals(2, actions.size)
    }
}

