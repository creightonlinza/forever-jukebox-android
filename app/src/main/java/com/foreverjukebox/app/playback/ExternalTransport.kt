package com.foreverjukebox.app.playback

/**
 * A play mode that drives its own audio instead of the jukebox engine. Surfaces outside the
 * view model (notification, media buttons, audio focus, sleep timer) pause, resume and stop
 * whichever transport is active through this interface, so they never act on the wrong player.
 */
interface ExternalTransport {
    fun isRunning(): Boolean

    fun isPaused(): Boolean

    fun pause()

    /** Continues from the paused position; false when there was nothing paused or audio did not start. */
    fun resume(): Boolean

    fun stop()
}
