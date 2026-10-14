package com.foreverjukebox.app.playback

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

private const val MEDIA_ITEM_UID = "jukebox"
private const val POSITION_REFRESH_INTERVAL_MS = 1_000L

/**
 * The media session's view of playback. State comes from the published
 * [PlaybackSessionSnapshot] plus the [PlaybackController]'s live values; transport presses
 * go through [JukeboxCommandHandler]. Must be used on the main looper, like the controller.
 *
 * Jukebox branches move the position without a seek, so while playing the state is
 * refreshed every second; [SimpleBasePlayer] reports any jump from the extrapolated
 * position as a discontinuity, which keeps the system seek bar on the real beat.
 */
@OptIn(UnstableApi::class)
internal class JukeboxPlayer(
    looper: Looper,
    private val readLocalStatus: () -> LocalPlaybackStatus,
    private val readPositionMs: () -> Long,
    private val commandHandler: JukeboxCommandHandler,
    private val artworkData: ByteArray?
) : SimpleBasePlayer(looper) {
    private var snapshot = PlaybackSessionState.snapshot
    private var castOverride = CastCommandOverride()
    private val handler = Handler(looper)
    private var positionRefreshScheduled = false
    private val positionRefresh = Runnable {
        positionRefreshScheduled = false
        invalidateState()
    }

    val surface: PlaybackSessionSurface
        get() = snapshot.surface

    fun publish(snapshot: PlaybackSessionSnapshot) {
        this.snapshot = snapshot
        castOverride = castOverride.afterSnapshot(snapshot.surface)
        invalidateState()
    }

    /** Re-reads controller state after a change made outside the session. */
    fun refresh() {
        invalidateState()
    }

    fun skipToPrevious() {
        verifyApplicationThread()
        commandHandler.skipToPrevious(snapshot.surface)
    }

    fun skipToNext() {
        verifyApplicationThread()
        commandHandler.skipToNext(snapshot.surface)
    }

    /** Pauses local playback for a lost output route, as a pause press would. */
    fun pauseLocalPlayback() {
        verifyApplicationThread()
        if (snapshot.surface != PlaybackSessionSurface.Local) {
            return
        }
        applyAction(PlaybackAction.Pause)
        invalidateState()
    }

    override fun getState(): State {
        val spec = resolveJukeboxSessionSpec(snapshot, readLocalStatus(), castOverride)
        schedulePositionRefresh(spec.reportsPosition && spec.playWhenReady)
        return buildState(spec)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        applyAction(if (playWhenReady) PlaybackAction.Play else PlaybackAction.Pause)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        applyAction(PlaybackAction.Stop)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacks(positionRefresh)
        positionRefreshScheduled = false
        return Futures.immediateVoidFuture()
    }

    private fun applyAction(action: PlaybackAction) {
        val result = commandHandler.handle(action, snapshot.surface) ?: return
        castOverride = castOverride.afterCommand(action, result)
    }

    private fun schedulePositionRefresh(active: Boolean) {
        if (!active) {
            handler.removeCallbacks(positionRefresh)
            positionRefreshScheduled = false
            return
        }
        if (!positionRefreshScheduled) {
            positionRefreshScheduled = true
            handler.postDelayed(positionRefresh, POSITION_REFRESH_INTERVAL_MS)
        }
    }

    private fun buildState(spec: JukeboxSessionSpec): State {
        val commands = Player.Commands.Builder()
        spec.commands.forEach(commands::add)
        val builder = State.Builder()
            .setAvailableCommands(commands.build())
            .setPlaybackState(spec.playbackState)
            .setPlayWhenReady(
                spec.playWhenReady,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
        if (!spec.hasMediaItem) {
            return builder.build()
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(spec.title)
            .setArtist(spec.artist)
        if (spec.showsArtwork && artworkData != null) {
            metadata.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        val mediaItem = MediaItem.Builder()
            .setMediaId(MEDIA_ITEM_UID)
            .setMediaMetadata(metadata.build())
            .build()
        val item = MediaItemData.Builder(MEDIA_ITEM_UID)
            .setMediaItem(mediaItem)
            .setDurationUs(spec.durationMs?.let(Util::msToUs) ?: C.TIME_UNSET)
            .build()
        val positionMs = if (spec.reportsPosition) readPositionMs().coerceAtLeast(0L) else 0L
        val position = if (spec.reportsPosition && spec.playWhenReady) {
            PositionSupplier.getExtrapolating(positionMs, 1f)
        } else {
            PositionSupplier.getConstant(positionMs)
        }
        return builder
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(position)
            .build()
    }
}

/**
 * Session-facing wrapper that turns previous/next into playlist skips. [Player.seekToNext]
 * and [Player.seekToPrevious] are final on a [SimpleBasePlayer] and resolve against its
 * own single-item timeline, so the skip has to be intercepted before it gets there.
 */
@OptIn(UnstableApi::class)
internal class JukeboxSessionPlayer(
    private val jukeboxPlayer: JukeboxPlayer
) : ForwardingPlayer(jukeboxPlayer) {
    override fun seekToPrevious() {
        jukeboxPlayer.skipToPrevious()
    }

    override fun seekToNext() {
        jukeboxPlayer.skipToNext()
    }
}
