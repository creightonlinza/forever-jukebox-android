package com.foreverjukebox.app.data

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The API's 404 detail for a job id it has no row for.
private const val JOB_NOT_FOUND_DETAIL = "Job not found"

class HttpStatusException(
    val statusCode: Int,
    val responseBody: String? = null
) : IOException("HTTP $statusCode")

/**
 * True when the server answered 404. On a request that names a job, that means the
 * server does not have it (deleted, expired, or a link to a different server) — a
 * state of the request, not a fault the app can act on.
 */
fun IOException.isNotFound(): Boolean = this is HttpStatusException && statusCode == 404

/**
 * True only when the API itself says the job does not exist, not for a 404 from a
 * proxy or a missing route.
 */
fun IOException.isJobNotFound(): Boolean {
    if (!isNotFound()) return false
    val body = (this as HttpStatusException).responseBody ?: return false
    val detail = runCatching {
        Json.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return detail == JOB_NOT_FOUND_DETAIL
}
