package com.foreverjukebox.app.ui

import com.foreverjukebox.app.engine.JukeboxState
import com.foreverjukebox.app.visualization.JumpLine

enum class FavoriteToggleResult {
    Added,
    Removed,
    LimitReached,
    BlockedInFlight,
    NoTrack
}

internal fun hasRealFavoritesSyncPath(state: UiState): Boolean {
    return state.allowFavoritesSync && !state.favoritesSyncCode.isNullOrBlank()
}

internal fun shouldShowListenFavoriteSpinner(state: UiState): Boolean {
    return hasRealFavoritesSyncPath(state) && state.listenFavoriteToggleInFlight
}

internal fun shouldBlockListenFavoriteToggle(state: UiState): Boolean {
    return shouldShowListenFavoriteSpinner(state)
}

// Tuning has two sources of truth: the local engine when playing on-device, and the
// receiver-reported tuning state while casting (cast connect resets the local engine and
// cast tuning edits never reach it). Favorites and playlist entries must capture from
// whichever source is live, or tuned tracks favorited during a cast session save no tuning.
internal fun favoriteTuningParamsForCurrentTrack(
    state: UiState,
    engineTuningParams: () -> String?
): String? {
    val playback = state.playback
    if (playback.playMode != PlaybackMode.Jukebox) {
        return null
    }
    if (!playback.isCasting) {
        return engineTuningParams()
    }
    return TuningParamsCodec.buildSavedTuningParams(
        tuning = state.tuning,
        audioModeWireValue = playback.castAudioModeWireValue,
        audioModeIntensity = playback.castAudioModeIntensity
    )
}

internal fun playbackTransportContentDescription(playback: PlaybackState): String {
    return when {
        playback.isRunning -> "Pause"
        playback.isPaused -> "Resume"
        else -> "Play"
    }
}

internal fun jumpLineForEngineState(engineState: JukeboxState, startedAt: Long): JumpLine? {
    val jumpFrom = engineState.lastJumpFromIndex ?: return null
    if (!engineState.lastJumped) return null
    val jumpTo = engineState.lastJumpToIndex ?: engineState.currentBeatIndex
    return JumpLine(jumpFrom, jumpTo, startedAt)
}

// Shared by the persistent playback bar and the fullscreen bottom controls so
// both render identical now-playing data.
internal fun nowPlayingLine(playback: PlaybackState): String {
    val title = playback.trackTitle.orEmpty()
    val displayTitle = if (
        playback.playMode == PlaybackMode.Jukebox &&
        playback.jukeboxAudioMode != JukeboxAudioMode.Off &&
        title.isNotBlank()
    ) {
        "$title (${playback.jukeboxAudioMode.wireValue})"
    } else {
        title
    }
    val artist = playback.trackArtist.orEmpty()
    return when {
        displayTitle.isNotBlank() && artist.isNotBlank() -> "$displayTitle - $artist"
        displayTitle.isNotBlank() -> displayTitle
        else -> "Forever Jukebox"
    }
}

internal fun playbackSummaryLine(playback: PlaybackState): String? {
    // The cast receiver doesn't report listen time or beats played back to the sender, so these
    // local counters are stale while casting — hide the line entirely.
    if (playback.isCasting) {
        return null
    }
    return if (playback.playMode == PlaybackMode.Autocanonizer) {
        "Listen Time: ${playback.listenTime}"
    } else {
        "Listen Time: ${playback.listenTime} - Total Beats: ${playback.beatsPlayed}"
    }
}

internal fun shouldShowPlaybackBar(playback: PlaybackState): Boolean {
    return when (resolveListenContentMode(playback)) {
        ListenContentMode.LocalReady -> true
        ListenContentMode.Cast -> playback.hasCastTrack()
        ListenContentMode.Empty, ListenContentMode.None -> false
    }
}
