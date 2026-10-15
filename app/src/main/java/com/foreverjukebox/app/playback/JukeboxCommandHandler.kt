package com.foreverjukebox.app.playback

import com.foreverjukebox.app.ui.stopAllPlaybackTransports

internal enum class PlaybackAction {
    Play,
    Pause,
    Stop
}

internal enum class TransportActionRoute {
    /** Drop the press: no meaningful transport target in this notification state. */
    Ignore,

    /** Ask the app to run the failed-load retry flow instead of touching playback. */
    BroadcastRetry,

    /** Normal transport handling (play/pause/stop the active playback). */
    Handle
}

/**
 * Routes a transport press by notification state. While a load is in flight every
 * press is dropped; while a failed notification is showing, Play becomes the retry
 * trigger (Pause/Stop have no playback to act on); otherwise the press reaches normal
 * playback handling.
 */
internal fun routeTransportAction(
    isLoading: Boolean,
    isLoadFailed: Boolean,
    action: PlaybackAction
): TransportActionRoute {
    if (isLoading) {
        return TransportActionRoute.Ignore
    }
    if (isLoadFailed) {
        return if (action == PlaybackAction.Play) {
            TransportActionRoute.BroadcastRetry
        } else {
            TransportActionRoute.Ignore
        }
    }
    return TransportActionRoute.Handle
}

/**
 * True when a local press asks to start playback but nothing is loaded to play. Media
 * keys can reach the session after the UI released its audio (app exited, task removed,
 * Bluetooth device auto-sending Play on connect), so this is an expected state rather
 * than a playback failure.
 */
internal fun isPlayRequestWithoutAudio(
    action: PlaybackAction,
    hasAudio: Boolean,
    isPlaying: Boolean,
    externalTransportActive: Boolean
): Boolean {
    if (action != PlaybackAction.Play) {
        return false
    }
    return !hasAudio && !isPlaying && !externalTransportActive
}

/** The parts of [PlaybackController] that session transport presses act on. */
internal interface JukeboxTransportTarget {
    fun isPlaying(): Boolean
    fun hasAudio(): Boolean
    fun hasOwner(): Boolean
    fun activeExternalTransport(): ExternalTransport?
    fun requestAudioFocusForLocalPlayback(): Boolean
    fun startExternalPlayback(resetTimers: Boolean)
    fun playOrResumePlayback(): Boolean
    fun pausePlayback()
    fun pauseExternalPlayback()
    fun stopAllPlayback()
}

internal class PlaybackControllerTransportTarget(
    private val controller: PlaybackController
) : JukeboxTransportTarget {
    override fun isPlaying(): Boolean = controller.isPlaying()

    override fun hasAudio(): Boolean = controller.player.hasAudio()

    override fun hasOwner(): Boolean = controller.hasOwner()

    override fun activeExternalTransport(): ExternalTransport? = controller.activeExternalTransport()

    override fun requestAudioFocusForLocalPlayback(): Boolean =
        controller.requestAudioFocusForLocalPlayback()

    override fun startExternalPlayback(resetTimers: Boolean) {
        controller.startExternalPlayback(resetTimers = resetTimers)
    }

    override fun playOrResumePlayback(): Boolean = controller.playOrResumePlayback()

    override fun pausePlayback() {
        controller.pausePlayback()
    }

    override fun pauseExternalPlayback() {
        controller.pauseExternalPlayback()
    }

    override fun stopAllPlayback() {
        stopAllPlaybackTransports(controller)
    }
}

/** App-side effects of session presses that the playback controller does not own. */
internal interface JukeboxSessionEvents {
    fun retryFailedLoad()
    fun skipToPrevious()
    fun skipToNext()

    /** Asks the UI to re-read the controller's transport state and re-sync the session. */
    fun localPlaybackStateChanged()

    /** Sends a transport command to the cast receiver; false when it could not be sent. */
    fun sendCastCommand(command: String): Boolean

    /** Ends the session: nothing owns the audio it would control. */
    fun stopSession()
}

/**
 * Applies session transport presses (notification buttons, lock screen, media keys) to
 * the active playback. Local presses go to the playback controller, picking whichever
 * play-mode transport is running; cast presses are forwarded to the receiver.
 */
internal class JukeboxCommandHandler(
    private val target: JukeboxTransportTarget,
    private val events: JukeboxSessionEvents,
    private val warn: (String) -> Unit
) {
    /**
     * Applies [action] for [surface] and returns what the session should layer over the
     * snapshot afterwards, or null when nothing changes. A stop on any surface but Loading
     * is marked, since that is how a dismissed notification arrives.
     */
    fun handle(action: PlaybackAction, surface: PlaybackSessionSurface): SessionOverride? {
        val route = routeTransportAction(
            isLoading = surface is PlaybackSessionSurface.Loading,
            isLoadFailed = surface == PlaybackSessionSurface.LoadFailed,
            action = action
        )
        val result = when (route) {
            TransportActionRoute.Ignore -> null
            TransportActionRoute.BroadcastRetry -> {
                events.retryFailedLoad()
                null
            }
            TransportActionRoute.Handle -> when (surface) {
                is PlaybackSessionSurface.Cast -> handleCast(action)
                PlaybackSessionSurface.Local,
                PlaybackSessionSurface.Hidden -> {
                    handleLocal(action)
                    null
                }
                is PlaybackSessionSurface.Loading,
                PlaybackSessionSurface.LoadFailed -> null
            }
        }
        val marksStop = action == PlaybackAction.Stop &&
            surface !is PlaybackSessionSurface.Loading &&
            result?.castUnreachable != true
        return if (marksStop) (result ?: SessionOverride()).copy(stopped = true) else result
    }

    fun skipToPrevious(surface: PlaybackSessionSurface) {
        if (acceptsSkip(surface)) {
            events.skipToPrevious()
        }
    }

    fun skipToNext(surface: PlaybackSessionSurface) {
        if (acceptsSkip(surface)) {
            events.skipToNext()
        }
    }

    private fun acceptsSkip(surface: PlaybackSessionSurface): Boolean {
        return surface == PlaybackSessionSurface.Local || surface is PlaybackSessionSurface.Cast
    }

    private fun handleCast(action: PlaybackAction): SessionOverride {
        val command = when (action) {
            PlaybackAction.Play -> "play"
            PlaybackAction.Pause -> "pause"
            PlaybackAction.Stop -> "stop"
        }
        if (!events.sendCastCommand(command)) {
            warn("Cast $command press not delivered; hiding the cast session")
            return SessionOverride(castUnreachable = true)
        }
        return SessionOverride(castPlaying = action == PlaybackAction.Play)
    }

    private fun handleLocal(action: PlaybackAction) {
        val external = target.activeExternalTransport()
        if (
            isPlayRequestWithoutAudio(
                action = action,
                hasAudio = target.hasAudio(),
                isPlaying = target.isPlaying(),
                externalTransportActive = external != null
            )
        ) {
            warn("Ignoring $action press: no audio loaded")
            if (target.hasOwner()) {
                // The owner may be mid-load and tracks the session's visibility; let it
                // re-sync the session rather than ending it underneath it.
                events.localPlaybackStateChanged()
            } else {
                events.stopSession()
            }
            return
        }
        val externalRunning = external?.isRunning() == true
        val externalPaused = external?.isPaused() == true
        when (action) {
            PlaybackAction.Play -> when {
                externalRunning -> Unit
                externalPaused -> {
                    val resumed = target.requestAudioFocusForLocalPlayback() &&
                        external?.resume() == true
                    if (resumed) {
                        target.startExternalPlayback(resetTimers = false)
                    }
                }
                !target.isPlaying() -> target.playOrResumePlayback()
            }
            PlaybackAction.Pause -> if (externalRunning) {
                external?.pause()
                target.pauseExternalPlayback()
            } else {
                // Idempotent when already paused/stopped; also cancels a play request
                // parked on a delayed audio focus grant.
                target.pausePlayback()
            }
            PlaybackAction.Stop -> target.stopAllPlayback()
        }
        events.localPlaybackStateChanged()
    }
}
