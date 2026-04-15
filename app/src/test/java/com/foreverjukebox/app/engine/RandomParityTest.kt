package com.foreverjukebox.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RandomParityTest {

    @Test
    fun seededModeMatchesExpectedWebSequenceForSeed1337() {
        val rng = createRng(RandomMode.Seeded, seed = 1337)
        val expected = listOf(
            0.184411832597,
            0.189989251317,
            0.810471992241,
            0.643748822156,
            0.430774615612
        )
        for (value in expected) {
            assertEquals(value, rng(), 1e-12)
        }
    }

    @Test
    fun deterministicModeMatchesExpectedWebSequenceForSeed1337() {
        val rng = createRng(RandomMode.Deterministic, seed = 1337)
        val expected = listOf(
            0.184411832597,
            0.189989251317,
            0.810471992241,
            0.643748822156,
            0.430774615612
        )
        for (value in expected) {
            assertEquals(value, rng(), 1e-12)
        }
    }
}
