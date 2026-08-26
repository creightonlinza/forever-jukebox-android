package com.foreverjukebox.app.ui

import com.foreverjukebox.app.playback.PlaybackController

internal data class ModeTransportPlan(
    val stopAllTransports: Boolean,
    val stopAutocanonizerWhileIdle: Boolean,
    val invokeOnStopped: Boolean,
    val clearAutocanonizerAudio: Boolean
)

internal fun resolveModeTransportPlan(
    previousMode: PlaybackMode,
    targetMode: PlaybackMode,
    isRunning: Boolean
): ModeTransportPlan {
    val clearAutocanonizerAudio =
        previousMode == PlaybackMode.Autocanonizer && targetMode == PlaybackMode.Jukebox
    if (isRunning) {
        return ModeTransportPlan(
            stopAllTransports = true,
            stopAutocanonizerWhileIdle = false,
            invokeOnStopped = true,
            clearAutocanonizerAudio = clearAutocanonizerAudio
        )
    }
    return ModeTransportPlan(
        stopAllTransports = false,
        stopAutocanonizerWhileIdle = previousMode == PlaybackMode.Autocanonizer,
        invokeOnStopped = false,
        clearAutocanonizerAudio = clearAutocanonizerAudio
    )
}

internal fun stopTransportForModeChange(
    controller: PlaybackController,
    previousMode: PlaybackMode,
    targetMode: PlaybackMode,
    isRunning: Boolean,
    onStopped: (() -> Unit)? = null
): ModeTransportPlan {
    val plan = resolveModeTransportPlan(previousMode, targetMode, isRunning)
    if (plan.stopAllTransports) {
        stopAllPlaybackTransports(controller)
        if (plan.invokeOnStopped) {
            onStopped?.invoke()
        }
        return plan
    }

    if (plan.stopAutocanonizerWhileIdle) {
        controller.autocanonizer.stop()
        controller.stopExternalPlayback()
    }
    return plan
}

/**
 * Playback state with the play mode applied. The audio mode fields are left untouched:
 * they hold the user's jukebox setting, which autocanonizer mode ignores rather than
 * clears — the play title stays correct because its autocanonizer form takes precedence
 * over the audio-mode suffix.
 */
internal fun playbackStateAfterPlayModeApplied(
    playback: PlaybackState,
    mode: PlaybackMode
): PlaybackState {
    return playback.copy(
        playMode = mode,
        playTitle = buildPlayTitle(
            title = playback.trackTitle,
            artist = playback.trackArtist,
            playMode = mode,
            audioMode = playback.jukeboxAudioMode
        )
    )
}

internal fun playbackStateAfterModeChange(
    playback: PlaybackState,
    preserveTransportState: Boolean
): PlaybackState {
    if (preserveTransportState) {
        return playback.copy(
            autocanonizer = playback.autocanonizer.withResetCursorTimes()
        )
    }
    return playback.copy(
        isRunning = false,
        isPaused = false,
        beatsPlayed = 0,
        currentBeatIndex = -1,
        canonizerOtherIndex = null,
        lastJumpFromIndex = null,
        jumpLine = null,
        autocanonizer = playback.autocanonizer.withResetCursorTimes()
    )
}
