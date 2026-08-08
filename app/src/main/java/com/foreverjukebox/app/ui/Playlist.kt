package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.data.FavoritePlayMode
import com.foreverjukebox.app.data.SavedPlaylistTrack
import com.foreverjukebox.app.data.SavedPlaylistTrackType
import com.foreverjukebox.app.data.canonicalTrackId

internal const val MAX_PLAYLIST_TRACKS = 10

enum class PlaylistTrackType {
    Server,
    LocalCached
}

data class PlaylistTrack(
    val id: String,
    val type: PlaylistTrackType,
    val title: String?,
    val artist: String?,
    val tuningParams: String? = null,
    // Play mode the track should load in; null is the implicit jukebox default.
    val playMode: FavoritePlayMode? = null
)

data class JukeboxPlaylistState(
    val tracks: List<PlaylistTrack> = emptyList(),
    val currentIndex: Int = -1
)

/** Identifies the loaded track as a playlist entry key. */
internal data class PlaylistTrackIdentity(
    val id: String,
    val type: PlaylistTrackType
)

/**
 * The loaded track as a playlist key, or null while nothing is playable yet.
 *
 * Settings captured after load are matched against this, so a capture that lands mid
 * track-switch — where the playlist has already moved on but playback still reports the
 * outgoing track — resolves to a key that no longer matches and is dropped.
 */
internal fun loadedPlaylistTrackIdentityOrNull(state: UiState): PlaylistTrackIdentity? {
    val playback = state.playback
    val hasLoadedTrack = (playback.audioLoaded && playback.analysisLoaded) || playback.hasCastTrack()
    if (!hasLoadedTrack) return null
    val trackId = playback.shareTrackIdOrNull() ?: return null
    return PlaylistTrackIdentity(
        id = canonicalTrackId(trackId) ?: trackId.trim(),
        type = if (state.appMode == AppMode.Local) {
            PlaylistTrackType.LocalCached
        } else {
            PlaylistTrackType.Server
        }
    )
}

internal fun JukeboxPlaylistState.isInitialized(): Boolean = tracks.isNotEmpty()

internal fun JukeboxPlaylistState.isActive(): Boolean {
    return currentIndex in tracks.indices
}

internal fun JukeboxPlaylistState.isInactiveSavedPlaylist(): Boolean {
    return tracks.isNotEmpty() && currentIndex == -1
}

internal fun JukeboxPlaylistState.currentTrack(): PlaylistTrack? {
    return tracks.getOrNull(currentIndex)
}

internal fun JukeboxPlaylistState.indexOfTrack(track: PlaylistTrack): Int {
    return tracks.indexOfFirst { it.playlistKey == track.playlistKey }
}

internal fun JukeboxPlaylistState.containsTrack(track: PlaylistTrack): Boolean {
    return indexOfTrack(track) >= 0
}

internal fun initializePlaylist(
    current: PlaylistTrack,
    next: PlaylistTrack
): JukeboxPlaylistState {
    if (current.playlistKey == next.playlistKey) {
        return JukeboxPlaylistState(tracks = listOf(current), currentIndex = 0)
    }
    return JukeboxPlaylistState(tracks = listOf(current, next), currentIndex = 0)
}

internal fun JukeboxPlaylistState.appendTrack(track: PlaylistTrack): JukeboxPlaylistState {
    if (!isInitialized() || containsTrack(track) || tracks.size >= MAX_PLAYLIST_TRACKS) {
        return this
    }
    return copy(tracks = tracks + track)
}

internal fun JukeboxPlaylistState.replaceCurrentTrackWith(track: PlaylistTrack): JukeboxPlaylistState {
    if (!isActive()) {
        return this
    }
    val existingIndex = indexOfTrack(track)
    if (existingIndex == currentIndex) {
        val mergedTrack = tracks[currentIndex].withMetadataFrom(track)
        if (mergedTrack == tracks[currentIndex]) {
            return this
        }
        val nextTracks = tracks.toMutableList()
        nextTracks[currentIndex] = mergedTrack
        return copy(tracks = nextTracks)
    }
    if (existingIndex >= 0) {
        val existingTrack = tracks[existingIndex]
        val nextTracks = tracks.toMutableList()
        nextTracks[currentIndex] = existingTrack
        nextTracks.removeAt(existingIndex)
        val nextCurrentIndex = if (existingIndex < currentIndex) {
            currentIndex - 1
        } else {
            currentIndex
        }.coerceIn(nextTracks.indices)
        return copy(tracks = nextTracks, currentIndex = nextCurrentIndex)
    }
    val nextTracks = tracks.toMutableList()
    nextTracks[currentIndex] = track
    return copy(tracks = nextTracks)
}

/**
 * Writes tuning and play mode onto the current entry when it is still the loaded track.
 *
 * Unlike [replaceCurrentTrackWith] this sets both fields outright, so a tuning reset clears
 * the entry and a switch back to jukebox demotes an autocanonizer entry — neither of which
 * the metadata merge can express.
 */
internal fun JukeboxPlaylistState.withCurrentTrackSettings(
    trackId: String,
    type: PlaylistTrackType,
    tuningParams: String?,
    playMode: FavoritePlayMode?
): JukeboxPlaylistState {
    val current = currentTrack() ?: return this
    if (current.playlistKey != playlistKeyOf(type, trackId)) {
        return this
    }
    val normalizedTuningParams = tuningParams.takeIfNotBlank()
    if (current.tuningParams == normalizedTuningParams && current.playMode == playMode) {
        return this
    }
    val nextTracks = tracks.toMutableList()
    nextTracks[currentIndex] = current.copy(
        tuningParams = normalizedTuningParams,
        playMode = playMode
    )
    return copy(tracks = nextTracks)
}

internal fun JukeboxPlaylistState.selectTrackAt(index: Int): JukeboxPlaylistState {
    if (index !in tracks.indices) {
        return this
    }
    return copy(currentIndex = index)
}

internal fun JukeboxPlaylistState.deactivate(): JukeboxPlaylistState {
    if (tracks.isEmpty()) {
        return this
    }
    return copy(currentIndex = -1)
}

internal fun JukeboxPlaylistState.canRemoveTrackAt(index: Int): Boolean {
    return index in tracks.indices && index != currentIndex
}

internal fun JukeboxPlaylistState.canSelectTrackAt(index: Int): Boolean {
    return index in tracks.indices && index != currentIndex
}

internal fun JukeboxPlaylistState.removeTrackAt(index: Int): JukeboxPlaylistState {
    if (!canRemoveTrackAt(index)) {
        return this
    }
    val nextTracks = tracks.toMutableList().apply { removeAt(index) }
    if (nextTracks.size <= 1) {
        return JukeboxPlaylistState()
    }
    val nextCurrentIndex = if (index < currentIndex) {
        currentIndex - 1
    } else {
        currentIndex
    }
    return copy(tracks = nextTracks, currentIndex = nextCurrentIndex)
}

internal fun JukeboxPlaylistState.canSkipPrevious(): Boolean = currentIndex > 0

internal fun JukeboxPlaylistState.canSkipNext(): Boolean {
    return currentIndex >= 0 && currentIndex < tracks.lastIndex
}

internal fun shouldShowPlaylistControls(playlist: JukeboxPlaylistState): Boolean {
    return playlist.tracks.size > 1
}

internal fun shouldShowActivePlaylistControls(playlist: JukeboxPlaylistState): Boolean {
    return shouldShowPlaylistControls(playlist) && playlist.isActive()
}

internal fun shouldAdvancePlaylistOnAutocanonizerEnd(state: UiState): Boolean {
    return state.playback.playMode == PlaybackMode.Autocanonizer &&
        state.playlist.canSkipNext()
}

internal fun shouldShowSavedPlaylistButton(state: UiState): Boolean {
    return resolveListenContentMode(state.playback) == ListenContentMode.Empty &&
        state.playlist.isInactiveSavedPlaylist() &&
        shouldShowPlaylistControls(state.playlist)
}

internal fun PlaylistTrack.toSavedPlaylistTrack(): SavedPlaylistTrack {
    return SavedPlaylistTrack(
        id = id,
        type = when (type) {
            PlaylistTrackType.Server -> SavedPlaylistTrackType.Server
            PlaylistTrackType.LocalCached -> SavedPlaylistTrackType.LocalCached
        },
        title = title,
        artist = artist,
        tuningParams = tuningParams,
        playMode = playMode
    )
}

internal fun SavedPlaylistTrack.toPlaylistTrack(): PlaylistTrack? {
    val normalizedId = id.trim()
    if (normalizedId.isBlank()) return null
    return PlaylistTrack(
        id = normalizedId,
        type = when (type) {
            SavedPlaylistTrackType.Server -> PlaylistTrackType.Server
            SavedPlaylistTrackType.LocalCached -> PlaylistTrackType.LocalCached
        },
        title = title,
        artist = artist,
        tuningParams = tuningParams,
        playMode = playMode
    )
}

/**
 * Builds a [PlaylistTrack] of [PlaylistTrackType.Server] from an arbitrary track id.
 *
 * Uses [canonicalTrackId] (not the narrower job-id check) so that ids sourced from
 * favorites — which may be YouTube source ids rather than 32-char job ids — are
 * accepted. Returns null only when the id can't be parsed as any known track id.
 */
internal fun serverPlaylistTrack(
    trackId: String,
    title: String?,
    artist: String?,
    tuningParams: String?,
    playMode: FavoritePlayMode? = null
): PlaylistTrack? {
    val canonical = canonicalTrackId(trackId) ?: return null
    return PlaylistTrack(
        id = canonical,
        type = PlaylistTrackType.Server,
        title = title.takeIfNotBlank(),
        artist = artist.takeIfNotBlank(),
        tuningParams = tuningParams?.takeIf { it.isNotBlank() },
        playMode = playMode
    )
}

internal fun playablePlaylistTracks(
    tracks: List<PlaylistTrack>,
    appMode: AppMode?,
    localCachedTracks: List<LocalCachedTrack>
): List<PlaylistTrack> {
    return when (appMode) {
        AppMode.Server -> tracks.filter { it.type == PlaylistTrackType.Server }
        AppMode.Local -> {
            val cachedIds = localCachedTracks.mapTo(mutableSetOf()) { it.localId }
            tracks.filter { it.type == PlaylistTrackType.LocalCached && it.id in cachedIds }
        }
        null -> emptyList()
    }
}

private fun playlistKeyOf(type: PlaylistTrackType, id: String): String = "${type.name}:${id.trim()}"

private val PlaylistTrack.playlistKey: String
    get() = playlistKeyOf(type, id)

private fun PlaylistTrack.withMetadataFrom(incoming: PlaylistTrack): PlaylistTrack {
    return copy(
        title = incoming.title.takeIfNotBlank() ?: title,
        artist = incoming.artist.takeIfNotBlank() ?: artist,
        tuningParams = incoming.tuningParams?.takeIf { it.isNotBlank() } ?: tuningParams,
        playMode = incoming.playMode ?: playMode
    )
}

private fun String?.takeIfNotBlank(): String? = this?.trim()?.takeIf { it.isNotBlank() }
