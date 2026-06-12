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
                    lastYouTubeId = "dQw4w9WgXcQ",
                    lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2"
                )
            ),
            tuningParams = "thresh=7&jb=1"
        )

        val shareUrl = coordinator.buildShareUrl()

        assertEquals(
            "https://example.com/listen/a3f3c0dc73c6476c9db95c227f9206f2?thresh=7&jb=1",
            shareUrl
        )
    }

    @Test
    fun buildShareUrlPreservesAnchorBeatTuningParam() {
        val coordinator = createCoordinator(
            state = UiState(
                baseUrl = "https://example.com/",
                playback = PlaybackState(
                    playMode = PlaybackMode.Jukebox,
                    lastYouTubeId = "dQw4w9WgXcQ",
                    lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2"
                )
            ),
            tuningParams = "thresh=7&ab=22"
        )

        val shareUrl = coordinator.buildShareUrl()

        assertEquals(
            "https://example.com/listen/a3f3c0dc73c6476c9db95c227f9206f2?thresh=7&ab=22",
            shareUrl
        )
    }

    @Test
    fun buildShareUrlIncludesAudioModeInJukeboxTuningParams() {
        val coordinator = createCoordinator(
            state = UiState(
                baseUrl = "https://example.com/",
                playback = PlaybackState(
                    playMode = PlaybackMode.Jukebox,
                    jukeboxAudioMode = JukeboxAudioMode.Nightcore,
                    lastYouTubeId = "dQw4w9WgXcQ",
                    lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2"
                )
            ),
            tuningParams = "am=nightcore"
        )

        val shareUrl = coordinator.buildShareUrl()

        assertEquals(
            "https://example.com/listen/a3f3c0dc73c6476c9db95c227f9206f2?am=nightcore",
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
                    lastJobId = "0123456789abcdef0123456789abcdef"
                )
            )
        )

        val shareUrl = coordinator.buildShareUrl()

        assertEquals(
            "https://example.com/listen/0123456789abcdef0123456789abcdef?mode=autocanonizer",
            shareUrl
        )
    }

    @Test
    fun handleDeepLinkLoadsTrackAndTuningForMatchingBase() {
        val playbackModes = mutableListOf<PlaybackMode>()
        val loads = mutableListOf<LoadRequest>()
        val coordinator = createCoordinator(
            state = UiState(baseUrl = "https://example.com"),
            setPlaybackMode = { playbackModes += it },
            loadTrackById = { trackId, title, artist, tuningParams ->
                loads += LoadRequest(trackId, title, artist, tuningParams)
            }
        )

        coordinator.handleDeepLink(
            "https://example.com/listen/dQw4w9WgXcQ?mode=autocanonizer&thresh=9"
        )

        assertEquals(listOf(PlaybackMode.Autocanonizer), playbackModes)
        assertEquals(1, loads.size)
        assertEquals(
            LoadRequest(
                trackId = "dQw4w9WgXcQ",
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
            loadTrackById = { trackId, title, artist, tuningParams ->
                loads += LoadRequest(trackId, title, artist, tuningParams)
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
            loadTrackById = { trackId, title, artist, tuningParams ->
                loads += LoadRequest(trackId, title, artist, tuningParams)
            }
        )

        coordinator.handleDeepLink("https://example.com/listen/dQw4w9WgXcQ?thresh=9")

        assertEquals(1, loads.size)
        assertEquals("thresh=9", loads.single().tuningParams)
    }

    @Test
    fun handleDeepLinkPreservesAnchorBeatTuningParam() {
        val loads = mutableListOf<LoadRequest>()
        val coordinator = createCoordinator(
            state = UiState(baseUrl = "https://example.com"),
            loadTrackById = { trackId, title, artist, tuningParams ->
                loads += LoadRequest(trackId, title, artist, tuningParams)
            }
        )

        coordinator.handleDeepLink("https://example.com/listen/dQw4w9WgXcQ?thresh=9&ab=22")

        assertEquals(1, loads.size)
        assertEquals("thresh=9&ab=22", loads.single().tuningParams)
    }

    @Test
    fun handleDeepLinkKeepsAudioModeInJukeboxTuningParams() {
        val loads = mutableListOf<LoadRequest>()
        val coordinator = createCoordinator(
            state = UiState(baseUrl = "https://example.com"),
            loadTrackById = { trackId, title, artist, tuningParams ->
                loads += LoadRequest(trackId, title, artist, tuningParams)
            }
        )

        coordinator.handleDeepLink("https://example.com/listen/dQw4w9WgXcQ?am=lofi")

        assertEquals(1, loads.size)
        assertEquals("am=lofi", loads.single().tuningParams)
    }

    @Test
    fun handleDeepLinkAcceptsCanonicalListenHostWhenBaseDiffers() {
        val loads = mutableListOf<LoadRequest>()
        val coordinator = createCoordinator(
            state = UiState(baseUrl = "https://api.foreverjukebox.internal"),
            loadTrackById = { trackId, title, artist, tuningParams ->
                loads += LoadRequest(trackId, title, artist, tuningParams)
            }
        )

        coordinator.handleDeepLink(
            "https://foreverjukebox.com/listen/dQw4w9WgXcQ?thresh=7&jb=1"
        )

        assertEquals(1, loads.size)
        assertEquals("dQw4w9WgXcQ", loads.single().trackId)
        assertEquals("thresh=7&jb=1", loads.single().tuningParams)
    }

    private fun createCoordinator(
        state: UiState,
        tuningParams: String? = null,
        setPlaybackMode: (PlaybackMode) -> Unit = {},
        loadTrackById: (String, String?, String?, String?) -> Unit = { _, _, _, _ -> }
    ): ListenLinkCoordinator {
        return ListenLinkCoordinator(
            buildTuningParamsString = { tuningParams },
            getState = { state },
            setPlaybackMode = setPlaybackMode,
            loadTrackById = loadTrackById
        )
    }

    private data class LoadRequest(
        val trackId: String,
        val title: String?,
        val artist: String?,
        val tuningParams: String?
    )
}
