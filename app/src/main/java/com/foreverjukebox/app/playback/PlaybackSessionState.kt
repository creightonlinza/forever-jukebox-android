package com.foreverjukebox.app.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the media session and its notification present, as resolved from the UI state. */
internal sealed interface PlaybackSessionSurface {
    /** Nothing to present: the session carries no media item and the service can stop. */
    data object Hidden : PlaybackSessionSurface

    /** A track, swing render or remix is being prepared; [progressBucket] is a 10% step. */
    data class Loading(val progressBucket: Int?) : PlaybackSessionSurface

    /** The last load failed; a play press asks the app to retry it. */
    data object LoadFailed : PlaybackSessionSurface

    /** A loaded local track. Playing or paused is read from the [PlaybackController]. */
    data object Local : PlaybackSessionSurface

    /** Playback runs on a cast receiver; transport presses are forwarded to it. */
    data class Cast(
        val isPlaying: Boolean,
        val title: String?,
        val artist: String?
    ) : PlaybackSessionSurface
}

internal data class PlaybackSessionSnapshot(
    val surface: PlaybackSessionSurface = PlaybackSessionSurface.Hidden,
    val canSkipPrevious: Boolean = false,
    val canSkipNext: Boolean = false
)

/**
 * Process-wide hand-off from the UI layer to the media session service. Every publish
 * emits, even an unchanged snapshot, because the session also re-reads controller state
 * (title, audio mode, play state) that the snapshot does not carry.
 */
internal object PlaybackSessionState {
    internal data class Published(
        val snapshot: PlaybackSessionSnapshot,
        val revision: Long
    )

    private val _published = MutableStateFlow(Published(PlaybackSessionSnapshot(), 0L))
    val published: StateFlow<Published> = _published

    val snapshot: PlaybackSessionSnapshot
        get() = _published.value.snapshot

    fun publish(snapshot: PlaybackSessionSnapshot) {
        _published.update { current -> Published(snapshot, current.revision + 1) }
    }
}
