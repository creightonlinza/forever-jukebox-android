package com.foreverjukebox.app.data

const val SOURCE_PROVIDER_YOUTUBE = "youtube"
const val SOURCE_PROVIDER_SOUNDCLOUD = "soundcloud"
const val SOURCE_PROVIDER_BANDCAMP = "bandcamp"
const val SOURCE_PROVIDER_UPLOAD = "upload"

private val YOUTUBE_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

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
    return normalized
}

fun parseTrackId(raw: String?): ParsedTrackIdentity? {
    val normalized = raw?.trim().orEmpty()
    if (normalized.isBlank()) {
        return null
    }
    return if (isYoutubeLikeSourceId(normalized)) {
        ParsedTrackIdentity(
            trackId = normalized,
            youtubeId = normalized
        )
    } else {
        ParsedTrackIdentity(
            trackId = buildJobTrackId(normalized),
            jobId = normalized
        )
    }
}

fun canonicalTrackId(raw: String?): String? {
    return parseTrackId(raw)?.trackId
}

fun favoriteUniqueSongIdFromTrackId(raw: String?): String? {
    return canonicalTrackId(raw)
}

fun trackIdFromAnalysis(response: AnalysisResponse): String? {
    val jobId = response.id?.trim().orEmpty().ifBlank { null }
    if (jobId != null) {
        return buildJobTrackId(jobId)
    }
    val provider = sourceProviderFromRaw(response.sourceProvider)
    val sourceId = response.sourceId?.trim().orEmpty().ifBlank { null }
    if (provider == SOURCE_PROVIDER_YOUTUBE && sourceId != null) {
        return sourceId
    }
    val youtubeId = response.youtubeId?.trim().orEmpty().ifBlank { null }
    if (youtubeId != null) {
        return youtubeId
    }
    return null
}

fun trackIdFromTopSong(item: TopSongItem): String? {
    val jobId = item.id?.trim().orEmpty().ifBlank { null }
    if (jobId != null) {
        return buildJobTrackId(jobId)
    }
    val provider = sourceProviderFromRaw(item.sourceProvider)
    val sourceId = item.sourceId?.trim().orEmpty().ifBlank { null }
    if (provider == SOURCE_PROVIDER_YOUTUBE && sourceId != null) {
        return sourceId
    }
    val youtubeId = item.youtubeId?.trim().orEmpty().ifBlank { null }
    if (youtubeId != null) {
        return youtubeId
    }
    return null
}

fun isYoutubeLikeSourceId(value: String?): Boolean {
    val normalized = value?.trim().orEmpty()
    return YOUTUBE_ID_REGEX.matches(normalized)
}
