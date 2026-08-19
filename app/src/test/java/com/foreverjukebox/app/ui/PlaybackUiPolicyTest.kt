package com.foreverjukebox.app.ui

import com.foreverjukebox.app.BuildConfig
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
    fun playbackSummaryLineHiddenWhileCastingBecauseReceiverDoesNotReportCounters() {
        val jukebox = PlaybackState(listenTime = "00:01:00", beatsPlayed = 42)

        assertEquals(
            "Listen Time: 00:01:00 - Total Beats: 42",
            playbackSummaryLine(jukebox)
        )
        assertEquals(
            "Listen Time: 00:01:00",
            playbackSummaryLine(jukebox.copy(playMode = PlaybackMode.Autocanonizer))
        )
        assertNull(playbackSummaryLine(jukebox.copy(isCasting = true)))
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
    fun localLoadingCancelShowsOnlyDuringLocalAnalysisPhase() {
        val analyzing = PlaybackState(
            analysisInFlight = true,
            analysisMessage = "Detecting beats"
        )

        assertTrue(shouldShowLocalLoadingCancel(AppMode.Local, analyzing))
        assertTrue(
            shouldShowLocalLoadingCancel(
                AppMode.Local,
                analyzing.copy(analysisMessage = null)
            )
        )
    }

    @Test
    fun localLoadingCancelHidesDuringAudioLoadAndOtherPhases() {
        val analyzing = PlaybackState(
            analysisInFlight = true,
            analysisMessage = "Detecting beats"
        )

        assertFalse(
            shouldShowLocalLoadingCancel(
                AppMode.Local,
                PlaybackState(analysisInFlight = true, analysisMessage = "Loading audio")
            )
        )
        assertFalse(
            shouldShowLocalLoadingCancel(
                AppMode.Local,
                PlaybackState(audioLoading = true)
            )
        )
        assertFalse(
            shouldShowLocalLoadingCancel(
                AppMode.Local,
                analyzing.copy(audioLoading = true)
            )
        )
        assertFalse(
            shouldShowLocalLoadingCancel(
                AppMode.Local,
                PlaybackState(analysisCalculating = true)
            )
        )
        assertFalse(shouldShowLocalLoadingCancel(AppMode.Server, analyzing))
        assertFalse(shouldShowLocalLoadingCancel(null, analyzing))
        // Cancelling an analysis started while casting is allowed (shown on the cast screen).
        assertTrue(
            shouldShowLocalLoadingCancel(AppMode.Local, analyzing.copy(isCasting = true))
        )
        assertFalse(shouldShowLocalLoadingCancel(AppMode.Local, PlaybackState()))
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

        assertEquals(BuildConfig.SERVER_MODE_AVAILABLE, shouldRetryFailedLoadFromTransport(state))
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
    fun youtubeIdRetriesFromTransportBeforeJobAssigned() {
        val state = UiState(
            appMode = AppMode.Server,
            playback = PlaybackState(
                analysisErrorMessage = "Network error.",
                lastYouTubeId = "dQw4w9WgXcQ"
            )
        )

        assertEquals(
            BuildConfig.SERVER_MODE_AVAILABLE,
            shouldRetryFailedLoadFromTransport(state)
        )
    }

    @Test
    fun retryTrackIdPrefersJobIdOverYoutubeId() {
        val playback = PlaybackState(
            lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2",
            lastYouTubeId = "dQw4w9WgXcQ"
        )

        assertEquals("a3f3c0dc73c6476c9db95c227f9206f2", playback.retryTrackIdOrNull())
        assertEquals("dQw4w9WgXcQ", playback.copy(lastJobId = null).retryTrackIdOrNull())
        assertNull(playback.copy(lastJobId = null, lastYouTubeId = null).retryTrackIdOrNull())
    }

    @Test
    fun retryTrackIdRejectsIdsTheLoadPipelineCannotParse() {
        // Ids arrive from server responses and cast receiver status, so junk is
        // possible; advertising a retry for an unloadable id would leave the failed
        // notification's button a silent dead end.
        assertNull(PlaybackState(lastYouTubeId = "not a parseable id").retryTrackIdOrNull())
        assertNull(PlaybackState(lastJobId = "job_123").retryTrackIdOrNull())
        assertNull(PlaybackState(lastJobId = "local-fingerprint").retryTrackIdOrNull())
    }

    @Test
    fun resolvedTrackIdMatchesAnalyticsResolution() {
        // Retry and analytics resolve the track id through the same helper so both
        // always name the same track.
        val playback = PlaybackState(
            lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2",
            lastYouTubeId = "dQw4w9WgXcQ"
        )

        assertEquals(playback.analyticsPlayTrackId(), playback.resolvedTrackIdOrNull())
        assertEquals(
            playback.copy(lastJobId = null).analyticsPlayTrackId(),
            playback.copy(lastJobId = null).resolvedTrackIdOrNull()
        )
    }

    @Test
    fun failedLoadRetryRequestFallsBackToYoutubeId() {
        val request = failedLoadRetryRequest(
            PlaybackState(
                lastYouTubeId = "dQw4w9WgXcQ",
                playAfterLoaded = true
            )
        )

        assertEquals("dQw4w9WgXcQ", request?.trackId)
        assertTrue(request?.playAfterLoaded == true)
    }

    @Test
    fun failedTrackWithLoadedAudioAndAnalysisIsStillPlayable() {
        val playback = PlaybackState(
            analysisErrorMessage = "Playback failed.",
            audioLoaded = true,
            analysisLoaded = true
        )

        assertTrue(failedTrackStillPlayable(playback))
    }

    @Test
    fun playFromMemoryRequiresLoadedAudioAndAnalysis() {
        val playable = PlaybackState(
            analysisErrorMessage = "Playback failed.",
            audioLoaded = true,
            analysisLoaded = true
        )

        assertFalse(failedTrackStillPlayable(playable.copy(audioLoaded = false)))
        assertFalse(failedTrackStillPlayable(playable.copy(analysisLoaded = false)))
        assertFalse(failedTrackStillPlayable(playable.copy(analysisErrorMessage = null)))
        assertFalse(failedTrackStillPlayable(playable.copy(analysisErrorMessage = "")))
        assertFalse(failedTrackStillPlayable(playable.copy(isCasting = true)))
        assertFalse(failedTrackStillPlayable(playable.copy(analysisInFlight = true)))
        assertFalse(failedTrackStillPlayable(playable.copy(audioLoading = true)))
        assertFalse(failedTrackStillPlayable(playable.copy(isCastLoading = true)))
    }

    @Test
    fun failedLoadNotificationStaysVisibleForGenuineLoadFailure() {
        val state = UiState(
            appMode = AppMode.Server,
            playback = PlaybackState(
                analysisErrorMessage = "Loading failed.",
                lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2"
            )
        )

        assertEquals(
            BuildConfig.SERVER_MODE_AVAILABLE,
            shouldRetryFailedLoadFromTransport(state)
        )
    }

    @Test
    fun failedNotificationStaysVisibleWhenTrackPlayableFromMemory() {
        // Visibility and press behavior are separate: the failed notification stays
        // up so the transport button remains reachable, and the press resumes the
        // in-memory track instead of reloading.
        val state = UiState(
            appMode = AppMode.Server,
            playback = PlaybackState(
                analysisErrorMessage = "Playback failed.",
                lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2",
                audioLoaded = true,
                analysisLoaded = true
            )
        )

        assertEquals(
            BuildConfig.SERVER_MODE_AVAILABLE,
            shouldRetryFailedLoadFromTransport(state)
        )
        assertTrue(failedTrackStillPlayable(state.playback))
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
