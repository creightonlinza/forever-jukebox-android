package com.foreverjukebox.app.ui

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
    if (!isInitialized() || containsTrack(track)) {
        return this
    }
    return copy(tracks = tracks + track)
}

internal fun JukeboxPlaylistState.appendAndSelectTrack(track: PlaylistTrack): JukeboxPlaylistState {
    val existingIndex = indexOfTrack(track)
    if (existingIndex >= 0) {
        return copy(currentIndex = existingIndex)
    }
    if (!isInitialized()) {
        return this
    }
    return copy(tracks = tracks + track, currentIndex = tracks.size)
}

internal fun JukeboxPlaylistState.selectTrackAt(index: Int): JukeboxPlaylistState {
    if (index !in tracks.indices) {
        return this
    }
    return copy(currentIndex = index)
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

private val PlaylistTrack.playlistKey: String
    get() = "${type.name}:${id.trim()}"
