package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.engine.JukeboxState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackUiPolicyTest {

    @Test
    fun playbackTransportContentDescriptionMatchesPlaybackState() {
        assertEquals("Pause", playbackTransportContentDescription(PlaybackState(isRunning = true)))
        assertEquals("Resume", playbackTransportContentDescription(PlaybackState(isPaused = true)))
        assertEquals("Play", playbackTransportContentDescription(PlaybackState()))
    }

    @Test
    fun jumpLineUsesActualJumpDestinationWhenCursorCaughtUpPastIt() {
        val line = jumpLineForEngineState(
            JukeboxState(
                currentBeatIndex = 2,
                beatsPlayed = 12,
                currentTime = 1.2,
                lastJumped = true,
                lastJumpTime = 0.0,
                lastJumpFromIndex = 1,
                lastJumpToIndex = 0,
                currentThreshold = 20,
                lastBranchPoint = 1,
                curRandomBranchChance = 0.2
            ),
            startedAt = 1234L
        )

        assertEquals(1, line?.from)
        assertEquals(0, line?.to)
        assertEquals(1234L, line?.startedAt)
    }

    @Test
    fun playAfterLoadedOptionShowsForLocalAndServerLoadingOnly() {
        val loading = PlaybackState(analysisInFlight = true)

        assertTrue(shouldShowPlayAfterLoadedOption(AppMode.Local, loading))
        assertTrue(shouldShowPlayAfterLoadedOption(AppMode.Server, loading))
        assertFalse(shouldShowPlayAfterLoadedOption(null, loading))
        assertFalse(shouldShowPlayAfterLoadedOption(AppMode.Local, PlaybackState()))
        assertFalse(
            shouldShowPlayAfterLoadedOption(
                AppMode.Server,
                loading.copy(isCasting = true)
            )
        )
        assertFalse(
            shouldShowPlayAfterLoadedOption(
                AppMode.Local,
                PlaybackState(analysisErrorMessage = "boom")
            )
        )
    }

    @Test
    fun playAfterLoadedStartsOnlyWhenCheckedAndStableReady() {
        val ready = PlaybackState(
            playAfterLoaded = true,
            audioLoaded = true,
            analysisLoaded = true
        )

        assertTrue(shouldStartPlayAfterLoaded(ready))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(playAfterLoaded = false)))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(analysisInFlight = true)))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(audioLoading = true)))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(analysisCalculating = true)))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(isCasting = true)))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(isRunning = true)))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(analysisErrorMessage = "boom")))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(audioLoaded = false)))
        assertFalse(shouldStartPlayAfterLoaded(ready.copy(analysisLoaded = false)))
    }

    @Test
    fun trackLoadingCoversLocalAndCastLoadingStates() {
        assertTrue(PlaybackState(analysisInFlight = true).isTrackLoading())
        assertTrue(PlaybackState(analysisCalculating = true).isTrackLoading())
        assertTrue(PlaybackState(audioLoading = true).isTrackLoading())
        assertTrue(PlaybackState(isCastLoading = true).isTrackLoading())
        assertTrue(PlaybackState(castPlaybackState = "loading").isTrackLoading())
        assertFalse(PlaybackState().isTrackLoading())
    }

    @Test
    fun loadingTrackMetadataUsesTitleAndArtist() {
        val metadata = resolveLoadingTrackMetadata(
            playback = PlaybackState(
                trackTitle = " Track ",
                trackArtist = " Artist "
            ),
            localSelectedFileName = null
        )

        assertEquals(LoadingTrackMetadata("Track", "Artist"), metadata)
    }

    @Test
    fun loadingTrackMetadataShowsTitleWithoutArtist() {
        val metadata = resolveLoadingTrackMetadata(
            playback = PlaybackState(trackTitle = "Track"),
            localSelectedFileName = null
        )

        assertEquals(LoadingTrackMetadata("Track", null), metadata)
    }

    @Test
    fun loadingTrackMetadataSuppressesBlankMetadata() {
        val metadata = resolveLoadingTrackMetadata(
            playback = PlaybackState(
                trackTitle = " ",
                trackArtist = " "
            ),
            localSelectedFileName = ""
        )

        assertNull(metadata)
    }

    @Test
    fun loadingTrackMetadataFallsBackToLocalFileName() {
        val metadata = resolveLoadingTrackMetadata(
            playback = PlaybackState(),
            localSelectedFileName = " local.mp3 "
        )

        assertEquals(LoadingTrackMetadata("local.mp3", null), metadata)
    }

    @Test
    fun shareTrackIdPrefersJobIdForReusableServerIdentity() {
        val playback = PlaybackState(
            lastYouTubeId = "dQw4w9WgXcQ",
            lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2"
        )

        assertEquals("a3f3c0dc73c6476c9db95c227f9206f2", playback.shareTrackIdOrNull())
    }

    @Test
    fun shareTrackIdDoesNotFallbackToYoutubeIdForLegacySourceOnlyTracks() {
        val playback = PlaybackState(lastYouTubeId = "dQw4w9WgXcQ")

        assertNull(playback.shareTrackIdOrNull())
    }

    @Test
    fun reusableTrackIdsForMatchingIncludesJobAndYoutubeAliases() {
        val playback = PlaybackState(
            lastYouTubeId = "dQw4w9WgXcQ",
            lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2"
        )

        assertEquals(
            setOf("a3f3c0dc73c6476c9db95c227f9206f2", "dQw4w9WgXcQ"),
            playback.reusableTrackIdsForMatching()
        )
    }

    @Test
    fun failedServerLoadCanRetryFromTransportWithJobId() {
        val state = UiState(
            appMode = AppMode.Server,
            playback = PlaybackState(
                analysisErrorMessage = "Loading failed.",
                lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2"
            )
        )

        assertTrue(shouldRetryFailedLoadFromTransport(state))
    }

    @Test
    fun failedServerLoadDoesNotRetryFromTransportWithYoutubeIdOnly() {
        val state = UiState(
            appMode = AppMode.Server,
            playback = PlaybackState(
                analysisErrorMessage = "Loading failed.",
                lastYouTubeId = "dQw4w9WgXcQ"
            )
        )

        assertFalse(shouldRetryFailedLoadFromTransport(state))
    }

    @Test
    fun failedLoadRetryRequestCarriesPlayAfterLoadedWhenEnabled() {
        val request = failedLoadRetryRequest(
            PlaybackState(
                playAfterLoaded = true,
                lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2",
                trackTitle = "Track",
                trackArtist = "Artist"
            )
        )

        assertEquals("a3f3c0dc73c6476c9db95c227f9206f2", request?.trackId)
        assertEquals("Track", request?.title)
        assertEquals("Artist", request?.artist)
        assertTrue(request?.playAfterLoaded == true)
    }

    @Test
    fun failedLoadRetryRequestCarriesPlayAfterLoadedWhenDisabled() {
        val request = failedLoadRetryRequest(
            PlaybackState(
                playAfterLoaded = false,
                lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2"
            )
        )

        assertEquals("a3f3c0dc73c6476c9db95c227f9206f2", request?.trackId)
        assertFalse(request?.playAfterLoaded == true)
    }

    @Test
    fun failedLoadRetryRequestRequiresTrackId() {
        assertNull(
            failedLoadRetryRequest(
                PlaybackState(
                    playAfterLoaded = true,
                    analysisErrorMessage = "Loading failed."
                )
            )
        )
    }

    @Test
    fun localLoadErrorDoesNotRetryFromTransport() {
        val state = UiState(
            appMode = AppMode.Local,
            playback = PlaybackState(
                analysisErrorMessage = "Local analysis failed.",
                lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2"
            )
        )

        assertFalse(shouldRetryFailedLoadFromTransport(state))
    }

    @Test
    fun loadingStatesDoNotRetryFromTransport() {
        val baseState = UiState(
            appMode = AppMode.Server,
            playback = PlaybackState(
                analysisErrorMessage = "Loading failed.",
                lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2"
            )
        )

        assertFalse(
            shouldRetryFailedLoadFromTransport(
                baseState.copy(playback = baseState.playback.copy(analysisInFlight = true))
            )
        )
        assertFalse(
            shouldRetryFailedLoadFromTransport(
                baseState.copy(playback = baseState.playback.copy(isCastLoading = true))
            )
        )
        assertFalse(
            shouldRetryFailedLoadFromTransport(
                baseState.copy(playback = baseState.playback.copy(castPlaybackState = "loading"))
            )
        )
    }

    @Test
    fun missingTrackIdDoesNotRetryFromTransport() {
        val state = UiState(
            appMode = AppMode.Server,
            playback = PlaybackState(analysisErrorMessage = "Loading failed.")
        )

        assertFalse(shouldRetryFailedLoadFromTransport(state))
    }

    @Test
    fun playbackChangeLoadingLockBlocksEveryLoadingPhase() {
        assertTrue(shouldBlockPlaybackChangeWhileLoading(PlaybackState(analysisInFlight = true)))
        assertTrue(shouldBlockPlaybackChangeWhileLoading(PlaybackState(analysisCalculating = true)))
        assertTrue(shouldBlockPlaybackChangeWhileLoading(PlaybackState(audioLoading = true)))
        assertTrue(shouldBlockPlaybackChangeWhileLoading(PlaybackState(isCastLoading = true)))
        assertTrue(shouldBlockPlaybackChangeWhileLoading(PlaybackState(castPlaybackState = "loading")))
    }

    @Test
    fun playbackChangeLoadingLockAllowsStableAndFailedStates() {
        assertFalse(shouldBlockPlaybackChangeWhileLoading(PlaybackState()))
        assertFalse(
            shouldBlockPlaybackChangeWhileLoading(
                PlaybackState(
                    audioLoaded = true,
                    analysisLoaded = true
                )
            )
        )
        assertFalse(
            shouldBlockPlaybackChangeWhileLoading(
                PlaybackState(analysisErrorMessage = "Loading failed.")
            )
        )
    }

    @Test
    fun playlistQueueEditsRemainAvailableWhilePlaybackChangesAreBlocked() {
        val loading = PlaybackState(analysisInFlight = true)
        val one = PlaylistTrack("one", PlaylistTrackType.Server, "One", null)
        val two = PlaylistTrack("two", PlaylistTrackType.Server, "Two", null)
        val three = PlaylistTrack("three", PlaylistTrackType.Server, "Three", null)
        val playlist = JukeboxPlaylistState(
            tracks = listOf(one, two),
            currentIndex = 0
        )

        assertTrue(shouldBlockPlaybackChangeWhileLoading(loading))
        assertEquals(listOf(one, two, three), playlist.appendTrack(three).tracks)
        assertEquals(listOf(one, three), playlist.appendTrack(three).removeTrackAt(1).tracks)
    }

    @Test
    fun playlistSkipEnablesPlayAfterLoadedForActiveLocalPlaylist() {
        val playlist = JukeboxPlaylistState(
            tracks = listOf(
                PlaylistTrack("one", PlaylistTrackType.Server, "One", null),
                PlaylistTrack("two", PlaylistTrackType.Server, "Two", null)
            ),
            currentIndex = 0
        )
        val active = UiState(
            playlist = playlist,
            playback = PlaybackState(playMode = PlaybackMode.Jukebox)
        )

        assertTrue(shouldEnablePlayAfterLoadedForPlaylistSkip(active))
        assertFalse(
            shouldEnablePlayAfterLoadedForPlaylistSkip(
                active.copy(playback = active.playback.copy(isCasting = true))
            )
        )
        assertFalse(
            shouldEnablePlayAfterLoadedForPlaylistSkip(
                active.copy(playlist = playlist.copy(tracks = playlist.tracks.take(1)))
            )
        )
    }

    @Test
    fun savedPlaylistButtonShowsOnlyForInactivePlaylistOnEmptyListenScreen() {
        val inactivePlaylist = JukeboxPlaylistState(
            tracks = listOf(
                PlaylistTrack("one", PlaylistTrackType.Server, "One", null),
                PlaylistTrack("two", PlaylistTrackType.Server, "Two", null)
            ),
            currentIndex = -1
        )
        val empty = UiState(playlist = inactivePlaylist, playback = PlaybackState())

        assertTrue(shouldShowSavedPlaylistButton(empty))
        assertFalse(
            shouldShowSavedPlaylistButton(
                empty.copy(playback = PlaybackState(audioLoaded = true, analysisLoaded = true))
            )
        )
        assertFalse(
            shouldShowSavedPlaylistButton(
                empty.copy(playlist = inactivePlaylist.copy(currentIndex = 0))
            )
        )
        assertFalse(
            shouldShowSavedPlaylistButton(
                empty.copy(playlist = inactivePlaylist.copy(tracks = inactivePlaylist.tracks.take(1)))
            )
        )
    }

    @Test
    fun activePlaylistControlsHideForInactiveSavedPlaylist() {
        val inactivePlaylist = JukeboxPlaylistState(
            tracks = listOf(
                PlaylistTrack("one", PlaylistTrackType.Server, "One", null),
                PlaylistTrack("two", PlaylistTrackType.Server, "Two", null)
            ),
            currentIndex = -1
        )

        assertTrue(shouldShowPlaylistControls(inactivePlaylist))
        assertFalse(shouldShowActivePlaylistControls(inactivePlaylist))
        assertTrue(shouldShowActivePlaylistControls(inactivePlaylist.copy(currentIndex = 0)))
    }
}
