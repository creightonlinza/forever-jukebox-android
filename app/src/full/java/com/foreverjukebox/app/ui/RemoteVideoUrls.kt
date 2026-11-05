package com.foreverjukebox.app.ui

// YouTube URL builders for the server/remote (full) build. Kept out of src/main so
// the local-only Play build contains no YouTube URLs or related strings.

// 480x360 for the preview dialog.
internal fun youtubeThumbnailUrl(videoId: String): String = youtubeImageUrl(videoId, "hqdefault")

// 120x90 for search result rows; the 16:9 crop trims its letterbox bars.
internal fun youtubeRowThumbnailUrl(videoId: String): String = youtubeImageUrl(videoId, "default")

private fun youtubeImageUrl(videoId: String, file: String): String {
    val normalizedVideoId = videoId.trim()
    require(normalizedVideoId.isNotEmpty()) { "videoId must not be blank" }
    return "https://i.ytimg.com/vi/$normalizedVideoId/$file.jpg"
}

internal fun youtubeWatchUrl(videoId: String): String {
    val normalizedVideoId = videoId.trim()
    require(normalizedVideoId.isNotEmpty()) { "videoId must not be blank" }
    return "https://www.youtube.com/watch?v=$normalizedVideoId"
}
