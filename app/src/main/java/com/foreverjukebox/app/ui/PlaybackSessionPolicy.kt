package com.foreverjukebox.app.ui

import com.foreverjukebox.app.playback.PlaybackSessionSnapshot
import com.foreverjukebox.app.playback.PlaybackSessionSurface
import com.foreverjukebox.app.playback.loadingNotificationProgressBucket

/**
 * Resolves what the media session presents for the full UI state, wiring in the
 * failed-notification visibility rule. This is the production entry point: keeping
 * the visibility predicate here (rather than at call sites) is what guarantees a
 * retryable failure resolves to [PlaybackSessionSurface.LoadFailed] instead of
 * falling through to Hidden and tearing down the notification.
 */
internal fun resolvePlaybackSessionSnapshot(state: UiState): PlaybackSessionSnapshot {
    val showPlaylistControls = !state.playback.isTrackLoading() &&
        shouldShowPlaylistControls(state.playlist)
    return PlaybackSessionSnapshot(
        surface = resolvePlaybackSessionSurface(
            playback = state.playback,
            keepFailedLoadVisible = shouldRetryFailedLoadFromTransport(state)
        ),
        canSkipPrevious = showPlaylistControls && state.playlist.canSkipPrevious(),
        canSkipNext = showPlaylistControls && state.playlist.canSkipNext()
    )
}

internal fun resolvePlaybackSessionSurface(
    playback: PlaybackState,
    keepFailedLoadVisible: Boolean = false
): PlaybackSessionSurface {
    if (playback.shouldShowCastNotification()) {
        return PlaybackSessionSurface.Cast(
            isPlaying = playback.isRunning,
            title = playback.castNotificationTitle(),
            artist = playback.trackArtist
        )
    }
    if (playback.isCasting) {
        return PlaybackSessionSurface.Hidden
    }
    if (playback.isLoading()) {
        return PlaybackSessionSurface.Loading(loadingNotificationProgressBucket(playback.analysisProgress))
    }
    if (playback.isPreparingSwing()) {
        return PlaybackSessionSurface.Loading(loadingNotificationProgressBucket(playback.swingProgress))
    }
    if (playback.isPreparingWubMachine()) {
        return PlaybackSessionSurface.Loading(loadingNotificationProgressBucket(playback.wubProgress))
    }
    // While audio is audibly running the transport must keep controlling it — the
    // failed surface has no pause, so an error surfaced without stopping playback
    // waits for playback to end before taking over as a retry surface. A paused or
    // stopped track with an error goes to the failed surface, whose press either
    // resumes it from memory or retries the load.
    if (playback.isRunning) {
        return PlaybackSessionSurface.Local
    }
    if (keepFailedLoadVisible && !playback.analysisErrorMessage.isNullOrBlank()) {
        return PlaybackSessionSurface.LoadFailed
    }
    if (playback.isPaused || playback.hasLoadedLocalSession()) {
        return PlaybackSessionSurface.Local
    }
    return PlaybackSessionSurface.Hidden
}

private fun PlaybackState.hasLoadedLocalSession(): Boolean {
    return audioLoaded &&
        analysisLoaded &&
        analysisErrorMessage.isNullOrBlank()
}
