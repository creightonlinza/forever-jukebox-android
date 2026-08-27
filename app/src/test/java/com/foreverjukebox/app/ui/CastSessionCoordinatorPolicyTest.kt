package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CastSessionCoordinatorPolicyTest {

    @Test
    fun capturePreservedCastTrackReturnsNullWhenTrackIdMissing() {
        val preserved = capturePreservedCastTrack(
            PlaybackState(
                audioLoaded = true,
                analysisLoaded = true
            )
        )

        assertNull(preserved)
    }

    @Test
    fun capturePreservedCastTrackReturnsNullWhenTrackNotReadyToAutoCast() {
        val preserved = capturePreservedCastTrack(
            PlaybackState(
                audioLoaded = true,
                analysisLoaded = false,
                lastYouTubeId = "yt123",
                trackTitle = "Track",
                trackArtist = "Artist"
            )
        )

        assertNull(preserved)
    }

    @Test
    fun capturePreservedCastTrackKeepsResolvedTrackMetadata() {
        val preserved = capturePreservedCastTrack(
            PlaybackState(
                audioLoaded = true,
                analysisLoaded = true,
                lastYouTubeId = "yt123",
                lastJobId = "job123",
                trackTitle = "Track",
                trackArtist = "Artist",
                jukeboxAudioMode = JukeboxAudioMode.EightD
            )
        )

        assertEquals(
            PreservedCastTrack.Server(
                jobId = "job123",
                youtubeId = "yt123",
                title = "Track",
                artist = "Artist",
                audioMode = JukeboxAudioMode.EightD
            ),
            preserved
        )
    }

    @Test
    fun capturePreservedCastTrackCarriesEngineTuningForJukeboxTracks() {
        val preserved = capturePreservedCastTrack(
            playback = PlaybackState(
                audioLoaded = true,
                analysisLoaded = true,
                lastJobId = "job123",
                playMode = PlaybackMode.Jukebox
            ),
            engineTuningParams = "jb=1&thresh=45"
        )

        assertEquals("jb=1&thresh=45", preserved?.tuningParams)
    }

    @Test
    fun capturePreservedCastTrackDropsEngineTuningForAutocanonizerTracks() {
        val preserved = capturePreservedCastTrack(
            playback = PlaybackState(
                audioLoaded = true,
                analysisLoaded = true,
                lastJobId = "job123",
                playMode = PlaybackMode.Autocanonizer
            ),
            engineTuningParams = "jb=1&thresh=45"
        )

        assertNotNull(preserved)
        assertNull(preserved?.tuningParams)
    }

    @Test
    fun capturePreservedCastTrackDropsRetainedAudioModeForAutocanonizerTracks() {
        val preserved = capturePreservedCastTrack(
            playback = PlaybackState(
                audioLoaded = true,
                analysisLoaded = true,
                lastJobId = "job123",
                playMode = PlaybackMode.Autocanonizer,
                jukeboxAudioMode = JukeboxAudioMode.Cowbell
            )
        )

        assertEquals(JukeboxAudioMode.Off, preserved?.audioMode)
    }

    @Test
    fun capturePendingCastSelectionDropsRetainedAudioModeForAutocanonizerTracks() {
        val pending = capturePendingCastSelection(
            playback = PlaybackState(
                analysisInFlight = true,
                lastJobId = "job123",
                playMode = PlaybackMode.Autocanonizer,
                jukeboxAudioMode = JukeboxAudioMode.Cowbell
            )
        )

        val byJobId = pending as PendingCastSelection.ByJobId
        assertEquals(JukeboxAudioMode.Off, byJobId.track.audioMode)
    }

    @Test
    fun capturePreservedCastTrackReturnsLocalWhenSourceUriPresent() {
        val preserved = capturePreservedCastTrack(
            PlaybackState(
                audioLoaded = true,
                analysisLoaded = true,
                lastJobId = "local-0123456789abcdef",
                localSourceUri = "content://audio/1",
                trackTitle = "Local Track",
                trackArtist = "Local Artist",
                jukeboxAudioMode = JukeboxAudioMode.EightD
            )
        )

        assertEquals(
            PreservedCastTrack.Local(
                cacheKey = "0123456789abcdef",
                sourceUri = "content://audio/1",
                title = "Local Track",
                artist = "Local Artist",
                audioMode = JukeboxAudioMode.EightD
            ),
            preserved
        )
    }

    @Test
    fun capturePreservedCastTrackClearsLocalMarkerOnDisconnect() {
        val disconnected = stateAfterCastDisconnect(
            UiState(
                playback = PlaybackState(
                    isCasting = true,
                    localSourceUri = "content://audio/1",
                    lastJobId = "local-0123456789abcdef"
                )
            )
        )

        assertNull(disconnected.playback.localSourceUri)
    }

    @Test
    fun capturePendingCastSelectionKeepsJobIdAndMetadataWhileLoadIsInFlight() {
        val pending = capturePendingCastSelection(
            PlaybackState(
                analysisInFlight = true,
                analysisProgress = 40,
                lastJobId = "job123",
                lastYouTubeId = "yt123",
                trackTitle = "Track",
                trackArtist = "Artist"
            )
        )

        assertEquals(
            PendingCastSelection.ByJobId(
                PreservedCastTrack.Server(
                    jobId = "job123",
                    youtubeId = "yt123",
                    title = "Track",
                    artist = "Artist",
                    audioMode = JukeboxAudioMode.Off
                )
            ),
            pending
        )
    }

    @Test
    fun capturePendingCastSelectionFallsBackToSourceBeforeTheJobIdIsKnown() {
        // A source load holds only the youtube id until the server answers with a job id.
        val pending = capturePendingCastSelection(
            PlaybackState(
                analysisInFlight = true,
                lastYouTubeId = "yt123",
                trackTitle = "Track",
                trackArtist = "Artist"
            )
        )

        assertEquals(
            PendingCastSelection.BySource(
                youtubeId = "yt123",
                title = "Track",
                artist = "Artist",
                tuningParams = null
            ),
            pending
        )
    }

    @Test
    fun capturePendingCastSelectionCarriesPendingTuningParams() {
        val pending = capturePendingCastSelection(
            playback = PlaybackState(analysisInFlight = true, lastJobId = "job123"),
            pendingTuningParams = "jb=1&thresh=45"
        )

        assertEquals(
            "jb=1&thresh=45",
            (pending as? PendingCastSelection.ByJobId)?.track?.tuningParams
        )
    }

    @Test
    fun capturePendingCastSelectionTreatsCalculatingAndAudioLoadingAsInFlight() {
        assertNotNull(
            capturePendingCastSelection(
                PlaybackState(analysisCalculating = true, lastJobId = "job123")
            )
        )
        assertNotNull(
            capturePendingCastSelection(
                PlaybackState(audioLoading = true, lastJobId = "job123")
            )
        )
    }

    @Test
    fun capturePendingCastSelectionReturnsNullForAFailedLoad() {
        // A failed load keeps its ids with every loading flag cleared; connecting to a cast
        // device to read the error must not start the track the load gave up on.
        val pending = capturePendingCastSelection(
            PlaybackState(
                analysisErrorMessage = "Loading failed.",
                lastJobId = "job123",
                lastYouTubeId = "yt123",
                trackTitle = "Track"
            )
        )

        assertNull(pending)
    }

    @Test
    fun capturePendingCastSelectionReturnsNullForLoadedTracksAndUnidentifiedLoads() {
        // A loaded track belongs to the preserved-track handoff.
        assertNull(
            capturePendingCastSelection(
                PlaybackState(audioLoaded = true, analysisLoaded = true, lastJobId = "job123")
            )
        )
        // A local track's analysis artifact hands itself off when it completes.
        assertNull(
            capturePendingCastSelection(
                PlaybackState(
                    audioLoading = true,
                    lastJobId = "local-0123456789abcdef",
                    localSourceUri = "content://audio/1"
                )
            )
        )
        // An upload in flight carries no id the receiver could pull.
        assertNull(
            capturePendingCastSelection(
                PlaybackState(analysisInFlight = true, trackTitle = "Uploaded Track")
            )
        )
    }

    @Test
    fun stateAfterCastDisconnectClearsCastStateAndDeactivatesPlaylist() {
        val playlist = JukeboxPlaylistState(
            tracks = listOf(
                PlaylistTrack("one", PlaylistTrackType.Server, "One", null),
                PlaylistTrack("two", PlaylistTrackType.Server, "Two", null)
            ),
            currentIndex = 1
        )
        val state = UiState(
            playback = PlaybackState(
                isCasting = true,
                castDeviceName = "Living Room"
            ),
            playlist = playlist
        )

        val updated = stateAfterCastDisconnect(state)

        assertFalse(updated.playback.isCasting)
        assertNull(updated.playback.castDeviceName)
        assertEquals(playlist.tracks, updated.playlist.tracks)
        assertEquals(-1, updated.playlist.currentIndex)
    }
}
