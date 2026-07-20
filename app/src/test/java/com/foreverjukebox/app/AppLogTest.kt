package com.foreverjukebox.app

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogTest {

    @Test
    fun formatErrorChainRendersSingleErrorAsToString() {
        val error = IOException("Failed to decode audio for job1")

        assertEquals(
            "java.io.IOException: Failed to decode audio for job1",
            AppLog.formatErrorChain(error)
        )
    }

    @Test
    fun formatErrorChainRendersCausesInOrder() {
        val root = IllegalStateException("codec error")
        val wrapper = IOException("Failed to decode audio for job1", root)

        assertEquals(
            "java.io.IOException: Failed to decode audio for job1" +
                " <- java.lang.IllegalStateException: codec error",
            AppLog.formatErrorChain(wrapper)
        )
    }

    @Test
    fun formatErrorChainTruncatesBeyondDepthCap() {
        var current = RuntimeException("depth7")
        for (depth in 6 downTo 1) {
            current = RuntimeException("depth$depth", current)
        }

        val formatted = AppLog.formatErrorChain(current)

        assertEquals(
            "java.lang.RuntimeException: depth1" +
                " <- java.lang.RuntimeException: depth2" +
                " <- java.lang.RuntimeException: depth3" +
                " <- java.lang.RuntimeException: depth4" +
                " <- java.lang.RuntimeException: depth5" +
                " <- ...",
            formatted
        )
    }

    @Test
    fun formatErrorChainStopsOnCauseCycle() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)

        assertEquals(
            "java.lang.RuntimeException: second <- java.lang.RuntimeException: first",
            AppLog.formatErrorChain(second)
        )
    }
}
