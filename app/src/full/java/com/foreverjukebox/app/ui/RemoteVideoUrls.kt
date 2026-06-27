package com.foreverjukebox.app.ui

// YouTube URL builders for the server/remote (full) build. Kept out of src/main so
// the local-only Play build contains no YouTube URLs or related strings.

internal fun youtubeThumbnailUrl(videoId: String): String {
    val normalizedVideoId = videoId.trim()
    require(normalizedVideoId.isNotEmpty()) { "videoId must not be blank" }
    return "https://i.ytimg.com/vi/$normalizedVideoId/hqdefault.jpg"
}

internal fun youtubeWatchUrl(videoId: String): String {
    val normalizedVideoId = videoId.trim()
    require(normalizedVideoId.isNotEmpty()) { "videoId must not be blank" }
    return "https://www.youtube.com/watch?v=$normalizedVideoId"
}
