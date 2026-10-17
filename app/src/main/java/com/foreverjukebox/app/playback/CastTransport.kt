package com.foreverjukebox.app.playback

import com.foreverjukebox.app.ui.CastController

/** The app's custom message namespace on the relay cast receiver. */
internal const val CAST_COMMAND_NAMESPACE = "urn:x-cast:com.foreverjukebox.app"

/** Sends a play/pause/stop command to the receiver; false when no session could take it. */
internal fun CastController.sendTransportCommand(command: String): Boolean {
    return sendCommand(CAST_COMMAND_NAMESPACE, command)
}
