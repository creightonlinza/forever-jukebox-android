package com.foreverjukebox.app.ui

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the hold contract coordinators rely on: every hold is balanced by a
 * release even when the block throws, and holds nest without interfering.
 */
class AudioLoadHoldTest {

    private class CountingHold : AudioLoadHold {
        var acquires = 0
        var releases = 0
        val active get() = acquires - releases

        override suspend fun <T> hold(block: suspend () -> T): T {
            acquires += 1
            try {
                return block()
            } finally {
                releases += 1
            }
        }
    }

    @Test
    fun `hold releases after block completes`() = runTest {
        val hold = CountingHold()
        val result = hold.hold { "loaded" }
        assertEquals("loaded", result)
        assertEquals(1, hold.acquires)
        assertEquals(0, hold.active)
    }

    @Test
    fun `hold releases when block throws`() = runTest {
        val hold = CountingHold()
        var thrown = false
        try {
            hold.hold<Unit> { throw IOException("network gone") }
        } catch (_: IOException) {
            thrown = true
        }
        assertTrue(thrown)
        assertEquals(1, hold.acquires)
        assertEquals(0, hold.active)
    }

    @Test
    fun `nested holds stay balanced and keep outer hold active`() = runTest {
        val hold = CountingHold()
        hold.hold {
            assertEquals(1, hold.active)
            hold.hold {
                assertEquals(2, hold.active)
            }
            assertEquals(1, hold.active)
        }
        assertEquals(2, hold.acquires)
        assertEquals(0, hold.active)
    }
}
