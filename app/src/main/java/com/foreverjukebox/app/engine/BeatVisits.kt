package com.foreverjukebox.app.engine

/**
 * Per-beat play counts. The tick loop records on a background dispatcher while seeks and
 * UI reads arrive on the main thread, so readers only ever see immutable snapshots.
 */
internal class BeatVisitCounter {
    private var counts = IntArray(0)
    private var published: List<Int>? = emptyList()

    @Synchronized
    fun reset(size: Int) {
        counts = IntArray(size)
        published = null
    }

    @Synchronized
    fun record(index: Int) {
        if (index !in counts.indices) return
        counts[index] += 1
        published = null
    }

    @Synchronized
    fun snapshot(): List<Int> {
        return published ?: counts.toList().also { published = it }
    }
}
