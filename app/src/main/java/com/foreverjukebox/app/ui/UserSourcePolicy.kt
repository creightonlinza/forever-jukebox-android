package com.foreverjukebox.app.ui

import java.net.URI

/**
 * Client-side rules for the user-supplied track sources (pasted URL and file upload). Mirrors the
 * web client's `normalizeSupportedSourceUrl` and the server's filename/extension validation so
 * invalid input fails locally instead of burning a round trip. The server re-validates everything.
 */

data class NormalizedSourceUrl(
    val url: String,
    val provider: String,
    /** Set when the URL resolves to a single YouTube video, enabling the by-source job lookup. */
    val youtubeId: String? = null
)

private val YOUTUBE_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

/**
 * Validate and normalize a user-pasted source reference. A bare 11-char YouTube id expands to a
 * watch URL; otherwise the value must be an http(s) URL on YouTube, SoundCloud, or Bandcamp
 * (subdomains allowed). The fragment is stripped, the query kept. Returns null for anything else.
 */
fun normalizeSupportedSourceUrl(raw: String): NormalizedSourceUrl? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    if (YOUTUBE_ID_REGEX.matches(value)) {
        return NormalizedSourceUrl(
            url = "https://www.youtube.com/watch?v=$value",
            provider = "youtube",
            youtubeId = value
        )
    }
    val withoutFragment = value.substringBefore('#').trim()
    val uri = runCatching { URI(withoutFragment) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.removePrefix("www.")?.lowercase() ?: return null
    val provider = when {
        host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") -> "youtube"
        host == "soundcloud.com" || host.endsWith(".soundcloud.com") -> "soundcloud"
        host == "bandcamp.com" || host.endsWith(".bandcamp.com") -> "bandcamp"
        else -> return null
    }
    return NormalizedSourceUrl(
        url = withoutFragment,
        provider = provider,
        youtubeId = if (provider == "youtube") extractYoutubeId(uri, host) else null
    )
}

private fun extractYoutubeId(uri: URI, normalizedHost: String): String? {
    val candidate = if (normalizedHost == "youtu.be") {
        uri.path?.trim('/')?.substringBefore('/')
    } else {
        uri.rawQuery
            ?.split('&')
            ?.firstOrNull { it.startsWith("v=") }
            ?.substringAfter('=')
    }
    return candidate?.takeIf { YOUTUBE_ID_REGEX.matches(it) }
}

/** Server-default upload extensions, used when the config hasn't provided a list. */
private val DEFAULT_UPLOAD_EXTS = listOf(".aac", ".flac", ".m4a", ".mp3", ".ogg", ".wav", ".webm")

private val MIME_TO_UPLOAD_EXT = mapOf(
    "audio/mpeg" to ".mp3",
    "audio/mp3" to ".mp3",
    "audio/mp4" to ".m4a",
    "audio/m4a" to ".m4a",
    "audio/x-m4a" to ".m4a",
    "audio/aac" to ".aac",
    "audio/flac" to ".flac",
    "audio/x-flac" to ".flac",
    "audio/ogg" to ".ogg",
    "application/ogg" to ".ogg",
    "audio/wav" to ".wav",
    "audio/x-wav" to ".wav",
    "audio/wave" to ".wav",
    "audio/webm" to ".webm",
    "video/webm" to ".webm"
)

private fun normalizeUploadExts(allowedExts: List<String>): List<String> {
    val normalized = allowedExts
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .map { if (it.startsWith(".")) it else ".$it" }
    return normalized.ifEmpty { DEFAULT_UPLOAD_EXTS }
}

/**
 * Resolve the multipart filename for an upload. The server validates by filename suffix only (the
 * MIME type is never inspected), so a SAF display name without an allowed suffix gets one appended
 * from the content type. Returns null when neither the name nor the MIME type yields an allowed
 * extension — block the upload locally in that case.
 */
fun resolveUploadFileName(
    displayName: String?,
    mimeType: String?,
    allowedExts: List<String>
): String? {
    val exts = normalizeUploadExts(allowedExts)
    val name = displayName?.trim().orEmpty().ifEmpty { "audio" }
    val lowered = name.lowercase()
    if (exts.any { lowered.endsWith(it) }) return name
    val derived = mimeType?.trim()?.lowercase()?.let { MIME_TO_UPLOAD_EXT[it] } ?: return null
    if (derived !in exts) return null
    return name + derived
}

private val UPLOAD_TITLE_SEPARATORS = Regex("[_-]+")
private val WHITESPACE_RUN = Regex("\\s+")
private const val UPLOAD_TITLE_MAX_LENGTH = 200

/**
 * The loading-screen title for an upload, mirroring the server's sanitization of the filename stem
 * (`_`/`-` become spaces, 200-char cap) so the seeded title matches the job's final track title.
 */
fun uploadTitleFromFileName(fileName: String): String {
    val stem = fileName.substringBeforeLast('.', fileName)
    return stem
        .replace(UPLOAD_TITLE_SEPARATORS, " ")
        .replace(WHITESPACE_RUN, " ")
        .trim()
        .take(UPLOAD_TITLE_MAX_LENGTH)
}

/**
 * MIME filter for the SAF picker. Some document providers expose `.ogg`/`.webm` under non-audio
 * types, so those are added explicitly when the server accepts the extensions.
 */
fun uploadMimeTypesForPicker(allowedExts: List<String>): Array<String> {
    val exts = normalizeUploadExts(allowedExts)
    val types = mutableListOf("audio/*")
    if (".ogg" in exts) types.add("application/ogg")
    if (".webm" in exts) types.add("video/webm")
    return types.toTypedArray()
}

/** Local error for a file whose type can't be resolved to an allowed upload extension. */
fun unsupportedUploadTypeMessage(allowedExts: List<String>): String {
    val exts = normalizeUploadExts(allowedExts).joinToString(", ") { it.removePrefix(".") }
    return "Unsupported file type. Allowed: $exts."
}

/**
 * User-facing message for an HTTP failure from the URL-analysis endpoint, or null when the
 * generic load-failure handling (including the 422 track-length dialog) should take over.
 */
fun urlAnalysisHttpErrorMessage(
    statusCode: Int,
    responseBody: String?,
    sourceProvider: String?
): String? = when (statusCode) {
    403 -> "This server doesn't allow adding tracks by link."
    400, 500 -> {
        val detail = parseApiErrorDetail(responseBody)
        ErrorDisplay.format(
            raw = detail.message,
            errorCode = detail.errorCode,
            sourceProvider = sourceProvider,
            fallback = "Loading failed."
        )
    }
    else -> null
}

/**
 * User-facing message for an HTTP failure from the upload endpoint, or null when the generic
 * load-failure handling (including the 422 track-length dialog) should take over.
 */
fun uploadHttpErrorMessage(statusCode: Int, responseBody: String?): String? = when (statusCode) {
    403 -> "This server doesn't allow uploads."
    413 -> "This file is too large for this server."
    400, 500 -> {
        val detail = parseApiErrorDetail(responseBody)
        ErrorDisplay.format(
            raw = detail.message,
            errorCode = detail.errorCode,
            fallback = "Loading failed."
        )
    }
    else -> null
}

/** Human-readable size limit for the local too-large error, e.g. "20 MB". */
fun formatUploadSizeLimitMb(maxUploadSize: Long): String {
    val mb = maxUploadSize.toDouble() / (1024.0 * 1024.0)
    val rounded = kotlin.math.round(mb * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) "${rounded.toInt()} MB" else "$rounded MB"
}
