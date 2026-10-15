package com.foreverjukebox.app.playback

import androidx.media3.common.Player
import com.foreverjukebox.app.ui.JukeboxAudioMode

internal const val DEFAULT_NOTIFICATION_TITLE = "Forever Jukebox"
internal const val LOADING_NOTIFICATION_TITLE = "Loading"
internal const val LOAD_FAILED_NOTIFICATION_TITLE = "Loading Failed"

internal fun loadingNotificationProgressBucket(progress: Int?): Int? {
    val safeProgress = progress?.coerceIn(0, 100) ?: return null
    val bucket = (safeProgress / 10) * 10
    return bucket.takeIf { it >= 10 }
}

internal fun loadingNotificationTitle(progressBucket: Int?): String {
    return progressBucket?.let { "Loading - $it%" } ?: LOADING_NOTIFICATION_TITLE
}

internal fun localPlaybackNotificationTitle(
    baseTitle: String?,
    audioMode: JukeboxAudioMode,
    isLoading: Boolean,
    loadingProgressBucket: Int?,
    isLoadFailed: Boolean = false
): String {
    if (isLoadFailed) {
        return LOAD_FAILED_NOTIFICATION_TITLE
    }
    if (isLoading) {
        return loadingNotificationTitle(loadingProgressBucket)
    }
    val title = baseTitle.orEmpty()
    return when {
        title.isBlank() -> DEFAULT_NOTIFICATION_TITLE
        audioMode != JukeboxAudioMode.Off -> "$title (${audioMode.wireValue})"
        else -> title
    }
}

internal fun localPlaybackNotificationArtist(
    baseArtist: String?,
    isLoading: Boolean,
    isLoadFailed: Boolean = false
): String {
    return if (isLoading || isLoadFailed) {
        DEFAULT_NOTIFICATION_TITLE
    } else {
        baseArtist.orEmpty()
    }
}

/** The controller's view of the loaded local track, read when the session state is built. */
internal data class LocalPlaybackStatus(
    val isPlaying: Boolean,
    val isPaused: Boolean,
    /** A play request is parked until the system grants delayed audio focus. */
    val isWaitingForFocus: Boolean,
    val title: String?,
    val artist: String?,
    val audioMode: JukeboxAudioMode,
    val durationMs: Long?
)

/**
 * What the session layers over the published snapshot after its own presses.
 *
 * [castPlaying] is the play state a forwarded cast press asked for, shown until the
 * receiver's reported state changes or catches up. [castUnreachable] hides the session
 * until the next snapshot. [stopped] marks a stop pressed through the session (dismissing
 * the notification arrives as one): the surface reports idle, which keeps a dismissed
 * notification dismissed, until playback starts again or the surface changes.
 */
internal data class SessionOverride(
    val castPlaying: Boolean? = null,
    val castUnreachable: Boolean = false,
    val stopped: Boolean = false
)

/** Folds a press's [result] into the override; only a play clears a stop. */
internal fun SessionOverride.afterCommand(
    action: PlaybackAction,
    result: SessionOverride
): SessionOverride {
    val stillStopped = stopped && action != PlaybackAction.Play
    return result.copy(stopped = result.stopped || stillStopped)
}

/**
 * What survives a newly published snapshot. [surfaceChanged] is true when the kind of
 * surface changed since [previous] was published, including through snapshots the
 * service never saw.
 */
internal fun SessionOverride.afterSnapshot(
    previous: PlaybackSessionSurface,
    surface: PlaybackSessionSurface,
    surfaceChanged: Boolean
): SessionOverride {
    val keepCastPlaying = castPlaying != null &&
        !surfaceChanged &&
        previous is PlaybackSessionSurface.Cast &&
        surface is PlaybackSessionSurface.Cast &&
        surface.isPlaying == previous.isPlaying &&
        surface.isPlaying != castPlaying
    val keepStopped = stopped && !surfaceChanged && when (surface) {
        is PlaybackSessionSurface.Cast -> !surface.isPlaying
        PlaybackSessionSurface.Local,
        PlaybackSessionSurface.LoadFailed -> true
        PlaybackSessionSurface.Hidden,
        is PlaybackSessionSurface.Loading -> false
    }
    return SessionOverride(
        castPlaying = castPlaying.takeIf { keepCastPlaying },
        stopped = keepStopped
    )
}

/** A local track that started playing outside the session is no longer stopped. */
internal fun SessionOverride.afterLocalStatus(
    surface: PlaybackSessionSurface,
    local: LocalPlaybackStatus
): SessionOverride {
    val playingLocally = surface == PlaybackSessionSurface.Local &&
        (local.isPlaying || local.isWaitingForFocus)
    return if (stopped && playingLocally) copy(stopped = false) else this
}

/**
 * Everything the media session reports, as plain values. [playbackState] is a
 * [Player.State] and [commands] are [Player.Command] codes. [suppressedByAudioFocus]
 * means play-when-ready is set but playback is held until audio focus is granted.
 */
internal data class JukeboxSessionSpec(
    val hasMediaItem: Boolean,
    val playbackState: Int,
    val playWhenReady: Boolean,
    val suppressedByAudioFocus: Boolean,
    val title: String,
    val artist: String,
    val durationMs: Long?,
    val showsArtwork: Boolean,
    val reportsPosition: Boolean,
    val commands: Set<Int>
) {
    /** True while the reported position moves on its own. */
    val positionAdvances: Boolean
        get() = reportsPosition && playWhenReady && !suppressedByAudioFocus &&
            playbackState == Player.STATE_READY
}

private val METADATA_COMMANDS = setOf(
    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
    Player.COMMAND_GET_TIMELINE,
    Player.COMMAND_GET_METADATA,
    Player.COMMAND_RELEASE
)

private val HIDDEN_SPEC = JukeboxSessionSpec(
    hasMediaItem = false,
    playbackState = Player.STATE_IDLE,
    playWhenReady = false,
    suppressedByAudioFocus = false,
    title = DEFAULT_NOTIFICATION_TITLE,
    artist = "",
    durationMs = null,
    showsArtwork = false,
    reportsPosition = false,
    commands = setOf(Player.COMMAND_RELEASE)
)

private fun transportCommands(snapshot: PlaybackSessionSnapshot): Set<Int> {
    val commands = METADATA_COMMANDS + Player.COMMAND_PLAY_PAUSE + Player.COMMAND_STOP
    return commands +
        listOfNotNull(
            Player.COMMAND_SEEK_TO_PREVIOUS.takeIf { snapshot.canSkipPrevious },
            Player.COMMAND_SEEK_TO_NEXT.takeIf { snapshot.canSkipNext }
        )
}

private fun readyOrStopped(stopped: Boolean): Int {
    return if (stopped) Player.STATE_IDLE else Player.STATE_READY
}

/**
 * Maps the published snapshot and the controller's current state onto the session.
 *
 * Loading reports buffering with play-when-ready so the system draws its spinner in the
 * play slot and Media3 keeps the service in the foreground while a screen-off skip loads.
 * It exposes no transport commands, matching a notification with no buttons. A failed load
 * reports a paused track whose play press retries it. A play parked on delayed audio focus
 * reports play-when-ready held by focus, so Media3 keeps the foreground through the wait.
 *
 * A surface stopped through the session reports idle (see [SessionOverride.stopped]). The
 * service shows idle sessions, so a plain stop keeps the notification; only a dismissal,
 * which Media3 remembers until the session leaves idle, keeps it away.
 */
internal fun resolveJukeboxSessionSpec(
    snapshot: PlaybackSessionSnapshot,
    local: LocalPlaybackStatus,
    override: SessionOverride = SessionOverride()
): JukeboxSessionSpec {
    return when (val surface = snapshot.surface) {
        PlaybackSessionSurface.Hidden -> HIDDEN_SPEC
        is PlaybackSessionSurface.Loading -> JukeboxSessionSpec(
            hasMediaItem = true,
            playbackState = Player.STATE_BUFFERING,
            playWhenReady = true,
            suppressedByAudioFocus = false,
            title = loadingNotificationTitle(surface.progressBucket),
            artist = DEFAULT_NOTIFICATION_TITLE,
            durationMs = null,
            showsArtwork = true,
            reportsPosition = false,
            commands = METADATA_COMMANDS
        )
        PlaybackSessionSurface.LoadFailed -> JukeboxSessionSpec(
            hasMediaItem = true,
            playbackState = readyOrStopped(override.stopped),
            playWhenReady = false,
            suppressedByAudioFocus = false,
            title = LOAD_FAILED_NOTIFICATION_TITLE,
            artist = DEFAULT_NOTIFICATION_TITLE,
            durationMs = null,
            showsArtwork = true,
            reportsPosition = false,
            commands = METADATA_COMMANDS + Player.COMMAND_PLAY_PAUSE + Player.COMMAND_STOP
        )
        PlaybackSessionSurface.Local -> {
            val waitingForFocus = local.isWaitingForFocus && !local.isPlaying
            val playWhenReady = local.isPlaying || waitingForFocus
            JukeboxSessionSpec(
                hasMediaItem = true,
                playbackState = readyOrStopped(override.stopped && !playWhenReady),
                playWhenReady = playWhenReady,
                suppressedByAudioFocus = waitingForFocus,
                title = localPlaybackNotificationTitle(
                    baseTitle = local.title,
                    audioMode = local.audioMode,
                    isLoading = false,
                    loadingProgressBucket = null
                ),
                artist = localPlaybackNotificationArtist(baseArtist = local.artist, isLoading = false),
                durationMs = local.durationMs?.takeIf { it > 0L },
                showsArtwork = true,
                reportsPosition = true,
                commands = transportCommands(snapshot)
            )
        }
        is PlaybackSessionSurface.Cast -> if (override.castUnreachable) {
            HIDDEN_SPEC
        } else {
            val isPlaying = override.castPlaying ?: surface.isPlaying
            JukeboxSessionSpec(
                hasMediaItem = true,
                playbackState = readyOrStopped(override.stopped && !isPlaying),
                playWhenReady = isPlaying,
                suppressedByAudioFocus = false,
                title = surface.title?.takeIf { it.isNotBlank() } ?: DEFAULT_NOTIFICATION_TITLE,
                artist = surface.artist.orEmpty(),
                durationMs = null,
                showsArtwork = false,
                reportsPosition = false,
                commands = transportCommands(snapshot)
            )
        }
    }
}
