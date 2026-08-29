package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TuningDialogThresholdTest {

    @Test
    fun leavesTheThresholdOnAutoUntilTheControlMoves() {
        assertNull(
            chosenThresholdOrAuto(
                threshold = 30,
                thresholdChosen = false,
                thresholdMoved = false,
                computedThreshold = 30
            )
        )
    }

    @Test
    fun pinsAThresholdTheUserChose() {
        assertEquals(
            45,
            chosenThresholdOrAuto(
                threshold = 45,
                thresholdChosen = true,
                thresholdMoved = true,
                computedThreshold = 30
            )
        )
    }

    @Test
    fun returnsToAutoWhenTheControlIsMovedBackToTheAutoValue() {
        // A track whose auto threshold is 30 sounds identical whether 30 is pinned or auto, and
        // the slider has no other way to express auto, so moving it there restores auto.
        assertNull(
            chosenThresholdOrAuto(
                threshold = 30,
                thresholdChosen = true,
                thresholdMoved = true,
                computedThreshold = 30
            )
        )
    }

    @Test
    fun keepsAPinnedThresholdThatHappensToMatchAutoWhenTheControlIsUntouched() {
        // Applying an unrelated change — an audio mode, a branch filter — must leave a stored
        // threshold exactly as the dialog found it, even where it equals the auto value.
        assertEquals(
            30,
            chosenThresholdOrAuto(
                threshold = 30,
                thresholdChosen = true,
                thresholdMoved = false,
                computedThreshold = 30
            )
        )
    }

    @Test
    fun pinsTheChosenThresholdWhenNoAutoValueIsKnown() {
        assertEquals(
            30,
            chosenThresholdOrAuto(
                threshold = 30,
                thresholdChosen = true,
                thresholdMoved = true,
                computedThreshold = null
            )
        )
    }
}
