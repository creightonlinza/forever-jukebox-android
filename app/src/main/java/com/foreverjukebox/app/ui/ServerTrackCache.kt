package com.foreverjukebox.app.ui

import java.io.File

internal fun serverTrackAnalysisFile(cacheDir: File, trackKey: String): File =
    File(cacheDir, "$trackKey.analysis.json")

internal fun serverTrackAudioFile(cacheDir: File, trackKey: String): File =
    File(cacheDir, "$trackKey.audio")

internal fun hasCompleteServerTrackCache(cacheDir: File, trackKey: String): Boolean {
    return serverTrackAnalysisFile(cacheDir, trackKey).exists() &&
        serverTrackAudioFile(cacheDir, trackKey).exists()
}
