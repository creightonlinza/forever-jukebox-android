package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            PreservedCastTrack(
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
    fun clearPlaylistOnCastDisconnectClearsCastStateAndPlaylist() {
        val state = UiState(
            playback = PlaybackState(
                isCasting = true,
                castDeviceName = "Living Room"
            ),
            playlist = JukeboxPlaylistState(
                tracks = listOf(
                    PlaylistTrack("one", PlaylistTrackType.Server, "One", null),
                    PlaylistTrack("two", PlaylistTrackType.Server, "Two", null)
                ),
                currentIndex = 1
            )
        )

        val updated = clearPlaylistOnCastDisconnect(state)

        assertFalse(updated.playback.isCasting)
        assertNull(updated.playback.castDeviceName)
        assertEquals(JukeboxPlaylistState(), updated.playlist)
    }
}
