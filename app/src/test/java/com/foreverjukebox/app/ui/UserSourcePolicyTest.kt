package com.foreverjukebox.app.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserSourcePolicyTest {

    @Test
    fun bareYoutubeIdExpandsToWatchUrl() {
        val normalized = normalizeSupportedSourceUrl(" dQw4w9WgXcQ ")

        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", normalized?.url)
        assertEquals("youtube", normalized?.provider)
        assertEquals("dQw4w9WgXcQ", normalized?.youtubeId)
    }

    @Test
    fun watchUrlKeepsQueryAndExtractsId() {
        val normalized = normalizeSupportedSourceUrl(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"
        )

        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s", normalized?.url)
        assertEquals("dQw4w9WgXcQ", normalized?.youtubeId)
    }

    @Test
    fun fragmentIsStripped() {
        val normalized = normalizeSupportedSourceUrl(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ#fragment"
        )

        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", normalized?.url)
        assertEquals("youtube", normalized?.provider)
    }

    @Test
    fun youtuBeShortLinkExtractsId() {
        val normalized = normalizeSupportedSourceUrl("https://youtu.be/dQw4w9WgXcQ?si=abc")

        assertEquals("youtube", normalized?.provider)
        assertEquals("dQw4w9WgXcQ", normalized?.youtubeId)
    }

    @Test
    fun soundcloudAndBandcampHostsAccepted() {
        assertEquals(
            "soundcloud",
            normalizeSupportedSourceUrl("https://soundcloud.com/artist/track")?.provider
        )
        assertEquals(
            "soundcloud",
            normalizeSupportedSourceUrl("http://on.soundcloud.com/xyz")?.provider
        )
        assertEquals(
            "bandcamp",
            normalizeSupportedSourceUrl("https://artist.bandcamp.com/track/song")?.provider
        )
    }

    @Test
    fun bandcampUrlHasNoYoutubeId() {
        assertNull(normalizeSupportedSourceUrl("https://artist.bandcamp.com/track/song")?.youtubeId)
    }

    @Test
    fun unsupportedInputsRejected() {
        assertNull(normalizeSupportedSourceUrl(""))
        assertNull(normalizeSupportedSourceUrl("   "))
        assertNull(normalizeSupportedSourceUrl("not a url"))
        assertNull(normalizeSupportedSourceUrl("https://vimeo.com/12345"))
        assertNull(normalizeSupportedSourceUrl("ftp://youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(normalizeSupportedSourceUrl("https://evilyoutube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(normalizeSupportedSourceUrl("shortid1"))
        assertNull(normalizeSupportedSourceUrl("dQw4w9WgXcQx"))
    }

    @Test
    fun playlistUrlWithoutVideoIdStillNormalizes() {
        val normalized = normalizeSupportedSourceUrl(
            "https://www.youtube.com/playlist?list=PL123"
        )

        assertEquals("youtube", normalized?.provider)
        assertNull(normalized?.youtubeId)
    }

    @Test
    fun allowedExtensionPassesThroughCaseInsensitively() {
        val name = resolveUploadFileName("Track.MP3", null, listOf(".mp3"))

        assertEquals("Track.MP3", name)
    }

    @Test
    fun missingExtensionDerivedFromMime() {
        val name = resolveUploadFileName("My Song", "audio/mpeg", listOf(".mp3", ".m4a"))

        assertEquals("My Song.mp3", name)
    }

    @Test
    fun disallowedDerivedExtensionRejected() {
        assertNull(resolveUploadFileName("My Song", "audio/webm", listOf(".mp3")))
    }

    @Test
    fun unknownMimeAndExtensionRejected() {
        assertNull(resolveUploadFileName("notes.txt", "text/plain", listOf(".mp3")))
        assertNull(resolveUploadFileName("mystery", null, listOf(".mp3")))
    }

    @Test
    fun blankDisplayNameFallsBackToGenericStem() {
        val name = resolveUploadFileName(null, "audio/flac", listOf(".flac"))

        assertEquals("audio.flac", name)
    }

    @Test
    fun emptyAllowedListFallsBackToServerDefaults() {
        assertEquals("Track.ogg", resolveUploadFileName("Track.ogg", null, emptyList()))
    }

    @Test
    fun uploadTitleReplacesSeparatorsWithSpaces() {
        assertEquals("My Cool Track", uploadTitleFromFileName("My_Cool-Track.mp3"))
        assertEquals("plain", uploadTitleFromFileName("plain"))
    }

    @Test
    fun pickerMimesIncludeNonAudioTypesOnlyWhenAllowed() {
        assertArrayEquals(
            arrayOf("audio/*", "application/ogg", "video/webm"),
            uploadMimeTypesForPicker(emptyList())
        )
        assertArrayEquals(
            arrayOf("audio/*"),
            uploadMimeTypesForPicker(listOf(".mp3", ".m4a"))
        )
    }

    @Test
    fun sizeLimitFormatsWholeAndFractionalMegabytes() {
        assertEquals("20 MB", formatUploadSizeLimitMb(20L * 1024 * 1024))
        assertEquals("2.5 MB", formatUploadSizeLimitMb((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun megabytesFormatSpacesTheUnitOnDemandAndFloorsAtZero() {
        assertEquals("20MB", formatMegabytes(20L * 1024 * 1024, unitSeparator = ""))
        assertEquals("2.5MB", formatMegabytes((2.5 * 1024 * 1024).toLong(), unitSeparator = ""))
        assertEquals("0MB", formatMegabytes(0L, unitSeparator = ""))
        assertEquals("0MB", formatMegabytes(-1L, unitSeparator = ""))
    }

    @Test
    fun megabytesKeepTheirMagnitudeBeyondIntRange() {
        // A limit this large only comes from a server spelling out "effectively unlimited", but the
        // number in the message still has to be the one it sent.
        assertEquals("314572800 MB", formatMegabytes(300L * 1024 * 1024 * 1024 * 1024))
    }

    @Test
    fun uploadTabExistsWhileEitherUserSourceIsAllowed() {
        assertEquals(true, uploadTabAvailable(allowUserUrl = true, allowUserUpload = false))
        assertEquals(true, uploadTabAvailable(allowUserUrl = false, allowUserUpload = true))
        assertEquals(false, uploadTabAvailable(allowUserUrl = false, allowUserUpload = false))
    }

    @Test
    fun searchPanelTabFallsBackToSearchWhenNoUserSourceIsAllowed() {
        assertEquals(
            SearchPanelTab.Search,
            coerceSearchPanelTab(SearchPanelTab.Upload, allowUserUrl = false, allowUserUpload = false)
        )
        assertEquals(
            SearchPanelTab.Upload,
            coerceSearchPanelTab(SearchPanelTab.Upload, allowUserUrl = true, allowUserUpload = false)
        )
        assertEquals(
            SearchPanelTab.Upload,
            coerceSearchPanelTab(SearchPanelTab.Upload, allowUserUrl = false, allowUserUpload = true)
        )
        assertEquals(
            SearchPanelTab.Search,
            coerceSearchPanelTab(SearchPanelTab.Search, allowUserUrl = true, allowUserUpload = true)
        )
    }
}
