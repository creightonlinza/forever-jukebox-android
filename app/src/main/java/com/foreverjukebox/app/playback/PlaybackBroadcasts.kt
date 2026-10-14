package com.foreverjukebox.app.playback

import android.content.Context
import android.content.Intent

/** Package-scoped broadcasts from the playback layer to the UI. */
object PlaybackBroadcasts {
    const val ACTION_SLEEP_TIMER_EXPIRED = "com.foreverjukebox.app.playback.SLEEP_TIMER_EXPIRED"
    const val ACTION_PLAYBACK_STATE_CHANGED =
        "com.foreverjukebox.app.playback.PLAYBACK_STATE_CHANGED"
    const val ACTION_PLAYLIST_PREVIOUS = "com.foreverjukebox.app.playback.PLAYLIST_PREVIOUS"
    const val ACTION_PLAYLIST_NEXT = "com.foreverjukebox.app.playback.PLAYLIST_NEXT"
    const val ACTION_CLOSE_FULLSCREEN = "com.foreverjukebox.app.playback.CLOSE_FULLSCREEN"
    const val ACTION_RETRY_FAILED_LOAD = "com.foreverjukebox.app.playback.RETRY_FAILED_LOAD"

    internal fun send(context: Context, action: String) {
        context.sendBroadcast(Intent(action).apply { setPackage(context.packageName) })
    }
}
