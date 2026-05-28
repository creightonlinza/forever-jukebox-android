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
