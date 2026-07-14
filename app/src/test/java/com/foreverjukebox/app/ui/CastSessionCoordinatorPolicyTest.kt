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
