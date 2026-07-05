package com.foreverjukebox.app.ui

import okhttp3.RequestBody

/**
 * Resolved inputs for a single Local-mode Cast upload: the streaming audio body, the analysis JSON
 * body, and the queried audio size (null when the content provider doesn't report one). Built by the
 * Android layer ([MainViewModel]) from a content URI + cacheKey and consumed by [CastPlaybackCoordinator].
 */
class CastLocalUploadSource(
    val sizeBytes: Long?,
    val audioBody: RequestBody,
    val analysisBody: RequestBody
)

/**
 * Thrown by the upload-source factory when the local audio can no longer be read at cast time — the
 * persisted content-URI permission lapsed (`SecurityException`), the file moved/was deleted
 * (`FileNotFoundException`), or the cached analysis JSON is missing. Surfaced as the "re-pick the
 * file" error rather than failing silently (PLAN §6.6 / §6.9 #3).
 */
class CastSourceUnavailableException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Builds a [CastLocalUploadSource] for (sourceUri, cacheKey), or throws
 * [CastSourceUnavailableException] if the source or its cached analysis can't be accessed.
 */
fun interface CastLocalUploadSourceFactory {
    fun build(sourceUri: String, cacheKey: String): CastLocalUploadSource
}
