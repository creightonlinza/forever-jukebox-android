package com.foreverjukebox.app.data

const val SOURCE_PROVIDER_YOUTUBE = "youtube"

// Identity prefix for on-device analyzed tracks (cache fingerprint with this prefix).
// The cast relay receiver reports the bare fingerprint, so cast paths strip/restore it.
const val LOCAL_TRACK_ID_PREFIX = "local-"

private val YOUTUBE_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
private val JOB_ID_REGEX = Regex("^[A-Fa-f0-9]{32}$")

data class ParsedTrackIdentity(
    val trackId: String,
    val youtubeId: String? = null,
    val jobId: String? = null
)

fun sourceProviderFromRaw(value: String?): String? {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return normalized.ifBlank { null }
}

fun buildJobTrackId(jobId: String): String {
    val normalized = jobId.trim()
    require(normalized.isNotEmpty()) { "jobId must not be blank" }
    require(isCanonicalJobId(normalized)) { "jobId must be a 32-character hex id" }
    return normalized
}

fun parseTrackId(raw: String?): ParsedTrackIdentity? {
    val normalized = raw?.trim().orEmpty()
    if (normalized.isBlank()) {
        return null
    }
    return when {
        isCanonicalJobId(normalized) -> {
            ParsedTrackIdentity(
                trackId = buildJobTrackId(normalized),
                jobId = normalized
            )
        }
        isYoutubeLikeSourceId(normalized) -> ParsedTrackIdentity(
            trackId = normalized,
            youtubeId = normalized
        )
        else -> null
    }
}

fun isCanonicalJobId(value: String?): Boolean {
    val normalized = value?.trim().orEmpty()
    return JOB_ID_REGEX.matches(normalized)
}

fun canonicalJobId(raw: String?): String? {
    val normalized = raw?.trim().orEmpty()
    return if (isCanonicalJobId(normalized)) {
        buildJobTrackId(normalized)
    } else {
        null
    }
}

fun canonicalTrackId(raw: String?): String? {
    return parseTrackId(raw)?.trackId
}

fun favoriteUniqueSongIdFromTrackId(raw: String?): String? {
    return canonicalTrackId(raw)
}

fun isYoutubeLikeSourceId(value: String?): Boolean {
    val normalized = value?.trim().orEmpty()
    return YOUTUBE_ID_REGEX.matches(normalized)
}
