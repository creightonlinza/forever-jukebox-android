package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.HttpStatusException
import java.io.IOException
import java.net.UnknownServiceException
import kotlinx.coroutines.delay

internal const val REMOTE_LOAD_RETRY_MAX_ATTEMPTS = 4
internal const val REMOTE_LOAD_RETRY_DELAY_INITIAL_MS = 1_000L
internal const val REMOTE_LOAD_RETRY_DELAY_MULTIPLIER = 2L

internal fun shouldRetryRemoteLoadFailure(error: IOException): Boolean {
    return when (error) {
        is HttpStatusException -> {
            error.statusCode == 408 ||
                error.statusCode == 429 ||
                error.statusCode >= 500
        }
        // CleartextGuardInterceptor throws this for http requests to disallowed (public)
        // addresses. It is a deterministic policy rejection, not a transient failure, so
        // retrying only wastes backoff time before surfacing the same error.
        is UnknownServiceException -> false
        else -> true
    }
}

internal suspend fun <T> retryTransientRemoteLoad(
    maxAttempts: Int = REMOTE_LOAD_RETRY_MAX_ATTEMPTS,
    initialDelayMs: Long = REMOTE_LOAD_RETRY_DELAY_INITIAL_MS,
    delayMultiplier: Long = REMOTE_LOAD_RETRY_DELAY_MULTIPLIER,
    delayFn: suspend (Long) -> Unit = { delay(it) },
    block: suspend () -> T
): T {
    require(maxAttempts > 0) { "maxAttempts must be positive" }
    require(initialDelayMs >= 0) { "initialDelayMs must not be negative" }
    require(delayMultiplier > 0) { "delayMultiplier must be positive" }

    var attempt = 1
    var nextDelayMs = initialDelayMs
    while (true) {
        try {
            return block()
        } catch (error: HttpStatusException) {
            if (attempt >= maxAttempts || !shouldRetryRemoteLoadFailure(error)) {
                throw error
            }
        } catch (error: IOException) {
            if (attempt >= maxAttempts || !shouldRetryRemoteLoadFailure(error)) {
                throw error
            }
        }
        delayFn(nextDelayMs)
        attempt += 1
        nextDelayMs *= delayMultiplier
    }
}
