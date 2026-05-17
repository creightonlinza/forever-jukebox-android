package com.foreverjukebox.app.ui

import java.net.URI

object ErrorDisplay {
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
    ): String {
        val message = clean(raw, fallback)
        val label = sourceLabels[sourceProvider?.trim()?.lowercase()]

        if (label != null && isFetchFailure(message, errorCode)) {
            return "$label fetch failed."
        }

        return message
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
