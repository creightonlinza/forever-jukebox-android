package com.foreverjukebox.app.ui

import com.foreverjukebox.app.playback.PlaybackController

internal data class ModeTransportPlan(
    val stopAllTransports: Boolean,
    val stopAutocanonizerWhileIdle: Boolean,
    val invokeOnStopped: Boolean
)

internal fun resolveModeTransportPlan(
    previousMode: PlaybackMode,
    isRunning: Boolean
): ModeTransportPlan {
    if (isRunning) {
        return ModeTransportPlan(
            stopAllTransports = true,
            stopAutocanonizerWhileIdle = false,
            invokeOnStopped = true
        )
    }
    return ModeTransportPlan(
        stopAllTransports = false,
        stopAutocanonizerWhileIdle = previousMode == PlaybackMode.Autocanonizer,
        invokeOnStopped = false
    )
}

internal fun stopTransportForModeChange(
    controller: PlaybackController,
    previousMode: PlaybackMode,
    isRunning: Boolean,
    onStopped: (() -> Unit)? = null
) {
    val plan = resolveModeTransportPlan(previousMode, isRunning)
    if (plan.stopAllTransports) {
        stopAllPlaybackTransports(controller)
        if (plan.invokeOnStopped) {
            onStopped?.invoke()
        }
        return
    }

    if (plan.stopAutocanonizerWhileIdle) {
        controller.autocanonizer.stop()
        controller.stopExternalPlayback()
    }
}

internal fun playbackStateAfterModeChange(
    playback: PlaybackState,
    preserveTransportState: Boolean
): PlaybackState {
    if (preserveTransportState) {
        return playback
    }
    return playback.copy(
        isRunning = false,
        isPaused = false,
        beatsPlayed = 0,
        currentBeatIndex = -1,
        canonizerOtherIndex = null,
        lastJumpFromIndex = null,
        jumpLine = null
    )
}
