package com.foreverjukebox.app.ui

import java.util.Locale
import kotlin.math.floor
import kotlin.math.round

/**
 * Bytes as megabytes with at most one decimal, e.g. "20 MB" or "2.5 MB". [unitSeparator] is what
 * goes between the number and the unit, so a caller wanting "20MB" passes an empty string.
 */
fun formatMegabytes(bytes: Long, unitSeparator: String = " "): String {
    val mb = bytes.coerceAtLeast(0).toDouble() / (1024.0 * 1024.0)
    // Rounded in Double so a size beyond Int range keeps its magnitude instead of saturating.
    val rounded = round(mb * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) {
        "${rounded.toLong()}${unitSeparator}MB"
    } else {
        String.format(Locale.US, "%.1f${unitSeparator}MB", rounded)
    }
}

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

fun formatCursorTime(seconds: Double): String {
    val total = if (seconds.isFinite()) {
        floor(seconds).toLong().coerceAtLeast(0)
    } else {
        0L
    }

    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
