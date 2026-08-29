package com.foreverjukebox.app.ui

import com.foreverjukebox.app.engine.JukeboxConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The drift marker compares a tuning string built from [TuningState] against one built from the
 * engine, so the two have to describe the same config identically. Percent conversion is where
 * they can silently disagree: a value the dialog sends as 29% lands as a double just under 0.29,
 * which truncates to 28 and rounds to 29.
 */
class TuningStateProducerParityTest {

    @Test
    fun convertsEveryBranchProbabilityPercentTheSameWayAsTheDialogSendsIt() {
        for (percent in 0..100) {
            val config = JukeboxConfig(
                minRandomBranchChance = percent.toFloat() / 100.0,
                maxRandomBranchChance = percent.toFloat() / 100.0
            )
            val tuning = engineTuningState(config = config, computedThreshold = 30)

            assertEquals("minProb for $percent%", percent, tuning.minProb)
            assertEquals("maxProb for $percent%", percent, tuning.maxProb)
        }
    }

    @Test
    fun convertsEveryRampPercentTheSameWayAsTheDialogSendsIt() {
        for (percent in 0..100) {
            val config = JukeboxConfig(randomBranchChanceDelta = percent.toFloat() / 500.0)
            val tuning = engineTuningState(config = config, computedThreshold = 30)

            assertEquals("ramp for $percent%", percent, tuning.ramp)
        }
    }
}
