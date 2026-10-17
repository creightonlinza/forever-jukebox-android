package com.foreverjukebox.app.ui

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json

/**
 * Reads a cached analysis. A file that no longer decodes (a write cut short by process death, or
 * a format the app no longer reads) is deleted and reported through [onDiscard], so the caller
 * falls back to the server instead of failing on the same file every time the track is opened.
 */
internal fun readCachedAnalysis(
    file: File,
    json: Json,
    onDiscard: (Throwable) -> Unit = {}
): TrackAnalysisResult? {
    return try {
        json.decodeFromString<TrackAnalysisResult>(file.readText())
    } catch (error: IOException) {
        onDiscard(error)
        file.delete()
        null
    } catch (error: IllegalArgumentException) {
        // kotlinx.serialization's SerializationException extends IllegalArgumentException.
        onDiscard(error)
        file.delete()
        null
    }
}

/**
 * Writes [text] to a temporary sibling and renames it over [target], so a process killed
 * mid-write never leaves a truncated file under the final name.
 */
internal fun writeTextAtomically(target: File, text: String) {
    val temp = File(target.parentFile, "${target.name}.tmp")
    temp.writeText(text)
    if (!temp.renameTo(target)) {
        temp.delete()
        throw IOException("Could not move ${temp.name} into place")
    }
}
