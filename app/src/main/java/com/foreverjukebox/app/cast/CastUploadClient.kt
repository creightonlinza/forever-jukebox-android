package com.foreverjukebox.app.cast

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source

/**
 * Sender-side client for the Local-mode Cast relay (`fj-android-cast`). Creates a relay session,
 * uploads the local audio + analysis JSON, and reports a typed outcome. The relay contract:
 *
 * - `POST /api/sessions` → `201 {"sessionId":"<uuidv4>"}`
 * - `PUT /api/sessions/{sessionId}/audio/{fingerprint}` → `204 | 404 | 413 (>20MB) | 400 | 507`
 * - `PUT /api/sessions/{sessionId}/analysis/{fingerprint}` → `204 | 404 | 413 (>2MB) | 400 | 507`
 *
 * The session must exist before any PUT; on a `404` the sender recreates the session and retries the
 * uploads exactly once (a wiped/replaced relay machine loses the in-memory registry). The HTTP surface
 * takes plain [RequestBody] instances so it can be unit-tested against MockWebServer with in-memory
 * bodies; the Android caller wires content-URI streaming via [streamingBody].
 */
class CastUploadClient(
    private val client: OkHttpClient = defaultClient
) {
    /** Terminal outcome of a full upload attempt. */
    sealed interface UploadResult {
        /** Both files uploaded; [sessionId] is the session to send in the Cast LOAD customData. */
        data class Success(val sessionId: String) : UploadResult

        /** Relay rejected a file as too large (413). */
        data object TooLarge : UploadResult

        /** Relay disk / per-session fingerprint guard tripped (507). */
        data object Guard : UploadResult

        /** Network failure, unexpected status, or still-404 after recreate. */
        data object Unreachable : UploadResult
    }

    /** POST a new session; returns the canonical lowercase UUIDv4 the relay assigns. */
    suspend fun createSession(baseUrl: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(sessionsUrl(baseUrl)).post(EMPTY_BODY).build()
        client.newCall(request).execute().use { response ->
            if (response.code != 201) {
                throw IOException("Unexpected session-create status ${response.code}")
            }
            val raw = response.body?.string().orEmpty()
            val sessionId = runCatching {
                lenientJson.parseToJsonElement(raw).jsonObject["sessionId"]?.jsonPrimitive?.content?.trim()
            }.getOrNull().orEmpty()
            if (sessionId.isBlank()) {
                throw IOException("Session-create response missing sessionId")
            }
            sessionId
        }
    }

    /** PUT the audio bytes for [fingerprint] under [sessionId]. */
    suspend fun putAudio(
        baseUrl: String,
        sessionId: String,
        fingerprint: String,
        body: RequestBody
    ): PutStatus = put(audioUrl(baseUrl, sessionId, fingerprint), body)

    /** PUT the bare analysis JSON for [fingerprint] under [sessionId]. */
    suspend fun putAnalysis(
        baseUrl: String,
        sessionId: String,
        fingerprint: String,
        body: RequestBody
    ): PutStatus = put(analysisUrl(baseUrl, sessionId, fingerprint), body)

    /**
     * Upload both files for a Cast LOAD. Reuses [existingSessionId] when present (one POST per Cast
     * connection); on a `404` from either PUT, creates a fresh session and retries the uploads once.
     */
    suspend fun uploadForCast(
        baseUrl: String,
        existingSessionId: String?,
        fingerprint: String,
        audioBody: RequestBody,
        analysisBody: RequestBody
    ): UploadResult {
        var sessionId = existingSessionId?.takeIf { it.isNotBlank() }
            ?: (createSessionOrNull(baseUrl) ?: return UploadResult.Unreachable)

        repeat(MAX_ATTEMPTS) { attempt ->
            when (val outcome = putBoth(baseUrl, sessionId, fingerprint, audioBody, analysisBody)) {
                PutStatus.Ok -> return UploadResult.Success(sessionId)
                PutStatus.NotFound -> {
                    if (attempt == MAX_ATTEMPTS - 1) return UploadResult.Unreachable
                    sessionId = createSessionOrNull(baseUrl) ?: return UploadResult.Unreachable
                }
                PutStatus.TooLarge -> return UploadResult.TooLarge
                PutStatus.Guard -> return UploadResult.Guard
                PutStatus.Failed -> return UploadResult.Unreachable
            }
        }
        return UploadResult.Unreachable
    }

    private suspend fun putBoth(
        baseUrl: String,
        sessionId: String,
        fingerprint: String,
        audioBody: RequestBody,
        analysisBody: RequestBody
    ): PutStatus {
        val audio = putAudio(baseUrl, sessionId, fingerprint, audioBody)
        if (audio != PutStatus.Ok) return audio
        return putAnalysis(baseUrl, sessionId, fingerprint, analysisBody)
    }

    private suspend fun createSessionOrNull(baseUrl: String): String? =
        runCatching { createSession(baseUrl) }.getOrNull()

    private suspend fun put(url: String, body: RequestBody): PutStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).put(body).build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    204, 200, 201 -> PutStatus.Ok
                    404 -> PutStatus.NotFound
                    413 -> PutStatus.TooLarge
                    507 -> PutStatus.Guard
                    else -> PutStatus.Failed
                }
            }
        } catch (_: IOException) {
            PutStatus.Failed
        }
    }

    private fun sessionsUrl(baseUrl: String): String =
        baseHttpUrl(baseUrl).newBuilder()
            .addPathSegment("api")
            .addPathSegment("sessions")
            .build()
            .toString()

    private fun audioUrl(baseUrl: String, sessionId: String, fingerprint: String): String =
        fileUrl(baseUrl, sessionId, "audio", fingerprint)

    private fun analysisUrl(baseUrl: String, sessionId: String, fingerprint: String): String =
        fileUrl(baseUrl, sessionId, "analysis", fingerprint)

    private fun fileUrl(
        baseUrl: String,
        sessionId: String,
        kind: String,
        fingerprint: String
    ): String =
        baseHttpUrl(baseUrl).newBuilder()
            .addPathSegment("api")
            .addPathSegment("sessions")
            .addPathSegment(sessionId)
            .addPathSegment(kind)
            .addPathSegment(fingerprint)
            .build()
            .toString()

    private fun baseHttpUrl(baseUrl: String): HttpUrl =
        baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid relay base URL: $baseUrl")

    /** Status of a single PUT, mapped from the relay's response codes. */
    enum class PutStatus { Ok, NotFound, TooLarge, Guard, Failed }

    companion object {
        /** Relay audio cap (20 MB); the sender pre-checks so oversized files never start uploading. */
        const val MAX_AUDIO_BYTES: Long = 20L * 1024 * 1024

        private const val MAX_ATTEMPTS = 2

        private val lenientJson = Json { ignoreUnknownKeys = true }

        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * A [RequestBody] that streams from [streamProvider] without buffering the whole file in
         * memory. [streamProvider] must return a fresh stream each call (it may be re-invoked on a
         * retry). [sizeBytes] is sent as the Content-Length.
         */
        fun streamingBody(
            contentType: MediaType?,
            sizeBytes: Long,
            streamProvider: () -> InputStream
        ): RequestBody = object : RequestBody() {
            override fun contentType(): MediaType? = contentType

            override fun contentLength(): Long = sizeBytes

            override fun writeTo(sink: BufferedSink) {
                streamProvider().use { input ->
                    sink.writeAll(input.source())
                }
            }
        }
    }
}
