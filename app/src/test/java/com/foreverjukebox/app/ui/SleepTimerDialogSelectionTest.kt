package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerDialogSelectionTest {

    @Test
    fun selectOptionUpdatesPendingOnlyUntilSetPressed() {
        val initial = SleepTimerDialogSelectionState(
            appliedOption = SleepTimerOption.Minutes15,
            pendingOption = SleepTimerOption.Minutes15
        )

        val afterSelect = reduceSleepTimerDialogSelection(
            state = initial,
            action = SleepTimerDialogAction.SelectOption(SleepTimerOption.Hour1)
        )

        assertEquals(SleepTimerOption.Minutes15, afterSelect.appliedOption)
        assertEquals(SleepTimerOption.Hour1, afterSelect.pendingOption)

        val afterSet = reduceSleepTimerDialogSelection(
            state = afterSelect,
            action = SleepTimerDialogAction.Set
        )

        assertEquals(SleepTimerOption.Hour1, afterSet.appliedOption)
        assertEquals(SleepTimerOption.Hour1, afterSet.pendingOption)
    }

    @Test
    fun setWithoutChangingSelectionKeepsCurrentOption() {
        val initial = SleepTimerDialogSelectionState(
            appliedOption = SleepTimerOption.Minutes30,
            pendingOption = SleepTimerOption.Minutes30
        )

        val afterSet = reduceSleepTimerDialogSelection(
            state = initial,
            action = SleepTimerDialogAction.Set
        )

        assertEquals(SleepTimerOption.Minutes30, afterSet.appliedOption)
        assertEquals(SleepTimerOption.Minutes30, afterSet.pendingOption)
    }
}

