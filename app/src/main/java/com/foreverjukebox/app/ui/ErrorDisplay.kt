package com.foreverjukebox.app.ui

import java.net.URI

/**
 * A load failure as shown to the user. [retryBlocked] marks failures an immediate
 * retry cannot fix, so surfaces that offer a retry can withhold it.
 */
data class LoadFailure(val message: String, val retryBlocked: Boolean = false)

object ErrorDisplay {
    const val YOUTUBE_BLOCKED_MESSAGE =
        "YouTube is temporarily blocking this server. Please try again later."

    // Reported while YouTube bot-checks the server: every YouTube download fails
    // until the block lifts, so retrying immediately cannot succeed.
    private const val YOUTUBE_BLOCKED_CODE = "youtube_unreachable"
    private const val YOUTUBE_PROVIDER = "youtube"

    private val fetchFailureCodes = setOf(
        "download_unavailable",
        "youtube_unavailable",
        "youtube_unreachable"
    )

    private val sourceLabels = mapOf(
        "youtube" to "YouTube",
        "soundcloud" to "SoundCloud",
        "bandcamp" to "Bandcamp"
    )

    private val whitespaceRegex = Regex("\\s+")
    private val youtubeIdRegex = Regex("^[A-Za-z0-9_-]{11}$")

    fun clean(raw: String?, fallback: String = "Loading failed."): String {
        var message = raw.orEmpty().replace(whitespaceRegex, " ").trim()
        while (message.startsWith("Error:", ignoreCase = true)) {
            message = message.substringAfter(":").trim()
        }
        return message.ifBlank { fallback }
    }

    fun format(
        raw: String?,
        errorCode: String? = null,
        sourceProvider: String? = null,
        fallback: String = "Loading failed."
    ): String = describe(raw, errorCode, sourceProvider, fallback).message

    /** [format], plus whether the failure is one an immediate retry cannot fix. */
    fun describe(
        raw: String?,
        errorCode: String? = null,
        sourceProvider: String? = null,
        fallback: String = "Loading failed."
    ): LoadFailure {
        val message = clean(raw, fallback)
        val provider = sourceProvider?.trim()?.lowercase()
        val label = sourceLabels[provider]

        // The backend assigns the code from the error text without checking the
        // provider, so the blocked message only applies to YouTube or unknown sources.
        if (errorCode?.trim()?.lowercase() == YOUTUBE_BLOCKED_CODE) {
            return when {
                provider.isNullOrEmpty() || provider == YOUTUBE_PROVIDER ->
                    LoadFailure(YOUTUBE_BLOCKED_MESSAGE, retryBlocked = true)
                label != null -> LoadFailure("$label fetch failed.")
                // The backend text names YouTube, which is wrong for any other source.
                else -> LoadFailure(fallback)
            }
        }

        if (label != null && isFetchFailure(message, errorCode)) {
            return LoadFailure("$label fetch failed.")
        }

        return LoadFailure(message)
    }

    fun inferProviderFromUrl(raw: String): String? {
        val value = raw.trim()
        if (youtubeIdRegex.matches(value)) return "youtube"

        val host = runCatching {
            URI(value).host
                ?.removePrefix("www.")
                ?.lowercase()
        }.getOrNull()

        return when {
            host == "youtu.be" || host?.endsWith("youtube.com") == true -> "youtube"
            host?.endsWith("soundcloud.com") == true -> "soundcloud"
            host?.endsWith("bandcamp.com") == true -> "bandcamp"
            else -> null
        }
    }

    private fun isFetchFailure(message: String, errorCode: String?): Boolean {
        if (errorCode?.trim()?.lowercase() in fetchFailureCodes) return true

        val normalized = message.lowercase()
        return normalized == "unable to download video data." ||
            normalized == "this video is not available on youtube." ||
            normalized == "unable to reach youtube" ||
            normalized == "something went wrong. please try again or report an issue on github." ||
            normalized.startsWith("request failed (")
    }
}
