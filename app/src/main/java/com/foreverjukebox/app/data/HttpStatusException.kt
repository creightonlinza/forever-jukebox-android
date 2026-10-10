package com.foreverjukebox.app.data

import java.io.IOException

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
