package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTextSourceTest {

    private fun firstSupported(text: String?, subject: String? = null): NormalizedSourceUrl? =
        sharedSourceCandidates(text, subject).firstNotNullOfOrNull { normalizeSupportedSourceUrl(it) }

    @Test
    fun youtubeShareWithTitleLineExtractsUrl() {
        val normalized = firstSupported(
            "Never Gonna Give You Up\nhttps://youtu.be/dQw4w9WgXcQ?si=AbCdEf"
        )

        assertEquals("dQw4w9WgXcQ", normalized?.youtubeId)
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=AbCdEf", normalized?.url)
    }

    @Test
    fun bareUrlShareIsAccepted() {
        val normalized = firstSupported("https://youtu.be/dQw4w9WgXcQ")

        assertEquals("youtube", normalized?.provider)
    }

    @Test
    fun wholeTextBareYoutubeIdIsAccepted() {
        val normalized = firstSupported("  dQw4w9WgXcQ  ")

        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", normalized?.url)
    }

    @Test
    fun elevenCharacterWordInProseIsNotReadAsVideoId() {
        assertNull(firstSupported("listen to Blackbirds1 today"))
    }

    @Test
    fun trailingPunctuationIsStripped() {
        val normalized = firstSupported("Check this out https://youtu.be/dQw4w9WgXcQ!")

        assertEquals("https://youtu.be/dQw4w9WgXcQ", normalized?.url)
    }

    @Test
    fun wrappingParenthesesAreStripped() {
        val normalized = firstSupported("(https://soundcloud.com/artist/track)")

        assertEquals("soundcloud", normalized?.provider)
        assertEquals("https://soundcloud.com/artist/track", normalized?.url)
    }

    @Test
    fun bandcampShareWithTitleLineExtractsUrl() {
        val normalized = firstSupported("Song, by Artist\nhttps://artist.bandcamp.com/track/song")

        assertEquals("bandcamp", normalized?.provider)
    }

    @Test
    fun httpSchemeIsExtractedFromProse() {
        val normalized = firstSupported("see http://on.soundcloud.com/xyz now")

        assertEquals("soundcloud", normalized?.provider)
    }

    @Test
    fun firstSupportedUrlWinsWhenSeveralArePresent() {
        val normalized = firstSupported(
            "https://example.com/x https://youtu.be/dQw4w9WgXcQ"
        )

        assertEquals("dQw4w9WgXcQ", normalized?.youtubeId)
    }

    @Test
    fun unsupportedHostIsRejected() {
        assertNull(firstSupported("https://example.com/song"))
    }

    @Test
    fun subjectSuppliesUrlWhenBodyHasNone() {
        val normalized = firstSupported(
            text = "Great track",
            subject = "https://youtu.be/dQw4w9WgXcQ"
        )

        assertEquals("dQw4w9WgXcQ", normalized?.youtubeId)
    }

    @Test
    fun bodyUrlBeatsSubjectUrl() {
        val normalized = firstSupported(
            text = "listen https://soundcloud.com/artist/track",
            subject = "https://youtu.be/dQw4w9WgXcQ"
        )

        assertEquals("soundcloud", normalized?.provider)
    }

    /**
     * A listen link is a Forever Jukebox track id, not a user-supplied source, so it has to reach
     * the caller as a candidate while failing source normalization.
     */
    @Test
    fun listenLinkSurvivesAsCandidateButIsNotASupportedSource() {
        val link = "https://foreverjukebox.com/listen/abc123?mode=autocanonizer"

        assertTrue(link in sharedSourceCandidates(link))
        assertNull(normalizeSupportedSourceUrl(link))
    }

    @Test
    fun blankInputsProduceNoCandidates() {
        assertEquals(emptyList<String>(), sharedSourceCandidates(null, null))
        assertEquals(emptyList<String>(), sharedSourceCandidates("", ""))
        assertEquals(emptyList<String>(), sharedSourceCandidates("   ", "  "))
    }

    @Test
    fun candidateListIsCapped() {
        val text = (1..10).joinToString(" ") { "https://example.com/$it" }

        assertTrue(sharedSourceCandidates(text).size <= 5)
    }

    @Test
    fun duplicateUrlsAreCollapsed() {
        val text = "https://youtu.be/dQw4w9WgXcQ https://youtu.be/dQw4w9WgXcQ"

        assertEquals(2, sharedSourceCandidates(text).size)
    }

    @Test
    fun shareWaitsWhileAppModeGateIsShowing() {
        assertEquals(
            ShareReadiness.Wait,
            resolveShareReadiness(
                showAppModeGate = true,
                appMode = null,
                baseUrlLoaded = false,
                baseUrl = "",
                serverConfigPending = true
            )
        )
    }

    @Test
    fun localModeReportsNotServerMode() {
        assertEquals(
            ShareReadiness.NotServerMode,
            resolveShareReadiness(
                showAppModeGate = false,
                appMode = AppMode.Local,
                baseUrlLoaded = true,
                baseUrl = "https://example.com",
                serverConfigPending = false
            )
        )
    }

    @Test
    fun shareWaitsWhileBaseUrlIsUnread() {
        assertEquals(
            ShareReadiness.Wait,
            resolveShareReadiness(
                showAppModeGate = false,
                appMode = AppMode.Server,
                baseUrlLoaded = false,
                baseUrl = "",
                serverConfigPending = false
            )
        )
    }

    @Test
    fun settledBlankBaseUrlReportsNoServer() {
        assertEquals(
            ShareReadiness.NoServer,
            resolveShareReadiness(
                showAppModeGate = false,
                appMode = AppMode.Server,
                baseUrlLoaded = true,
                baseUrl = "  ",
                serverConfigPending = false
            )
        )
    }

    @Test
    fun shareWaitsWhileServerConfigIsPending() {
        assertEquals(
            ShareReadiness.Wait,
            resolveShareReadiness(
                showAppModeGate = false,
                appMode = AppMode.Server,
                baseUrlLoaded = true,
                baseUrl = "https://example.com",
                serverConfigPending = true
            )
        )
    }

    @Test
    fun settledServerStateIsReady() {
        assertEquals(
            ShareReadiness.Ready,
            resolveShareReadiness(
                showAppModeGate = false,
                appMode = AppMode.Server,
                baseUrlLoaded = true,
                baseUrl = "https://example.com",
                serverConfigPending = false
            )
        )
    }

    @Test
    fun everyCandidateIsInspectedNotJustTheFirst() {
        val candidates = sharedSourceCandidates("watch https://youtu.be/dQw4w9WgXcQ")

        assertEquals("watch https://youtu.be/dQw4w9WgXcQ", candidates.first())
        assertNotNull(candidates.firstNotNullOfOrNull { normalizeSupportedSourceUrl(it) })
    }
}
