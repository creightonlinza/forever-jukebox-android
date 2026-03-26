package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerOptionMappingTest {

    @Test
    fun mapsKnownDurationsToExpectedOptions() {
        assertEquals(
            SleepTimerOption.Minutes15,
            sleepTimerOptionForDurationMs(15L * 60L * 1000L)
        )
        assertEquals(
            SleepTimerOption.Minutes30,
            sleepTimerOptionForDurationMs(30L * 60L * 1000L)
        )
        assertEquals(
            SleepTimerOption.Minutes45,
            sleepTimerOptionForDurationMs(45L * 60L * 1000L)
        )
        assertEquals(
            SleepTimerOption.Hour1,
            sleepTimerOptionForDurationMs(60L * 60L * 1000L)
        )
        assertEquals(
            SleepTimerOption.Hours2,
            sleepTimerOptionForDurationMs(2L * 60L * 60L * 1000L)
        )
    }

    @Test
    fun mapsUnknownOrNonPositiveDurationToOff() {
        assertEquals(SleepTimerOption.Off, sleepTimerOptionForDurationMs(null))
        assertEquals(SleepTimerOption.Off, sleepTimerOptionForDurationMs(0L))
        assertEquals(SleepTimerOption.Off, sleepTimerOptionForDurationMs(-1L))
        assertEquals(SleepTimerOption.Off, sleepTimerOptionForDurationMs(12345L))
    }
}
