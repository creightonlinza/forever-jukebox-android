package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorDisplayTest {

    @Test
    fun cleanStripsRepeatedErrorPrefixesAndCollapsesWhitespace() {
        val message = ErrorDisplay.clean("Error: ERROR: Unable   to\n download video data.")

        assertEquals("Unable to download video data.", message)
    }

    @Test
    fun formatUsesSoundCloudFetchFailureForDownloadUnavailable() {
        val message = ErrorDisplay.format(
            raw = "Error: ERROR: Unable to download video data.",
            errorCode = "download_unavailable",
            sourceProvider = "soundcloud"
        )

        assertEquals("SoundCloud fetch failed.", message)
    }

    @Test
    fun formatUsesBandcampFetchFailureForGenericRequestFailure() {
        val message = ErrorDisplay.format(
            raw = "Request failed (503)",
            sourceProvider = "bandcamp"
        )

        assertEquals("Bandcamp fetch failed.", message)
    }

    @Test
    fun formatPreservesNoBeatsMessageWithoutPrefix() {
        val message = ErrorDisplay.format(
            raw = "ERROR: No beats or downbeats were detected in this track."
        )

        assertEquals("No beats or downbeats were detected in this track.", message)
    }

    @Test
    fun formatPreservesTrackTooLongMessageWithoutPrefix() {
        val message = ErrorDisplay.format(
            raw = "Error: Track is too long. Maximum supported length is 20 minutes.",
            errorCode = "track_too_long",
            sourceProvider = "youtube"
        )

        assertEquals("Track is too long. Maximum supported length is 20 minutes.", message)
    }

    @Test
    fun cleanUsesCallerFallbackForBlankError() {
        val message = ErrorDisplay.clean(" \n\t ", fallback = "Load failed.")

        assertEquals("Load failed.", message)
    }

    @Test
    fun inferProviderRecognizesSupportedInputs() {
        assertEquals("youtube", ErrorDisplay.inferProviderFromUrl("dQw4w9WgXcQ"))
        assertEquals(
            "youtube",
            ErrorDisplay.inferProviderFromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            "youtube",
            ErrorDisplay.inferProviderFromUrl("https://youtu.be/dQw4w9WgXcQ")
        )
        assertEquals(
            "soundcloud",
            ErrorDisplay.inferProviderFromUrl("https://soundcloud.com/artist/track")
        )
        assertEquals(
            "bandcamp",
            ErrorDisplay.inferProviderFromUrl("https://artist.bandcamp.com/track/song")
        )
        assertNull(ErrorDisplay.inferProviderFromUrl("https://example.com/track/song"))
    }
}
