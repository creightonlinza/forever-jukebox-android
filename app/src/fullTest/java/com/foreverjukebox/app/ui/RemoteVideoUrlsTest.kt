package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteVideoUrlsTest {

    @Test
    fun youtubeUrlsUseTheVideoIdWithoutChangingTheSearchApi() {
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            youtubeThumbnailUrl("dQw4w9WgXcQ")
        )
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            youtubeWatchUrl("dQw4w9WgXcQ")
        )
    }

    @Test
    fun youtubeUrlsTrimValidVideoIds() {
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            youtubeThumbnailUrl(" dQw4w9WgXcQ ")
        )
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            youtubeWatchUrl(" dQw4w9WgXcQ ")
        )
    }
}
