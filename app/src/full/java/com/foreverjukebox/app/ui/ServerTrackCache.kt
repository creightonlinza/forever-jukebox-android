package com.foreverjukebox.app.ui

import java.io.File

internal fun serverTrackAnalysisFile(cacheDir: File, jobId: String): File =
    File(cacheDir, "$jobId.analysis.json")

internal fun serverTrackAudioFile(cacheDir: File, jobId: String): File =
    File(cacheDir, "$jobId.audio")

internal fun hasCompleteServerTrackCache(cacheDir: File, jobId: String): Boolean {
    return serverTrackAnalysisFile(cacheDir, jobId).exists() &&
        serverTrackAudioFile(cacheDir, jobId).exists()
}
