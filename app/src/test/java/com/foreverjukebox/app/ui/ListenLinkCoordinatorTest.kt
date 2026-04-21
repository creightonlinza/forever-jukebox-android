package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenLinkCoordinatorTest {

    @Test
    fun buildShareUrlReturnsNullWhenNoTrackId() {
        val coordinator = createCoordinator(
            state = UiState(
                baseUrl = "https://example.com",
                playback = PlaybackState(playMode = PlaybackMode.Jukebox)
            )
        )

        val shareUrl = coordinator.buildShareUrl()

        assertNull(shareUrl)
    }

    @Test
    fun buildShareUrlUsesJukeboxTuningParams() {
        val coordinator = createCoordinator(
            state = UiState(
                baseUrl = "https://example.com/",
                playback = PlaybackState(
                    playMode = PlaybackMode.Jukebox,
                    lastYouTubeId = "dQw4w9WgXcQ"
                )
            ),
            tuningParams = "thresh=7&jb=1"
        )

        val shareUrl = coordinator.buildShareUrl()

        assertEquals(
            "https://example.com/listen/src%3Ayoutube%3AdQw4w9WgXcQ?thresh=7&jb=1",
            shareUrl
        )
    }

    @Test
    fun buildShareUrlUsesAutocanonizerModeParam() {
        val coordinator = createCoordinator(
            state = UiState(
                baseUrl = "https://example.com",
                playback = PlaybackState(
                    playMode = PlaybackMode.Autocanonizer,
                    lastJobId = "job_123"
                )
            )
        )

        val shareUrl = coordinator.buildShareUrl()

        assertEquals("https://example.com/listen/job%3Ajob_123?mode=autocanonizer", shareUrl)
    }

    @Test
    fun handleDeepLinkLoadsTrackAndTuningForMatchingBase() {
        val playbackModes = mutableListOf<PlaybackMode>()
        val loads = mutableListOf<LoadRequest>()
        val coordinator = createCoordinator(
            state = UiState(baseUrl = "https://example.com"),
            setPlaybackMode = { playbackModes += it },
            loadTrackByStableId = { stableId, title, artist, tuningParams ->
                loads += LoadRequest(stableId, title, artist, tuningParams)
            }
        )

        coordinator.handleDeepLink(
            "https://example.com/listen/src:youtube:yt123?mode=autocanonizer&thresh=9"
        )

        assertEquals(listOf(PlaybackMode.Autocanonizer), playbackModes)
        assertEquals(1, loads.size)
        assertEquals(
            LoadRequest(
                stableId = "src:youtube:yt123",
                title = null,
                artist = null,
                tuningParams = null
            ),
            loads.single()
        )
    }

    @Test
    fun handleDeepLinkIgnoresNonMatchingHost() {
        val playbackModes = mutableListOf<PlaybackMode>()
        val loads = mutableListOf<LoadRequest>()
        val coordinator = createCoordinator(
            state = UiState(baseUrl = "https://example.com"),
            setPlaybackMode = { playbackModes += it },
            loadTrackByStableId = { stableId, title, artist, tuningParams ->
                loads += LoadRequest(stableId, title, artist, tuningParams)
            }
        )

        coordinator.handleDeepLink("https://other.example/listen/yt123?thresh=9")

        assertTrue(playbackModes.isEmpty())
        assertTrue(loads.isEmpty())
    }

    @Test
    fun handleDeepLinkBuildsJukeboxTuningWithoutMode() {
        val loads = mutableListOf<LoadRequest>()
        val coordinator = createCoordinator(
            state = UiState(baseUrl = "https://example.com"),
            loadTrackByStableId = { stableId, title, artist, tuningParams ->
                loads += LoadRequest(stableId, title, artist, tuningParams)
            }
        )

        coordinator.handleDeepLink("https://example.com/listen/src:youtube:yt123?thresh=9")

        assertEquals(1, loads.size)
        assertEquals("thresh=9", loads.single().tuningParams)
    }

    @Test
    fun handleDeepLinkAcceptsCanonicalListenHostWhenBaseDiffers() {
        val loads = mutableListOf<LoadRequest>()
        val coordinator = createCoordinator(
            state = UiState(baseUrl = "https://api.foreverjukebox.internal"),
            loadTrackByStableId = { stableId, title, artist, tuningParams ->
                loads += LoadRequest(stableId, title, artist, tuningParams)
            }
        )

        coordinator.handleDeepLink(
            "https://foreverjukebox.com/listen/src:youtube:yt123?thresh=7&jb=1"
        )

        assertEquals(1, loads.size)
        assertEquals("src:youtube:yt123", loads.single().stableId)
        assertEquals("thresh=7&jb=1", loads.single().tuningParams)
    }

    private fun createCoordinator(
        state: UiState,
        tuningParams: String? = null,
        setPlaybackMode: (PlaybackMode) -> Unit = {},
        loadTrackByStableId: (String, String?, String?, String?) -> Unit = { _, _, _, _ -> }
    ): ListenLinkCoordinator {
        return ListenLinkCoordinator(
            buildTuningParamsString = { tuningParams },
            getState = { state },
            setPlaybackMode = setPlaybackMode,
            loadTrackByStableId = loadTrackByStableId
        )
    }

    private data class LoadRequest(
        val stableId: String,
        val title: String?,
        val artist: String?,
        val tuningParams: String?
    )
}
