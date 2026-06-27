package com.foreverjukebox.app.data

fun trackIdFromAnalysis(response: AnalysisResponse): String? {
    return canonicalJobId(response.id)
}

fun trackIdFromTopSong(item: TopSongItem): String? {
    return canonicalJobId(item.id)
}

fun youtubeTrackIdFromTopSong(item: TopSongItem): String? {
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
