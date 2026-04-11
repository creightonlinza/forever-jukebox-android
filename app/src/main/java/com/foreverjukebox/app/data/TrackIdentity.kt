package com.foreverjukebox.app.data

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

const val SOURCE_PROVIDER_YOUTUBE = "youtube"
const val SOURCE_PROVIDER_SOUNDCLOUD = "soundcloud"
const val SOURCE_PROVIDER_BANDCAMP = "bandcamp"
const val SOURCE_PROVIDER_UPLOAD = "upload"

private const val STABLE_PREFIX_SOURCE = "src:"
private const val STABLE_PREFIX_JOB = "job:"
private val YOUTUBE_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

data class ParsedTrackIdentity(
    val stableId: String,
    val sourceProvider: String? = null,
    val sourceId: String? = null,
    val jobId: String? = null
)

fun sourceProviderFromRaw(value: String?): String? {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return normalized.ifBlank { null }
}

fun buildSourceStableTrackId(sourceProvider: String, sourceId: String): String {
    val provider = sourceProviderFromRaw(sourceProvider)
        ?: throw IllegalArgumentException("sourceProvider must not be blank")
    val id = sourceId.trim()
    require(id.isNotEmpty()) { "sourceId must not be blank" }
    return "$STABLE_PREFIX_SOURCE$provider:${encodeTrackIdentityPart(id)}"
}

fun buildJobStableTrackId(jobId: String): String {
    val normalized = jobId.trim()
    require(normalized.isNotEmpty()) { "jobId must not be blank" }
    return "$STABLE_PREFIX_JOB$normalized"
}

fun parseTrackStableId(raw: String?): ParsedTrackIdentity? {
    val normalized = raw?.trim().orEmpty()
    if (normalized.isBlank()) {
        return null
    }
    if (normalized.startsWith(STABLE_PREFIX_SOURCE)) {
        val payload = normalized.removePrefix(STABLE_PREFIX_SOURCE)
        val splitAt = payload.indexOf(':')
        if (splitAt <= 0 || splitAt == payload.lastIndex) {
            return null
        }
        val provider = sourceProviderFromRaw(payload.substring(0, splitAt)) ?: return null
        val sourceId = decodeTrackIdentityPart(payload.substring(splitAt + 1))
        if (sourceId.isBlank()) {
            return null
        }
        return ParsedTrackIdentity(
            stableId = buildSourceStableTrackId(provider, sourceId),
            sourceProvider = provider,
            sourceId = sourceId
        )
    }
    if (normalized.startsWith(STABLE_PREFIX_JOB)) {
        val jobId = normalized.removePrefix(STABLE_PREFIX_JOB).trim()
        if (jobId.isBlank()) {
            return null
        }
        return ParsedTrackIdentity(
            stableId = buildJobStableTrackId(jobId),
            jobId = jobId
        )
    }
    return if (isYoutubeLikeSourceId(normalized)) {
        ParsedTrackIdentity(
            stableId = buildSourceStableTrackId(SOURCE_PROVIDER_YOUTUBE, normalized),
            sourceProvider = SOURCE_PROVIDER_YOUTUBE,
            sourceId = normalized
        )
    } else {
        ParsedTrackIdentity(
            stableId = buildJobStableTrackId(normalized),
            jobId = normalized
        )
    }
}

fun canonicalStableTrackId(raw: String?): String? {
    return parseTrackStableId(raw)?.stableId
}

fun stableTrackIdFromAnalysis(response: AnalysisResponse): String? {
    val provider = sourceProviderFromRaw(response.sourceProvider)
    val sourceId = response.sourceId?.trim().orEmpty().ifBlank { null }
    if (provider != null && sourceId != null) {
        return buildSourceStableTrackId(provider, sourceId)
    }
    val youtubeId = response.youtubeId?.trim().orEmpty().ifBlank { null }
    if (youtubeId != null) {
        return buildSourceStableTrackId(SOURCE_PROVIDER_YOUTUBE, youtubeId)
    }
    val jobId = response.id?.trim().orEmpty().ifBlank { null } ?: return null
    return buildJobStableTrackId(jobId)
}

fun stableTrackIdFromTopSong(item: TopSongItem): String? {
    val provider = sourceProviderFromRaw(item.sourceProvider)
    val sourceId = item.sourceId?.trim().orEmpty().ifBlank { null }
    if (provider != null && sourceId != null) {
        return buildSourceStableTrackId(provider, sourceId)
    }
    val youtubeId = item.youtubeId?.trim().orEmpty().ifBlank { null }
    if (youtubeId != null) {
        return buildSourceStableTrackId(SOURCE_PROVIDER_YOUTUBE, youtubeId)
    }
    val jobId = item.id?.trim().orEmpty().ifBlank { null } ?: return null
    return buildJobStableTrackId(jobId)
}

fun isYoutubeLikeSourceId(value: String?): Boolean {
    val normalized = value?.trim().orEmpty()
    return YOUTUBE_ID_REGEX.matches(normalized)
}

private fun encodeTrackIdentityPart(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

private fun decodeTrackIdentityPart(value: String): String {
    return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
