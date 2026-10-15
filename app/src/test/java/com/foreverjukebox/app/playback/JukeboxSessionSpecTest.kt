package com.foreverjukebox.app.playback

import androidx.media3.common.Player
import com.foreverjukebox.app.ui.JukeboxAudioMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JukeboxSessionSpecTest {

    private val pausedTrack = LocalPlaybackStatus(
        isPlaying = false,
        isPaused = true,
        isWaitingForFocus = false,
        title = "Track",
        artist = "Artist",
        audioMode = JukeboxAudioMode.Off,
        durationMs = 180_000L
    )

    private val castPlaying = PlaybackSessionSurface.Cast(isPlaying = true, title = "T", artist = "A")
    private val castPaused = PlaybackSessionSurface.Cast(isPlaying = false, title = "T", artist = "A")

    private fun snapshot(
        surface: PlaybackSessionSurface,
        canSkipPrevious: Boolean = true,
        canSkipNext: Boolean = true
    ) = PlaybackSessionSnapshot(
        surface = surface,
        canSkipPrevious = canSkipPrevious,
        canSkipNext = canSkipNext
    )

    @Test
    fun hiddenSurfaceCarriesNoMediaItemSoTheNotificationIsRemoved() {
        val spec = resolveJukeboxSessionSpec(snapshot(PlaybackSessionSurface.Hidden), pausedTrack)

        assertFalse(spec.hasMediaItem)
        assertEquals(Player.STATE_IDLE, spec.playbackState)
        assertFalse(spec.commands.contains(Player.COMMAND_PLAY_PAUSE))
    }

    @Test
    fun loadingBuffersWithPlayWhenReadyAndOffersNoTransport() {
        val spec = resolveJukeboxSessionSpec(
            snapshot(PlaybackSessionSurface.Loading(progressBucket = 40)),
            pausedTrack
        )

        assertTrue(spec.hasMediaItem)
        assertEquals(Player.STATE_BUFFERING, spec.playbackState)
        // Play-when-ready while buffering is what keeps the service in the foreground
        // and makes the system draw its spinner instead of a play button.
        assertTrue(spec.playWhenReady)
        assertFalse(spec.positionAdvances)
        assertEquals("Loading - 40%", spec.title)
        assertEquals("Forever Jukebox", spec.artist)
        listOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT
        ).forEach { command -> assertFalse(spec.commands.contains(command)) }
        assertTrue(spec.commands.contains(Player.COMMAND_GET_METADATA))
    }

    @Test
    fun loadingWithoutProgressUsesPlainLoadingTitle() {
        val spec = resolveJukeboxSessionSpec(
            snapshot(PlaybackSessionSurface.Loading(progressBucket = null)),
            pausedTrack
        )

        assertEquals("Loading", spec.title)
    }

    @Test
    fun failedLoadShowsPausedRetrySurfaceWithoutSkips() {
        val spec = resolveJukeboxSessionSpec(snapshot(PlaybackSessionSurface.LoadFailed), pausedTrack)

        assertEquals(Player.STATE_READY, spec.playbackState)
        assertFalse(spec.playWhenReady)
        assertEquals("Loading Failed", spec.title)
        assertEquals("Forever Jukebox", spec.artist)
        assertTrue(spec.commands.contains(Player.COMMAND_PLAY_PAUSE))
        // Stop is how a dismissal reaches the session.
        assertTrue(spec.commands.contains(Player.COMMAND_STOP))
        assertFalse(spec.commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertFalse(spec.commands.contains(Player.COMMAND_SEEK_TO_NEXT))
    }

    @Test
    fun dismissedFailedLoadIsIdleSoItStaysDismissed() {
        val spec = resolveJukeboxSessionSpec(
            snapshot(PlaybackSessionSurface.LoadFailed),
            pausedTrack,
            SessionOverride(stopped = true)
        )

        assertEquals(Player.STATE_IDLE, spec.playbackState)
        assertTrue(spec.commands.contains(Player.COMMAND_PLAY_PAUSE))
    }

    @Test
    fun localSurfaceReadsPlayStateTitleAndDurationFromController() {
        val spec = resolveJukeboxSessionSpec(
            snapshot(PlaybackSessionSurface.Local),
            pausedTrack.copy(isPlaying = true, isPaused = false, audioMode = JukeboxAudioMode.Nightcore)
        )

        assertEquals(Player.STATE_READY, spec.playbackState)
        assertTrue(spec.playWhenReady)
        assertTrue(spec.positionAdvances)
        assertFalse(spec.suppressedByAudioFocus)
        assertEquals("Track (nightcore)", spec.title)
        assertEquals("Artist", spec.artist)
        assertEquals(180_000L, spec.durationMs)
        assertTrue(spec.showsArtwork)
        assertTrue(spec.commands.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(spec.commands.contains(Player.COMMAND_STOP))
    }

    @Test
    fun pausedAndFreshlyLoadedTracksAreReadyWithAPlayButton() {
        listOf(pausedTrack, pausedTrack.copy(isPaused = false)).forEach { local ->
            val spec = resolveJukeboxSessionSpec(snapshot(PlaybackSessionSurface.Local), local)

            assertEquals(Player.STATE_READY, spec.playbackState)
            assertFalse(spec.playWhenReady)
            assertFalse(spec.positionAdvances)
        }
    }

    @Test
    fun localTrackStoppedThroughTheSessionIsIdleSoADismissalSticks() {
        val spec = resolveJukeboxSessionSpec(
            snapshot(PlaybackSessionSurface.Local),
            pausedTrack.copy(isPaused = false),
            SessionOverride(stopped = true)
        )

        assertEquals(Player.STATE_IDLE, spec.playbackState)
        assertTrue(spec.hasMediaItem)
        assertTrue(spec.commands.contains(Player.COMMAND_PLAY_PAUSE))
    }

    @Test
    fun playParkedOnDelayedFocusIsPlayWhenReadyHeldByFocus() {
        val spec = resolveJukeboxSessionSpec(
            snapshot(PlaybackSessionSurface.Local),
            pausedTrack.copy(isPaused = false, isWaitingForFocus = true),
            SessionOverride(stopped = true)
        )

        assertEquals(Player.STATE_READY, spec.playbackState)
        assertTrue(spec.playWhenReady)
        assertTrue(spec.suppressedByAudioFocus)
        assertFalse(spec.positionAdvances)
    }

    @Test
    fun localSurfaceTreatsUnknownDurationAsUnset() {
        val spec = resolveJukeboxSessionSpec(
            snapshot(PlaybackSessionSurface.Local),
            pausedTrack.copy(title = null, durationMs = 0L)
        )

        assertEquals(null, spec.durationMs)
        assertEquals("Forever Jukebox", spec.title)
    }

    @Test
    fun skipCommandsFollowPlaylistAvailability() {
        val previousOnly = resolveJukeboxSessionSpec(
            snapshot(PlaybackSessionSurface.Local, canSkipPrevious = true, canSkipNext = false),
            pausedTrack
        )
        assertTrue(previousOnly.commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertFalse(previousOnly.commands.contains(Player.COMMAND_SEEK_TO_NEXT))

        val nextOnly = resolveJukeboxSessionSpec(
            snapshot(PlaybackSessionSurface.Local, canSkipPrevious = false, canSkipNext = true),
            pausedTrack
        )
        assertFalse(nextOnly.commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(nextOnly.commands.contains(Player.COMMAND_SEEK_TO_NEXT))
    }

    @Test
    fun castSurfaceReportsReceiverStateWithoutArtworkOrPosition() {
        val spec = resolveJukeboxSessionSpec(
            snapshot(PlaybackSessionSurface.Cast(isPlaying = true, title = "Cast Track", artist = "Cast Artist")),
            pausedTrack
        )

        assertTrue(spec.playWhenReady)
        assertEquals("Cast Track", spec.title)
        assertEquals("Cast Artist", spec.artist)
        assertFalse(spec.showsArtwork)
        assertFalse(spec.reportsPosition)
        assertEquals(null, spec.durationMs)
        assertTrue(spec.commands.contains(Player.COMMAND_SEEK_TO_NEXT))
    }

    @Test
    fun castSurfaceFallsBackToAppNameForBlankTitle() {
        val spec = resolveJukeboxSessionSpec(
            snapshot(PlaybackSessionSurface.Cast(isPlaying = false, title = " ", artist = null)),
            pausedTrack
        )

        assertEquals("Forever Jukebox", spec.title)
        assertEquals("", spec.artist)
    }

    @Test
    fun castOverrideShowsForwardedPlayState() {
        val spec = resolveJukeboxSessionSpec(
            snapshot(castPlaying),
            pausedTrack,
            SessionOverride(castPlaying = false)
        )

        assertFalse(spec.playWhenReady)
    }

    @Test
    fun stoppedCastReceiverIsIdleUntilItPlaysAgain() {
        val idle = resolveJukeboxSessionSpec(snapshot(castPaused), pausedTrack, SessionOverride(stopped = true))
        assertEquals(Player.STATE_IDLE, idle.playbackState)

        val playing = resolveJukeboxSessionSpec(snapshot(castPlaying), pausedTrack, SessionOverride(stopped = true))
        assertEquals(Player.STATE_READY, playing.playbackState)
    }

    @Test
    fun unreachableCastReceiverHidesTheSession() {
        val spec = resolveJukeboxSessionSpec(
            snapshot(castPlaying),
            pausedTrack,
            SessionOverride(castUnreachable = true)
        )

        assertFalse(spec.hasMediaItem)
    }

    @Test
    fun castPlayOverrideOutlivesStaleSnapshotsUntilTheReceiverReports() {
        val pausedByPress = SessionOverride(castPlaying = false)

        // An unrelated sync re-publishes the receiver's last report: keep the press.
        assertEquals(
            pausedByPress,
            pausedByPress.afterSnapshot(castPlaying, castPlaying, surfaceChanged = false)
        )
        // The receiver reports the paused state the press asked for.
        assertEquals(
            SessionOverride(),
            pausedByPress.afterSnapshot(castPlaying, castPaused, surfaceChanged = false)
        )
        // The surface moved on.
        assertEquals(
            SessionOverride(),
            pausedByPress.afterSnapshot(castPlaying, PlaybackSessionSurface.Local, surfaceChanged = true)
        )
    }

    @Test
    fun castReceiverChangingOnItsOwnDropsThePlayOverride() {
        val playingByPress = SessionOverride(castPlaying = true)

        assertEquals(
            SessionOverride(),
            playingByPress.afterSnapshot(castPaused, castPlaying, surfaceChanged = false)
        )
    }

    @Test
    fun unreachableCastLastsOnlyUntilTheNextSnapshot() {
        assertEquals(
            SessionOverride(),
            SessionOverride(castUnreachable = true).afterSnapshot(castPlaying, castPlaying, false)
        )
    }

    @Test
    fun stopLatchSurvivesSnapshotsOfTheSameIdleSurface() {
        val stopped = SessionOverride(stopped = true)

        assertEquals(stopped, stopped.afterSnapshot(castPaused, castPaused, surfaceChanged = false))
        assertEquals(stopped, stopped.afterSnapshot(PlaybackSessionSurface.Local, PlaybackSessionSurface.Local, false))
        assertEquals(
            stopped,
            stopped.afterSnapshot(PlaybackSessionSurface.LoadFailed, PlaybackSessionSurface.LoadFailed, false)
        )
    }

    @Test
    fun stopLatchClearsWhenTheSurfaceMovesOnOrTheReceiverPlays() {
        val stopped = SessionOverride(stopped = true)

        assertEquals(SessionOverride(), stopped.afterSnapshot(castPaused, castPlaying, surfaceChanged = false))
        // A new track passes through Loading even if the service never saw that snapshot.
        assertEquals(
            SessionOverride(),
            stopped.afterSnapshot(PlaybackSessionSurface.Local, PlaybackSessionSurface.Local, surfaceChanged = true)
        )
        assertEquals(
            SessionOverride(),
            stopped.afterSnapshot(PlaybackSessionSurface.LoadFailed, PlaybackSessionSurface.LoadFailed, true)
        )
    }

    @Test
    fun stopLatchClearsOnlyOnPlay() {
        val stopped = SessionOverride(stopped = true)

        assertEquals(
            SessionOverride(castPlaying = false, stopped = true),
            stopped.afterCommand(PlaybackAction.Pause, SessionOverride(castPlaying = false))
        )
        assertEquals(
            SessionOverride(castPlaying = true),
            stopped.afterCommand(PlaybackAction.Play, SessionOverride(castPlaying = true))
        )
    }

    @Test
    fun localPlaybackStartedOutsideTheSessionClearsTheStopLatch() {
        val stopped = SessionOverride(stopped = true)

        assertEquals(
            SessionOverride(),
            stopped.afterLocalStatus(PlaybackSessionSurface.Local, pausedTrack.copy(isPlaying = true))
        )
        assertEquals(
            SessionOverride(),
            stopped.afterLocalStatus(PlaybackSessionSurface.Local, pausedTrack.copy(isWaitingForFocus = true))
        )
        assertEquals(stopped, stopped.afterLocalStatus(PlaybackSessionSurface.Local, pausedTrack))
        assertEquals(
            stopped,
            stopped.afterLocalStatus(castPaused, pausedTrack.copy(isPlaying = true))
        )
    }

    @Test
    fun loadingNotificationProgressBucketUsesTenPercentSteps() {
        assertEquals(null, loadingNotificationProgressBucket(null))
        assertEquals(null, loadingNotificationProgressBucket(0))
        assertEquals(null, loadingNotificationProgressBucket(9))
        assertEquals(null, loadingNotificationProgressBucket(-4))
        assertEquals(10, loadingNotificationProgressBucket(10))
        assertEquals(10, loadingNotificationProgressBucket(19))
        assertEquals(20, loadingNotificationProgressBucket(20))
        assertEquals(100, loadingNotificationProgressBucket(100))
        assertEquals(100, loadingNotificationProgressBucket(112))
    }

    @Test
    fun loadingNotificationTitleOmitsProgressUntilFirstBucket() {
        assertEquals("Loading", loadingNotificationTitle(null))
        assertEquals("Loading - 10%", loadingNotificationTitle(10))
        assertEquals("Loading - 100%", loadingNotificationTitle(100))
    }

    @Test
    fun localNotificationTitleUsesLoadingTitleBeforeTrackMetadataFallback() {
        assertEquals(
            "Loading",
            localPlaybackNotificationTitle(
                baseTitle = null,
                audioMode = JukeboxAudioMode.Off,
                isLoading = true,
                loadingProgressBucket = null
            )
        )
        assertEquals(
            "Loading - 20%",
            localPlaybackNotificationTitle(
                baseTitle = "Track",
                audioMode = JukeboxAudioMode.Nightcore,
                isLoading = true,
                loadingProgressBucket = 20
            )
        )
        assertEquals(
            "Forever Jukebox",
            localPlaybackNotificationTitle(
                baseTitle = null,
                audioMode = JukeboxAudioMode.Off,
                isLoading = false,
                loadingProgressBucket = null
            )
        )
        assertEquals(
            "Track",
            localPlaybackNotificationTitle(
                baseTitle = "Track",
                audioMode = JukeboxAudioMode.Off,
                isLoading = false,
                loadingProgressBucket = null
            )
        )
        assertEquals(
            "Track (nightcore)",
            localPlaybackNotificationTitle(
                baseTitle = "Track",
                audioMode = JukeboxAudioMode.Nightcore,
                isLoading = false,
                loadingProgressBucket = null
            )
        )
    }

    @Test
    fun localNotificationTitleUsesFailedTitleForLoadFailure() {
        assertEquals(
            "Loading Failed",
            localPlaybackNotificationTitle(
                baseTitle = "Track",
                audioMode = JukeboxAudioMode.Nightcore,
                isLoading = false,
                loadingProgressBucket = null,
                isLoadFailed = true
            )
        )
    }

    @Test
    fun localNotificationArtistUsesAppNameWhileLoading() {
        assertEquals(
            "Forever Jukebox",
            localPlaybackNotificationArtist(
                baseArtist = null,
                isLoading = true
            )
        )
        assertEquals(
            "Forever Jukebox",
            localPlaybackNotificationArtist(
                baseArtist = "Artist",
                isLoading = true
            )
        )
    }

    @Test
    fun localNotificationArtistUsesAppNameForLoadFailure() {
        assertEquals(
            "Forever Jukebox",
            localPlaybackNotificationArtist(
                baseArtist = "Artist",
                isLoading = false,
                isLoadFailed = true
            )
        )
    }

    @Test
    fun localNotificationArtistUsesProvidedValueWhenNotLoading() {
        assertEquals(
            "",
            localPlaybackNotificationArtist(
                baseArtist = null,
                isLoading = false
            )
        )
        assertEquals(
            "",
            localPlaybackNotificationArtist(
                baseArtist = "",
                isLoading = false
            )
        )
        assertEquals(
            "Artist",
            localPlaybackNotificationArtist(
                baseArtist = "Artist",
                isLoading = false
            )
        )
    }
}
