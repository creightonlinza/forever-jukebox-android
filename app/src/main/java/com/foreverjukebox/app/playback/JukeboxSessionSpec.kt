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
    val title: String?,
    val artist: String?,
    val audioMode: JukeboxAudioMode,
    val durationMs: Long?
)

/**
 * What the session applies on top of a cast snapshot after forwarding a press. [isPlaying]
 * and [unreachable] last until the next snapshot arrives; [stopped] lasts until the
 * receiver plays again, so a notification dismissed with a stop stays dismissed while
 * later snapshots report the receiver idle.
 */
internal data class CastCommandOverride(
    val isPlaying: Boolean? = null,
    val unreachable: Boolean = false,
    val stopped: Boolean = false
)

/** Folds a forwarded press's [result] into the override; only a play clears a stop. */
internal fun CastCommandOverride.afterCommand(
    action: PlaybackAction,
    result: CastCommandOverride
): CastCommandOverride {
    val stillStopped = stopped && action != PlaybackAction.Play
    return result.copy(stopped = result.stopped || stillStopped)
}

/** What survives a newly published snapshot: a stop, while the receiver stays idle. */
internal fun CastCommandOverride.afterSnapshot(
    surface: PlaybackSessionSurface
): CastCommandOverride {
    val receiverIdle = surface is PlaybackSessionSurface.Cast && !surface.isPlaying
    return CastCommandOverride(stopped = stopped && receiverIdle)
}

/**
 * Everything the media session reports, as plain values. [playbackState] is a
 * [Player.State] and [commands] are [Player.Command] codes.
 */
internal data class JukeboxSessionSpec(
    val hasMediaItem: Boolean,
    val playbackState: Int,
    val playWhenReady: Boolean,
    val title: String,
    val artist: String,
    val durationMs: Long?,
    val showsArtwork: Boolean,
    val reportsPosition: Boolean,
    val commands: Set<Int>
)

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

/**
 * Maps the published snapshot and the controller's current state onto the session.
 *
 * Loading reports buffering with play-when-ready so the system draws its spinner in the
 * play slot and Media3 keeps the service in the foreground while a screen-off skip loads.
 * It exposes no transport commands, matching a notification with no buttons. A failed load
 * reports a paused track whose play press retries it.
 *
 * A stopped track (loaded but never started, or stopped) reports idle. The service shows
 * idle sessions too, so this only matters once the user dismisses the notification:
 * dismissal arrives as a stop, and an idle session keeps Media3 from re-posting it.
 */
internal fun resolveJukeboxSessionSpec(
    snapshot: PlaybackSessionSnapshot,
    local: LocalPlaybackStatus,
    castOverride: CastCommandOverride = CastCommandOverride()
): JukeboxSessionSpec {
    return when (val surface = snapshot.surface) {
        PlaybackSessionSurface.Hidden -> HIDDEN_SPEC
        is PlaybackSessionSurface.Loading -> JukeboxSessionSpec(
            hasMediaItem = true,
            playbackState = Player.STATE_BUFFERING,
            playWhenReady = true,
            title = loadingNotificationTitle(surface.progressBucket),
            artist = DEFAULT_NOTIFICATION_TITLE,
            durationMs = null,
            showsArtwork = true,
            reportsPosition = false,
            commands = METADATA_COMMANDS
        )
        PlaybackSessionSurface.LoadFailed -> JukeboxSessionSpec(
            hasMediaItem = true,
            playbackState = Player.STATE_READY,
            playWhenReady = false,
            title = LOAD_FAILED_NOTIFICATION_TITLE,
            artist = DEFAULT_NOTIFICATION_TITLE,
            durationMs = null,
            showsArtwork = true,
            reportsPosition = false,
            commands = METADATA_COMMANDS + Player.COMMAND_PLAY_PAUSE
        )
        PlaybackSessionSurface.Local -> JukeboxSessionSpec(
            hasMediaItem = true,
            playbackState = if (local.isPlaying || local.isPaused) {
                Player.STATE_READY
            } else {
                Player.STATE_IDLE
            },
            playWhenReady = local.isPlaying,
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
        is PlaybackSessionSurface.Cast -> if (castOverride.unreachable) {
            HIDDEN_SPEC
        } else {
            val isPlaying = castOverride.isPlaying ?: surface.isPlaying
            JukeboxSessionSpec(
                hasMediaItem = true,
                playbackState = if (castOverride.stopped && !isPlaying) {
                    Player.STATE_IDLE
                } else {
                    Player.STATE_READY
                },
                playWhenReady = isPlaying,
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
