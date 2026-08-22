package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistIdentityPolicyTest {

    @Test
    fun resolvesServerIdentityForLoadedTrack() {
        val jobId = "a3f3c0dc73c6476c9db95c227f9206f2"
        val state = UiState(
            appMode = AppMode.Server,
            playback = PlaybackState(
                lastJobId = jobId,
                audioLoaded = true,
                analysisLoaded = true
            )
        )

        val identity = loadedPlaylistTrackIdentityOrNull(state)

        assertEquals(PlaylistTrackIdentity(jobId, PlaylistTrackType.Server), identity)
    }

    @Test
    fun resolvesLocalCachedIdentityInLocalMode() {
        // Capture skips these: local tuning lives in the per-track file, not the entry.
        val state = UiState(
            appMode = AppMode.Local,
            playback = PlaybackState(
                lastJobId = "local-abc123",
                audioLoaded = true,
                analysisLoaded = true
            )
        )

        val identity = loadedPlaylistTrackIdentityOrNull(state)

        assertEquals(PlaylistTrackType.LocalCached, identity?.type)
    }

    @Test
    fun resolvesIdentityWhileCasting() {
        // Cast sessions never load audio on the phone, so the cast track stands in.
        val jobId = "a3f3c0dc73c6476c9db95c227f9206f2"
        val state = UiState(
            appMode = AppMode.Server,
            playback = PlaybackState(
                lastJobId = jobId,
                isCasting = true,
                audioLoaded = false,
                analysisLoaded = false
            )
        )

        val identity = loadedPlaylistTrackIdentityOrNull(state)

        assertEquals(PlaylistTrackIdentity(jobId, PlaylistTrackType.Server), identity)
    }

    @Test
    fun resolvesNoIdentityWithoutALoadedTrack() {
        val state = UiState(
            appMode = AppMode.Server,
            playback = PlaybackState(
                lastJobId = null,
                audioLoaded = false,
                analysisLoaded = false
            )
        )

        assertNull(loadedPlaylistTrackIdentityOrNull(state))
        assertNull(
            loadedPlaylistTrackIdentityOrNull(
                state.copy(playback = state.playback.copy(lastJobId = "   "))
            )
        )
    }
}
