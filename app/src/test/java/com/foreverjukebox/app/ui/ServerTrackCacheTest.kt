package com.foreverjukebox.app.ui

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerTrackCacheTest {

    @Test
    fun completeServerTrackCacheRequiresMatchingJobAudioAndAnalysisFiles() {
        val cacheDir = Files.createTempDirectory("fj-server-track-cache").toFile()
        val jobId = "job_123"

        assertFalse(hasCompleteServerTrackCache(cacheDir, jobId))

        serverTrackAudioFile(cacheDir, jobId).writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(hasCompleteServerTrackCache(cacheDir, jobId))

        serverTrackAnalysisFile(cacheDir, jobId).writeText("{}")
        assertTrue(hasCompleteServerTrackCache(cacheDir, jobId))
        assertFalse(hasCompleteServerTrackCache(cacheDir, "dQw4w9WgXcQ"))
    }
}
