package com.foreverjukebox.app.ui

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrackCacheFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private val result = TrackAnalysisResult(
        id = "0f41364f0e234cea98c8409385b410d3",
        status = "complete",
        createdAt = "2026-10-14T21:47:00Z"
    )

    @Test
    fun readsAnIntactCachedAnalysis() {
        val file = folder.newFile("job.analysis.json")
        writeTextAtomically(file, json.encodeToString(TrackAnalysisResult.serializer(), result))

        assertEquals(result, readCachedAnalysis(file, json))
        assertTrue(file.exists())
    }

    @Test
    fun aTruncatedCachedAnalysisIsDiscardedInsteadOfThrowing() {
        // A write cut short by process death leaves the start of the JSON only. Throwing here
        // escaped the load coroutine and crashed the app on every open of the track.
        val full = json.encodeToString(TrackAnalysisResult.serializer(), result)
        val file = folder.newFile("job.analysis.json").apply { writeText(full.take(full.length / 2)) }
        var discarded: Throwable? = null

        assertNull(readCachedAnalysis(file, json) { discarded = it })

        assertNotNull(discarded)
        assertFalse(file.exists())
    }

    @Test
    fun anEmptyCachedAnalysisIsDiscarded() {
        val file = folder.newFile("job.analysis.json")

        assertNull(readCachedAnalysis(file, json))

        assertFalse(file.exists())
    }

    @Test
    fun aMissingCachedAnalysisReadsAsNothing() {
        val file = File(folder.root, "missing.analysis.json")
        var discarded: Throwable? = null

        assertNull(readCachedAnalysis(file, json) { discarded = it })

        assertNotNull(discarded)
    }

    @Test
    fun atomicWriteReplacesTheTargetAndLeavesNoTemporaryFile() {
        val file = folder.newFile("job.analysis.json").apply { writeText("old") }

        writeTextAtomically(file, "new")

        assertEquals("new", file.readText())
        assertEquals(listOf(file.name), folder.root.list()!!.toList())
    }
}
