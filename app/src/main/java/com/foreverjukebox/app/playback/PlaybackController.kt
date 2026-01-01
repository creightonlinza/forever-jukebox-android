package com.foreverjukebox.app.playback

import android.content.Context
import android.os.SystemClock
import com.foreverjukebox.app.audio.BufferedAudioPlayer
import com.foreverjukebox.app.engine.JukeboxEngine
import com.foreverjukebox.app.engine.JukeboxEngineOptions
import com.foreverjukebox.app.engine.RandomMode

class PlaybackController(context: Context) {
    val player = BufferedAudioPlayer(context)
    val engine = JukeboxEngine(player, JukeboxEngineOptions(randomMode = RandomMode.Random))

    private var playTimerMs = 0L
    private var lastPlayStamp: Long? = null
    private var isRunning = false
    private var trackTitle: String? = null
    private var trackArtist: String? = null

    fun setTrackMeta(title: String?, artist: String?) {
        trackTitle = title
        trackArtist = artist
    }

    fun getTrackTitle(): String? = trackTitle

    fun getTrackArtist(): String? = trackArtist

    fun togglePlayback(): Boolean {
        if (!isRunning) {
            engine.stopJukebox()
            engine.resetStats()
            playTimerMs = 0L
            lastPlayStamp = null
            engine.startJukebox()
            engine.play()
            lastPlayStamp = SystemClock.elapsedRealtime()
            isRunning = true
        } else {
            engine.stopJukebox()
            if (lastPlayStamp != null) {
                playTimerMs += SystemClock.elapsedRealtime() - lastPlayStamp!!
                lastPlayStamp = null
            }
            isRunning = false
            engine.resetStats()
        }
        return isRunning
    }

    fun stopPlayback() {
        if (isRunning) {
            engine.stopJukebox()
            if (lastPlayStamp != null) {
                playTimerMs += SystemClock.elapsedRealtime() - lastPlayStamp!!
                lastPlayStamp = null
            }
            isRunning = false
        }
        engine.resetStats()
    }

    fun resetTimers() {
        playTimerMs = 0L
        lastPlayStamp = null
    }

    fun isPlaying(): Boolean = isRunning

    fun getListenTimeSeconds(): Double {
        val now = SystemClock.elapsedRealtime()
        val totalMs = playTimerMs + (lastPlayStamp?.let { now - it } ?: 0L)
        return totalMs / 1000.0
    }
}

object PlaybackControllerHolder {
    @Volatile
    private var controller: PlaybackController? = null

    fun get(context: Context): PlaybackController {
        return controller ?: synchronized(this) {
            controller ?: PlaybackController(context.applicationContext).also { controller = it }
        }
    }
}
