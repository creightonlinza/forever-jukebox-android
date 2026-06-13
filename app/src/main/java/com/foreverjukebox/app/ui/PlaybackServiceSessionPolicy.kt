package com.foreverjukebox.app.ui

internal enum class PlaybackServiceSyncReason {
    StateChanged,
    ProgressTick,
    HardStop
}

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
    if (keepFailedLoadVisible && !playback.analysisErrorMessage.isNullOrBlank()) {
        return PlaybackServiceSession.LocalFailed
    }
    if (playback.isRunning) {
        return PlaybackServiceSession.LocalPlaying
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
