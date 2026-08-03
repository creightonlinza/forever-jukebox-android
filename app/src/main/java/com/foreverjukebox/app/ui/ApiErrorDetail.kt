package com.foreverjukebox.app.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** A FastAPI error body's user-relevant parts, for display via [ErrorDisplay.format]. */
data class ApiErrorDetail(
    val message: String? = null,
    val errorCode: String? = null
)

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Extract the `detail` field of a FastAPI error body, which takes three shapes: a plain string,
 * an object with `message`/`error_code`, or a pydantic validation list whose entries carry `msg`.
 * Malformed or blank bodies yield empty detail rather than throwing.
 */
fun parseApiErrorDetail(responseBody: String?): ApiErrorDetail {
    val body = responseBody?.trim().orEmpty()
    if (body.isEmpty()) return ApiErrorDetail()
    val detail = runCatching { lenientJson.parseToJsonElement(body).jsonObject["detail"] }
        .getOrNull() ?: return ApiErrorDetail()
    return when (detail) {
        is JsonPrimitive -> ApiErrorDetail(message = detail.stringOrNull())
        is JsonObject -> ApiErrorDetail(
            message = (detail["message"] as? JsonPrimitive)?.stringOrNull(),
            errorCode = (detail["error_code"] as? JsonPrimitive)?.stringOrNull()
        )
        is JsonArray -> ApiErrorDetail(
            message = detail
                .mapNotNull { ((it as? JsonObject)?.get("msg") as? JsonPrimitive)?.stringOrNull() }
                .firstOrNull()
        )
    }
}

private fun JsonPrimitive.stringOrNull(): String? =
    if (isString) content.takeIf { it.isNotBlank() } else null
