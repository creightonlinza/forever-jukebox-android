package com.foreverjukebox.app.ui

internal enum class PlaybackServiceSessionKind {
    Hidden,
    LocalLoading,
    LocalFailed,
    LocalReady,
    LocalPlaying,
    LocalPaused,
    Cast
}

internal sealed class PlaybackServiceSession {
    abstract val kind: PlaybackServiceSessionKind

    data object Hidden : PlaybackServiceSession() {
        override val kind: PlaybackServiceSessionKind = PlaybackServiceSessionKind.Hidden
    }

    data class LocalLoading(val progress: Int?) : PlaybackServiceSession() {
        override val kind: PlaybackServiceSessionKind = PlaybackServiceSessionKind.LocalLoading
    }

    data object LocalFailed : PlaybackServiceSession() {
        override val kind: PlaybackServiceSessionKind = PlaybackServiceSessionKind.LocalFailed
    }

    data object LocalReady : PlaybackServiceSession() {
        override val kind: PlaybackServiceSessionKind = PlaybackServiceSessionKind.LocalReady
    }

    data object LocalPlaying : PlaybackServiceSession() {
        override val kind: PlaybackServiceSessionKind = PlaybackServiceSessionKind.LocalPlaying
    }

    data object LocalPaused : PlaybackServiceSession() {
        override val kind: PlaybackServiceSessionKind = PlaybackServiceSessionKind.LocalPaused
    }

    data class Cast(
        val isPlaying: Boolean,
        val title: String?,
        val artist: String?,
        val deviceName: String?
    ) : PlaybackServiceSession() {
        override val kind: PlaybackServiceSessionKind = PlaybackServiceSessionKind.Cast
    }
}

internal data class PlaybackServiceSkipAvailability(
    val canSkipPrevious: Boolean,
    val canSkipNext: Boolean
)

/**
 * Resolves the playback service session for the full UI state, wiring in the
 * failed-notification visibility rule. This is the production entry point: keeping
 * the visibility predicate here (rather than at call sites) is what guarantees a
 * retryable failure resolves to [PlaybackServiceSession.LocalFailed] instead of
 * falling through to Hidden and tearing down the notification.
 */
internal fun resolvePlaybackServiceSession(state: UiState): PlaybackServiceSession {
    return resolvePlaybackServiceSession(
        playback = state.playback,
        keepFailedLoadVisible = shouldRetryFailedLoadFromTransport(state)
    )
}

internal fun resolvePlaybackServiceSession(
    playback: PlaybackState,
    keepFailedLoadVisible: Boolean = false
): PlaybackServiceSession {
    if (playback.shouldShowCastNotification()) {
        return PlaybackServiceSession.Cast(
            isPlaying = playback.isRunning,
            title = playback.castNotificationTitle(),
            artist = playback.trackArtist,
            deviceName = playback.castDeviceName
        )
    }
    if (playback.isCasting) {
        return PlaybackServiceSession.Hidden
    }
    if (playback.isLoading()) {
        return PlaybackServiceSession.LocalLoading(playback.analysisProgress)
    }
    // While audio is audibly running the transport must keep controlling it — the
    // failed surface drops Pause/Stop presses, so an error surfaced without stopping
    // playback waits for playback to end before taking over as a retry surface.
    // A paused or stopped track with an error goes to the failed surface, whose
    // press either resumes it from memory or retries the load.
    if (playback.isRunning) {
        return PlaybackServiceSession.LocalPlaying
    }
    if (keepFailedLoadVisible && !playback.analysisErrorMessage.isNullOrBlank()) {
        return PlaybackServiceSession.LocalFailed
    }
    if (playback.isPaused) {
        return PlaybackServiceSession.LocalPaused
    }
    if (playback.hasLoadedLocalSession()) {
        return PlaybackServiceSession.LocalReady
    }
    return PlaybackServiceSession.Hidden
}

internal fun resolvePlaybackServiceSkipAvailability(
    state: UiState
): PlaybackServiceSkipAvailability {
    val showPlaylistControls = !state.playback.isTrackLoading() &&
        shouldShowPlaylistControls(state.playlist)
    return PlaybackServiceSkipAvailability(
        canSkipPrevious = showPlaylistControls && state.playlist.canSkipPrevious(),
        canSkipNext = showPlaylistControls && state.playlist.canSkipNext()
    )
}

private fun PlaybackState.hasLoadedLocalSession(): Boolean {
    return audioLoaded &&
        analysisLoaded &&
        analysisErrorMessage.isNullOrBlank()
}
