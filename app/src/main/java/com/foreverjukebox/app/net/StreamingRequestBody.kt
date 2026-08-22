package com.foreverjukebox.app.net

import java.io.InputStream
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

private const val WRITE_SEGMENT_BYTES = 8L * 1024

/**
 * A [RequestBody] that streams from [streamProvider] without buffering the whole file in memory.
 * [streamProvider] must return a fresh stream each call (it may be re-invoked on a retry).
 * [sizeBytes] is sent as the Content-Length (pass -1 when unknown for chunked transfer).
 * [onBytesWritten] receives the cumulative bytes written so far, on OkHttp's IO thread; the count
 * restarts from zero if OkHttp re-invokes [RequestBody.writeTo] on a retry.
 */
fun streamingRequestBody(
    contentType: MediaType?,
    sizeBytes: Long,
    onBytesWritten: ((Long) -> Unit)? = null,
    streamProvider: () -> InputStream
): RequestBody = object : RequestBody() {
    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = sizeBytes

    override fun writeTo(sink: BufferedSink) {
        streamProvider().use { input ->
            val source = input.source()
            var totalBytes = 0L
            while (true) {
                val read = source.read(sink.buffer, WRITE_SEGMENT_BYTES)
                if (read == -1L) break
                sink.emitCompleteSegments()
                totalBytes += read
                onBytesWritten?.invoke(totalBytes)
            }
        }
    }
}
