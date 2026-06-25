package com.foreverjukebox.app.data

import java.io.IOException

class HttpStatusException(
    val statusCode: Int,
    val responseBody: String? = null
) : IOException("HTTP $statusCode")
