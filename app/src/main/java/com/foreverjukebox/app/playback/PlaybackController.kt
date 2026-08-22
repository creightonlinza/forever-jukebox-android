package com.foreverjukebox.app.playback

import android.content.Context
import android.os.SystemClock
import com.foreverjukebox.app.AppLog
import com.foreverjukebox.app.audio.BufferedAudioPlayer
import com.foreverjukebox.app.audio.BufferedAudioCowbellHitScheduler
import com.foreverjukebox.app.audio.CowbellOverlayController
import com.foreverjukebox.app.audio.NativeCowbellOverlayController
import com.foreverjukebox.app.audio.NoOpCowbellOverlayController
import com.foreverjukebox.app.autocanonizer.AutocanonizerController
import com.foreverjukebox.app.autocanonizer.BufferedAutocanonizerPlayer
import com.foreverjukebox.app.engine.JukeboxEngine
import com.foreverjukebox.app.engine.JukeboxEngineOptions
import com.foreverjukebox.app.engine.QuantumBase
import com.foreverjukebox.app.engine.RandomMode
import com.foreverjukebox.app.engine.VisualizationData
import com.foreverjukebox.app.ui.AudioModeIntensity
import com.foreverjukebox.app.ui.JukeboxAudioMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

sealed interface PlaybackStartResult {
    data object Started : PlaybackStartResult

    /** Delayed audio focus was accepted; playback auto-starts when the system grants focus. */
    data object WaitingForFocus : PlaybackStartResult

    sealed interface Failure : PlaybackStartResult

    data object NoAudio : Failure

    data object FocusDenied : Failure

    /** [cause] is null when the engine started but the player never reported playing. */
    data class StartFailed(val cause: Throwable?) : Failure
}

class PlaybackController {
    val player = BufferedAudioPlayer()
    val engine = JukeboxEngine(player, JukeboxEngineOptions(randomMode = RandomMode.Random))
    private val autocanonizerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val autocanonizerPlayer = BufferedAutocanonizerPlayer(player)
    val autocanonizer = AutocanonizerController(autocanonizerPlayer, autocanonizerScope)
    private var audioFocusController: PlaybackAudioFocusController =
        NoOpPlaybackAudioFocusController
    private var cowbellOverlay: CowbellOverlayController = NoOpCowbellOverlayController
    private var playbackStateChangedBroadcaster: (() -> Unit)? = null

    private var playTimerMs = 0L
    private var lastPlayStamp: Long? = null
    private var transportState = TransportState.Stopped
    // Non-null while a play request is parked on a delayed audio focus grant; the
    // value is the resetFromStart flag to replay once the system grants focus.
    private var pendingFocusPlayResetFromStart: Boolean? = null
    private var trackTitle: String? = null
    private var trackArtist: String? = null
    private var duckingActive = false

    private enum class TransportState {
        Playing,
        Paused,
        Stopped
    }

    fun attachAudioFocus(context: Context) {
        val appContext = context.applicationContext.playbackAttributionContext()
        playbackStateChangedBroadcaster = {
            broadcastLocalPlaybackStateChanged(appContext)
        }
        cowbellOverlay = NativeCowbellOverlayController(
            BufferedAudioCowbellHitScheduler(appContext, player)
        )
        audioFocusController = AndroidPlaybackAudioFocusController(
            context = appContext,
            onDuckingChanged = ::setDucking,
            onPlaybackFocusLost = ::pauseForAudioFocusLoss,
            onPlaybackFocusGained = ::startPendingFocusPlayback
        )
    }

    fun requestAudioFocusForLocalPlayback(): Boolean {
        return when (audioFocusController.requestAudioFocus()) {
            AudioFocusRequestResult.Granted -> true
            AudioFocusRequestResult.Delayed -> {
                // These callers (autocanonizer/cast/service) have no pending-play
                // machinery to replay on a later grant, so a delayed request would
                // leave the app holding focus with nothing queued to start.
                // Withdraw it rather than leak media focus.
                audioFocusController.abandonAudioFocus()
                AppLog.warn(TAG, "Local playback focus delayed with no pending play; abandoning request")
                false
            }
            AudioFocusRequestResult.Denied -> false
        }
    }

    fun setDucking(active: Boolean) {
        duckingActive = active
        player.setDucking(active)
        autocanonizer.setDucking(active)
        cowbellOverlay.setVolume(if (active) DUCKED_VOLUME else NORMAL_VOLUME)
    }

    fun setJukeboxAudioMode(
        mode: JukeboxAudioMode,
        intensity: Int = AudioModeIntensity.DEFAULT
    ) {
        player.setJukeboxAudioMode(mode, intensity)
        cowbellOverlay.setEnabled(mode == JukeboxAudioMode.Cowbell)
    }

    fun setCowbellSectionStartBeatIndices(indices: Collection<Int>) {
        cowbellOverlay.setSectionStartBeatIndices(indices)
    }

    fun handleCowbellBeatEnter(
        beatIndex: Int,
        beat: QuantumBase,
        nextBeat: QuantumBase?,
        playbackRate: Double
    ) {
        cowbellOverlay.handleBeatEnter(
            beatIndex = beatIndex,
            beat = beat,
            nextBeat = nextBeat,
            playbackRate = playbackRate
        )
    }

    fun setTrackMeta(title: String?, artist: String?) {
        trackTitle = title
        trackArtist = artist
    }

    fun getTrackTitle(): String? = trackTitle

    fun getTrackArtist(): String? = trackArtist

    private fun beginPlayback(resetFromStart: Boolean, requestFocus: Boolean = true): PlaybackStartResult {
        if (!player.hasAudio()) {
            transportState = TransportState.Stopped
            AppLog.error(TAG, "Jukebox playback start failed: no audio in player")
            return PlaybackStartResult.NoAudio
        }
        if (requestFocus) {
            when (audioFocusController.requestAudioFocus()) {
                AudioFocusRequestResult.Granted -> Unit
                AudioFocusRequestResult.Delayed -> {
                    pendingFocusPlayResetFromStart = resetFromStart
                    // Leave transportState as-is (Paused or Stopped). The engine
                    // hasn't started, so forcing Paused would strand a Stopped
                    // track in Paused if the park is later cancelled, making the
                    // next play take the resume path instead of restarting. Both
                    // prior states already render a play button, so the UI is
                    // unchanged either way.
                    AppLog.warn(TAG, "Jukebox playback start waiting for delayed audio focus grant")
                    return PlaybackStartResult.WaitingForFocus
                }
                AudioFocusRequestResult.Denied -> {
                    transportState = TransportState.Stopped
                    AppLog.error(TAG, "Jukebox playback start failed: audio focus denied")
                    return PlaybackStartResult.FocusDenied
                }
            }
        }
        // Guard against any leftover gain shaping from autocanonizer paths.
        player.setGain(1.0)
        cowbellOverlay.setVolume(if (duckingActive) DUCKED_VOLUME else NORMAL_VOLUME)
        if (resetFromStart) {
            cowbellOverlay.cancelScheduledHits()
            engine.stopJukebox()
            engine.resetStats()
            playTimerMs = 0L
            lastPlayStamp = null
        }
        var startError: Throwable? = null
        val started = runCatching {
            engine.play()
            engine.startJukebox(resetState = resetFromStart)
            player.isPlaying()
        }.getOrElse { error ->
            startError = error
            false
        }
        if (started) {
            lastPlayStamp = SystemClock.elapsedRealtime()
            transportState = TransportState.Playing
            return PlaybackStartResult.Started
        }
        if (startError != null) {
            AppLog.error(TAG, "Jukebox playback start failed: engine start threw", startError)
        } else {
            AppLog.error(TAG, "Jukebox playback start failed: player not playing after engine start")
        }
        runCatching { engine.stopJukebox() }
        audioFocusController.abandonAudioFocus()
        transportState = TransportState.Stopped
        return PlaybackStartResult.StartFailed(startError)
    }

    fun playOrResumePlaybackResult(): PlaybackStartResult {
        if (pendingFocusPlayResetFromStart != null) {
            return PlaybackStartResult.WaitingForFocus
        }
        return when (transportState) {
            TransportState.Playing -> PlaybackStartResult.Started
            TransportState.Paused -> beginPlayback(resetFromStart = false)
            TransportState.Stopped -> beginPlayback(resetFromStart = true)
        }
    }

    fun playOrResumePlayback(): Boolean {
        return playOrResumePlaybackResult() == PlaybackStartResult.Started
    }

    private fun startPendingFocusPlayback() {
        val resetFromStart = pendingFocusPlayResetFromStart ?: return
        pendingFocusPlayResetFromStart = null
        AppLog.warn(TAG, "Delayed audio focus granted; starting pending playback")
        beginPlayback(resetFromStart = resetFromStart, requestFocus = false)
        playbackStateChangedBroadcaster?.invoke()
    }

    private fun cancelPendingFocusPlay() {
        if (pendingFocusPlayResetFromStart == null) return
        pendingFocusPlayResetFromStart = null
        // Withdraw the delayed focus request so a later grant doesn't leave the
        // app holding focus with nothing queued to play.
        audioFocusController.abandonAudioFocus()
    }

    fun pausePlayback() {
        cancelPendingFocusPlay()
        cowbellOverlay.cancelScheduledHits()
        if (transportState != TransportState.Playing) {
            return
        }
        engine.pauseJukebox()
        markTransportPaused()
        audioFocusController.abandonAudioFocus()
    }

    fun togglePlayback(): Boolean {
        return when (transportState) {
            TransportState.Playing -> {
                pausePlayback()
                false
            }
            TransportState.Paused,
            TransportState.Stopped -> playOrResumePlayback()
        }
    }

    fun stopPlayback() {
        cancelPendingFocusPlay()
        cowbellOverlay.cancelScheduledHits()
        if (transportState == TransportState.Stopped) {
            return
        }
        engine.stopJukebox()
        markTransportStopped()
        audioFocusController.abandonAudioFocus()
    }

    fun resetTimers() {
        playTimerMs = 0L
        lastPlayStamp = null
    }

    fun startExternalPlayback(resetTimers: Boolean = true) {
        cancelPendingFocusPlay()
        if (resetTimers) {
            playTimerMs = 0L
        }
        lastPlayStamp = SystemClock.elapsedRealtime()
        transportState = TransportState.Playing
    }

    fun stopExternalPlayback() {
        cancelPendingFocusPlay()
        cowbellOverlay.cancelScheduledHits()
        markTransportStopped()
        audioFocusController.abandonAudioFocus()
    }

    fun pauseExternalPlayback() {
        cancelPendingFocusPlay()
        cowbellOverlay.cancelScheduledHits()
        markTransportPaused()
        audioFocusController.abandonAudioFocus()
    }

    private fun markTransportPaused() {
        if (lastPlayStamp != null) {
            playTimerMs += SystemClock.elapsedRealtime() - lastPlayStamp!!
            lastPlayStamp = null
        }
        transportState = TransportState.Paused
    }

    private fun markTransportStopped() {
        if (lastPlayStamp != null) {
            playTimerMs += SystemClock.elapsedRealtime() - lastPlayStamp!!
            lastPlayStamp = null
        }
        transportState = TransportState.Stopped
    }

    private fun pauseForAudioFocusLoss() {
        cancelPendingFocusPlay()
        if (transportState != TransportState.Playing) {
            setDucking(false)
            return
        }
        if (autocanonizer.isRunning()) {
            autocanonizer.pause()
        } else {
            engine.pauseJukebox()
        }
        markTransportPaused()
        setDucking(false)
        playbackStateChangedBroadcaster?.invoke()
    }

    fun isPlaying(): Boolean = transportState == TransportState.Playing

    fun isPaused(): Boolean = transportState == TransportState.Paused

    fun getListenTimeSeconds(): Double {
        val now = SystemClock.elapsedRealtime()
        val totalMs = playTimerMs + (lastPlayStamp?.let { now - it } ?: 0L)
        return totalMs / 1000.0
    }

    fun getPlaybackPositionMs(): Long {
        return (player.getCurrentTime() * 1000.0).toLong()
    }

    fun getTrackDurationMs(): Long? {
        return player.getDurationSeconds()?.let { (it * 1000.0).toLong() }
    }

    fun seekToBeat(index: Int, data: VisualizationData? = engine.getVisualizationData()): Boolean {
        val beats = data?.beats ?: return false
        if (index !in beats.indices) return false
        val beat = beats[index]
        cowbellOverlay.cancelScheduledHits()
        player.seek(beat.start)
        engine.seekToBeat(index)
        return true
    }

    fun syncAutocanonizerAudio(): Boolean {
        return autocanonizer.syncAudioFromMain()
    }

    // The controller outlives any single ViewModel (see PlaybackControllerHolder), so a
    // ViewModel going away must only drop what that ViewModel owned: audio focus, any
    // parked play, scheduled overlay hits, and the decoded audio. Everything released
    // here is rebuilt lazily on the next load; nothing is latched shut.
    fun detachOwner() {
        cancelPendingFocusPlay()
        audioFocusController.abandonAudioFocus()
        cowbellOverlay.cancelScheduledHits()
        autocanonizer.release()
        player.release()
    }

    private companion object {
        private const val TAG = "PlaybackController"
        private const val NORMAL_VOLUME = 1.0
        private const val DUCKED_VOLUME = 0.2
    }
}

object PlaybackControllerHolder {
    @Volatile
    private var controller: PlaybackController? = null

    fun get(context: Context): PlaybackController {
        return controller ?: synchronized(this) {
            controller ?: PlaybackController().also {
                it.attachAudioFocus(context)
                controller = it
            }
        }
    }
}
