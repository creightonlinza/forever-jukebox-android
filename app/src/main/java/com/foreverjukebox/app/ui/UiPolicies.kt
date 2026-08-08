package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.FavoriteTrack
import com.foreverjukebox.app.data.canonicalTrackId
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
// cast tuning edits never reach it). Favorites, playlist entries, and share URLs must
// capture from whichever source is live, or tuning captured during a cast session is stale.
internal fun tuningParamsForCurrentTrack(
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

internal fun favoriteForCurrentTrack(state: UiState): FavoriteTrack? {
    val trackIds = state.playback.reusableTrackIdsForMatching()
    if (trackIds.isEmpty()) {
        return null
    }
    return state.favorites.firstOrNull { canonicalTrackId(it.uniqueSongId) in trackIds }
}

// Tuning as it stands right now, from whichever source is live. Unlike the capture path this
// reads no engine state, so it can be recomputed on every frame; the engine's deleted branches
// and anchor branch are left out, and the comparison ignores them to match.
internal fun liveTuningParams(state: UiState): String? {
    val playback = state.playback
    if (playback.playMode != PlaybackMode.Jukebox) {
        return null
    }
    return TuningParamsCodec.buildSavedTuningParams(
        tuning = state.tuning,
        audioModeWireValue = if (playback.isCasting) {
            playback.castAudioModeWireValue
        } else {
            playback.jukeboxAudioMode.wireValue
        },
        audioModeIntensity = if (playback.isCasting) {
            playback.castAudioModeIntensity
        } else {
            playback.jukeboxAudioModeIntensity
        }
    )
}

// A favorite stores the tuning and play mode captured when the star was set. The star carries a
// drift marker while the live track no longer matches that snapshot: restoring the saved settings
// clears it, and so does unfavoriting, which discards the stored tuning.
internal fun hasFavoriteTuningDrift(state: UiState, favorite: FavoriteTrack?): Boolean {
    if (favorite == null) {
        return false
    }
    val playback = state.playback
    // The receiver owns tuning while casting, and until it reports a status for the loaded track
    // the mirrored tuning state is the engine reset's default, which reads as drift.
    if (playback.isCasting && playback.castTotalBeats == null) {
        return false
    }
    if (favorite.playMode.toPlaybackMode() != playback.playMode) {
        return true
    }
    return !TuningParamsCodec.savedTuningParamsEquivalent(
        liveTuningParams(state),
        favorite.tuningParams,
        computedThreshold = state.tuning.computedThreshold
    )
}

internal fun favoriteActionContentDescription(
    isFavorite: Boolean,
    hasTuningDrift: Boolean
): String {
    return when {
        !isFavorite -> "Add favorite"
        hasTuningDrift -> "Remove favorite, tuning differs from the saved tuning"
        else -> "Remove favorite"
    }
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
