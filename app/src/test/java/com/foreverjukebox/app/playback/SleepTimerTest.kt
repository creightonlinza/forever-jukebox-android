package com.foreverjukebox.app.playback

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {

    private fun TestScope.countdown(onExpired: () -> Unit = {}) = SleepTimerCountdown(
        scope = backgroundScope,
        nowMs = { testScheduler.currentTime },
        onExpired = onExpired
    )

    @Test
    fun startPublishesConfiguredDurationAndCountsDown() = runTest {
        val countdown = countdown()

        countdown.start(durationMs = 5_000L)
        runCurrent()
        assertEquals(5_000L, countdown.state.value.configuredDurationMs)
        assertEquals(5_000L, countdown.state.value.remainingMs)
        assertTrue(countdown.state.value.isActive)

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(3_000L, countdown.state.value.remainingMs)
    }

    @Test
    fun expiryClearsTheStatusAndRunsTheExpiryActionOnce() = runTest {
        var expirations = 0
        val countdown = countdown { expirations += 1 }

        countdown.start(durationMs = 3_000L)
        advanceTimeBy(3_001L)
        runCurrent()

        assertEquals(1, expirations)
        assertFalse(countdown.state.value.isActive)
        assertEquals(null, countdown.state.value.configuredDurationMs)
    }

    @Test
    fun clearCancelsWithoutExpiring() = runTest {
        var expirations = 0
        val countdown = countdown { expirations += 1 }

        countdown.start(durationMs = 3_000L)
        advanceTimeBy(1_000L)
        countdown.clear()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(0, expirations)
        assertEquals(SleepTimerStatus(), countdown.state.value)
    }

    @Test
    fun restartReplacesTheRunningCountdown() = runTest {
        var expirations = 0
        val countdown = countdown { expirations += 1 }

        countdown.start(durationMs = 2_000L)
        advanceTimeBy(1_000L)
        countdown.start(durationMs = 5_000L)
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(0, expirations)
        assertEquals(5_000L, countdown.state.value.configuredDurationMs)
        assertEquals(3_000L, countdown.state.value.remainingMs)

        advanceTimeBy(3_001L)
        runCurrent()
        assertEquals(1, expirations)
    }

    @Test
    fun expiryBroadcastActionsIncludeFullscreenCloseAndExpiry() {
        val actions = sleepTimerExpiryBroadcastActions()

        assertTrue(actions.contains(PlaybackBroadcasts.ACTION_SLEEP_TIMER_EXPIRED))
        assertTrue(actions.contains(PlaybackBroadcasts.ACTION_CLOSE_FULLSCREEN))
        assertEquals(2, actions.size)
    }

    @Test
    fun sleepTimerStatusIsActiveOnlyWhenRemainingAndEndRealtimeArePresent() {
        assertTrue(SleepTimerStatus(endRealtimeMs = 1234L, remainingMs = 1L).isActive)
        assertFalse(SleepTimerStatus(endRealtimeMs = null, remainingMs = 1L).isActive)
        assertFalse(SleepTimerStatus(endRealtimeMs = 1234L, remainingMs = 0L).isActive)
    }
}
