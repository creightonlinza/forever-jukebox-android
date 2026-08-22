package com.foreverjukebox.app.cast

import com.foreverjukebox.app.net.CleartextGuardInterceptor
import com.foreverjukebox.app.net.streamingRequestBody
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Sender-side client for the Cast relay (`fj-android-cast`). The relay is sessionless: one track id
 * (16–64 lowercase hex) keys everything.
 *
 * Local mode uploads both files before the Cast LOAD (the receiver errors with
 * `cast_content_not_found` if the analysis isn't there when it looks):
 *
 * - `PUT /api/tracks/{id}/audio` (any audio content type; echoed to the receiver verbatim)
 * - `PUT /api/tracks/{id}/analysis` (bare local-analysis JSON) → `204 | 413 (too large) | 507 (full)`
 *
 * Server mode registers a pull and launches immediately — the relay fetches the analysis/audio from
 * the jukebox server itself, driven by the receiver's polling:
 *
 * - `POST /api/tracks/{jobId}/pull` `{"baseUrl": …}` → `204 | 403 (not allowlisted) | 507 | 400`
 *
 * The relay holds uploads in memory only and drops them once the receiver has loaded the track;
 * nothing is written to disk or retained (PRIVACY.md depends on this — keep them in sync).
 *
 * Re-sending either call for the same id is allowed (uploads overwrite, pulls re-register) — that is
 * the recovery path after a relay restart drops its in-memory state. The HTTP surface takes plain
 * [RequestBody] instances so it can be unit-tested against MockWebServer with in-memory bodies; the
 * Android caller wires content-URI streaming via [streamingBody].
 */
class CastRelayClient(
    private val client: OkHttpClient = defaultClient
) {
    /** Terminal outcome of a Local-mode upload attempt. */
    sealed interface UploadResult {
        /** Both files uploaded; the track is ready to LOAD by its id. */
        data object Ok : UploadResult

        /** Relay rejected a file as too large (413). Don't retry. */
        data object TooLarge : UploadResult

        /** Relay memory / track-count capacity guard tripped (507). Retry later. */
        data object Guard : UploadResult

        /** Network failure, malformed id, or unexpected status. */
        data object Unreachable : UploadResult
    }

    /** Terminal outcome of a Server-mode pull registration. */
    sealed interface PullResult {
        /** Registered; launch the cast immediately (the TV shows live job progress). */
        data object Ok : PullResult

        /** Server mode disabled or host not allowlisted (403). Config problem, not retryable. */
        data object Forbidden : PullResult

        /** Relay memory / track-count capacity guard tripped (507). Retry later. */
        data object Guard : PullResult

        /** Malformed id or pull body (400, or rejected locally). Sender bug. */
        data object BadRequest : PullResult

        /** Network failure or unexpected status. */
        data object Unreachable : PullResult
    }

    /**
     * Upload both files for a Local-mode Cast LOAD: audio first, then analysis. Both must succeed
     * before the LOAD is sent.
     */
    suspend fun uploadForCast(
        relayBaseUrl: String,
        trackId: String,
        audioBody: RequestBody,
        analysisBody: RequestBody
    ): UploadResult {
        if (!TRACK_ID_REGEX.matches(trackId)) return UploadResult.Unreachable
        val audio = put(trackUrl(relayBaseUrl, trackId, "audio"), audioBody)
        if (audio != PutStatus.Ok) return audio.toUploadResult()
        return put(trackUrl(relayBaseUrl, trackId, "analysis"), analysisBody).toUploadResult()
    }

    /**
     * Register a Server-mode pull: the relay derives `{serverBaseUrl}/api/analysis/{jobId}` and
     * `…/api/audio/{jobId}` and fetches them itself. Returns instantly — don't wait for the job.
     */
    suspend fun registerPull(
        relayBaseUrl: String,
        jobId: String,
        serverBaseUrl: String
    ): PullResult = withContext(Dispatchers.IO) {
        if (!TRACK_ID_REGEX.matches(jobId)) return@withContext PullResult.BadRequest
        val payload = buildJsonObject {
            put("baseUrl", serverBaseUrl.trim().trimEnd('/'))
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(trackUrl(relayBaseUrl, jobId, "pull")).post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> PullResult.Ok
                    response.code == 403 -> PullResult.Forbidden
                    response.code == 507 -> PullResult.Guard
                    response.code == 400 -> PullResult.BadRequest
                    else -> PullResult.Unreachable
                }
            }
        } catch (_: IOException) {
            PullResult.Unreachable
        }
    }

    private suspend fun put(url: String, body: RequestBody): PutStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).put(body).build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> PutStatus.Ok
                    response.code == 413 -> PutStatus.TooLarge
                    response.code == 507 -> PutStatus.Guard
                    else -> PutStatus.Failed
                }
            }
        } catch (_: IOException) {
            PutStatus.Failed
        }
    }

    private fun trackUrl(relayBaseUrl: String, trackId: String, leaf: String): String =
        baseHttpUrl(relayBaseUrl).newBuilder()
            .addPathSegment("api")
            .addPathSegment("tracks")
            .addPathSegment(trackId)
            .addPathSegment(leaf)
            .build()
            .toString()

    private fun baseHttpUrl(baseUrl: String): HttpUrl =
        baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid relay base URL: $baseUrl")

    /** Status of a single PUT, mapped from the relay's response codes. */
    private enum class PutStatus { Ok, TooLarge, Guard, Failed }

    private fun PutStatus.toUploadResult(): UploadResult = when (this) {
        PutStatus.Ok -> UploadResult.Ok
        PutStatus.TooLarge -> UploadResult.TooLarge
        PutStatus.Guard -> UploadResult.Guard
        PutStatus.Failed -> UploadResult.Unreachable
    }

    companion object {
        /** Relay audio cap (20 MB); the sender pre-checks so oversized files never start uploading. */
        const val MAX_AUDIO_BYTES: Long = 20L * 1024 * 1024

        /** The relay's track-id shape: local cacheKey (16 hex) or jukebox jobId (32 hex). */
        val TRACK_ID_REGEX = Regex("^[a-f0-9]{16,64}$")

        private val JSON_MEDIA_TYPE: MediaType = "application/json".toMediaType()

        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addNetworkInterceptor(CleartextGuardInterceptor)
            .build()

        /**
         * A [RequestBody] that streams from [streamProvider] without buffering the whole file in
         * memory; see [streamingRequestBody] for the contract.
         */
        fun streamingBody(
            contentType: MediaType?,
            sizeBytes: Long,
            onBytesWritten: ((Long) -> Unit)? = null,
            streamProvider: () -> InputStream
        ): RequestBody = streamingRequestBody(contentType, sizeBytes, onBytesWritten, streamProvider)
    }
}
