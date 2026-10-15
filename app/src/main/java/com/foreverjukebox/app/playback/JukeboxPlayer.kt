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

/** How long a forwarded cast press is shown before the receiver's own report wins. */
private const val CAST_PLAY_OVERRIDE_TIMEOUT_MS = 5_000L

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
    private var published = PlaybackSessionState.published.value
    private var override = SessionOverride()
    private val handler = Handler(looper)
    private var positionRefreshScheduled = false
    private val positionRefresh = Runnable {
        positionRefreshScheduled = false
        invalidateState()
    }
    private val castPlayOverrideExpiry = Runnable {
        override = override.copy(castPlaying = null)
        invalidateState()
    }

    // The metadata only changes with the track, so the item is rebuilt only then; reusing
    // the instance also spares Media3 comparing the artwork bytes on every refresh.
    private var cachedItemKey: MediaItemKey? = null
    private var cachedItem: MediaItemData? = null

    private data class MediaItemKey(
        val title: String,
        val artist: String,
        val durationMs: Long?,
        val showsArtwork: Boolean
    )

    val surface: PlaybackSessionSurface
        get() = published.snapshot.surface

    fun publish(next: PlaybackSessionState.Published) {
        val previous = published
        published = next
        override = override.afterSnapshot(
            previous = previous.snapshot.surface,
            surface = next.snapshot.surface,
            surfaceChanged = next.surfaceEpoch != previous.surfaceEpoch
        )
        if (override.castPlaying == null) {
            handler.removeCallbacks(castPlayOverrideExpiry)
        }
        invalidateState()
    }

    fun skipToPrevious() {
        verifyApplicationThread()
        commandHandler.skipToPrevious(surface)
    }

    fun skipToNext() {
        verifyApplicationThread()
        commandHandler.skipToNext(surface)
    }

    /** Pauses local playback for a lost output route, as a pause press would. */
    fun pauseLocalPlayback() {
        verifyApplicationThread()
        if (surface != PlaybackSessionSurface.Local) {
            return
        }
        applyAction(PlaybackAction.Pause)
        invalidateState()
    }

    override fun getState(): State {
        val local = readLocalStatus()
        override = override.afterLocalStatus(surface, local)
        val spec = resolveJukeboxSessionSpec(published.snapshot, local, override)
        schedulePositionRefresh(spec.positionAdvances)
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
        handler.removeCallbacks(castPlayOverrideExpiry)
        positionRefreshScheduled = false
        return Futures.immediateVoidFuture()
    }

    private fun applyAction(action: PlaybackAction) {
        val result = commandHandler.handle(action, surface) ?: return
        override = override.afterCommand(action, result)
        handler.removeCallbacks(castPlayOverrideExpiry)
        if (override.castPlaying != null) {
            handler.postDelayed(castPlayOverrideExpiry, CAST_PLAY_OVERRIDE_TIMEOUT_MS)
        }
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
        if (spec.suppressedByAudioFocus) {
            builder.setPlaybackSuppressionReason(
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
            )
        }
        if (!spec.hasMediaItem) {
            return builder.build()
        }
        val positionMs = if (spec.reportsPosition) readPositionMs().coerceAtLeast(0L) else 0L
        val position = if (spec.positionAdvances) {
            PositionSupplier.getExtrapolating(positionMs, 1f)
        } else {
            PositionSupplier.getConstant(positionMs)
        }
        return builder
            .setPlaylist(listOf(mediaItemFor(spec)))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(position)
            .build()
    }

    private fun mediaItemFor(spec: JukeboxSessionSpec): MediaItemData {
        val key = MediaItemKey(spec.title, spec.artist, spec.durationMs, spec.showsArtwork)
        cachedItem?.takeIf { key == cachedItemKey }?.let { return it }
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
        cachedItemKey = key
        cachedItem = item
        return item
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
