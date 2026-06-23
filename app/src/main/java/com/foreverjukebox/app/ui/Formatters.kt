package com.foreverjukebox.app.ui

import kotlin.math.floor

fun formatDuration(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, secs)
}

fun formatDurationShort(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}

fun formatCursorTime(seconds: Double): String {
    val total = if (seconds.isFinite()) {
        floor(seconds).toLong().coerceAtLeast(0)
    } else {
        0L
    }

    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
