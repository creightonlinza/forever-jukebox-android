package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.data.SavedPlaylistTrack
import com.foreverjukebox.app.data.SavedPlaylistTrackType

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
    val tuningParams: String? = null
)

data class JukeboxPlaylistState(
    val tracks: List<PlaylistTrack> = emptyList(),
    val currentIndex: Int = -1
)

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
        return this
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

internal fun shouldShowSavedPlaylistButton(state: UiState): Boolean {
    return resolveListenContentMode(state.playback) == ListenContentMode.Empty &&
        state.playback.playMode == PlaybackMode.Jukebox &&
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
        tuningParams = tuningParams
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
        tuningParams = tuningParams
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

private val PlaylistTrack.playlistKey: String
    get() = "${type.name}:${id.trim()}"
