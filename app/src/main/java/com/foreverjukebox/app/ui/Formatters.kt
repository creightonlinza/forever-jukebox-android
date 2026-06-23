package com.foreverjukebox.app.ui

fun formatDuration(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, secs)
}

fun formatTrackDuration(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds < 0) {
        return "-"
    }

    val totalSeconds = seconds.toLong()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

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
