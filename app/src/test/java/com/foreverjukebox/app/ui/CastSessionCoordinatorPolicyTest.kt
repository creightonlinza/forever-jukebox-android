package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
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
                trackArtist = "Artist"
            )
        )

        assertEquals(
            PreservedCastTrack(
                trackId = "yt123",
                youtubeId = "yt123",
                jobId = "job123",
                title = "Track",
                artist = "Artist"
            ),
            preserved
        )
    }
}
