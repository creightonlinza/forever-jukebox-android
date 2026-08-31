package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.FavoriteSourceType
import com.foreverjukebox.app.data.FavoriteTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AutoFavoritePolicyTest {

    private val jobId = "a".repeat(32)
    private val otherJobId = "b".repeat(32)
    private val youtubeId = "dQw4w9WgXcQ"

    private fun loadedState(
        favorites: List<FavoriteTrack> = emptyList(),
        maxFavorites: Int = 150,
        playMode: PlaybackMode = PlaybackMode.Jukebox,
        trackTitle: String? = "Uploaded Song",
        trackArtist: String? = "Some Artist",
        lastYouTubeId: String? = null
    ): UiState {
        return UiState(
            favorites = favorites,
            maxFavorites = maxFavorites,
            playback = PlaybackState(
                playMode = playMode,
                trackTitle = trackTitle,
                trackArtist = trackArtist,
                trackDurationSeconds = 123.5,
                lastJobId = jobId,
                lastYouTubeId = lastYouTubeId
            )
        )
    }

    private fun autoFavorite(
        state: UiState,
        responseJobId: String? = jobId,
        pendingJobId: String? = jobId,
        sourceProvider: String? = "upload",
        sourceId: String? = null,
        engineTuningParams: () -> String? = { null }
    ): FavoriteTrack? {
        return autoFavoriteForUserSuppliedTrack(
            state = state,
            responseJobId = responseJobId,
            pendingJobId = pendingJobId,
            sourceProvider = sourceProvider,
            sourceId = sourceId,
            engineTuningParams = engineTuningParams
        )
    }

    @Test
    fun addsFavoriteWhenPendingMatchesResponseJob() {
        val favorite = autoFavorite(loadedState())

        assertEquals(jobId, favorite?.uniqueSongId)
        assertEquals("Uploaded Song", favorite?.title)
        assertEquals("Some Artist", favorite?.artist)
        assertEquals(123.5, favorite?.duration)
        assertEquals(FavoriteSourceType.Upload, favorite?.sourceType)
        assertNull(favorite?.playMode)
    }

    @Test
    fun skipsWhenPendingIsAbsentOrMismatched() {
        assertNull(autoFavorite(loadedState(), pendingJobId = null))
        assertNull(autoFavorite(loadedState(), pendingJobId = otherJobId))
        assertNull(autoFavorite(loadedState(), responseJobId = null))
    }

    @Test
    fun skipsWhenTrackAlreadyFavoritedByJobId() {
        val state = loadedState(
            favorites = listOf(
                FavoriteTrack(uniqueSongId = jobId, title = "Existing", artist = "")
            )
        )

        assertNull(autoFavorite(state))
    }

    @Test
    fun skipsWhenTrackAlreadyFavoritedByLegacyYoutubeId() {
        val state = loadedState(
            favorites = listOf(
                FavoriteTrack(uniqueSongId = youtubeId, title = "Existing", artist = "")
            ),
            lastYouTubeId = youtubeId
        )

        assertNull(autoFavorite(state))
    }

    @Test
    fun skipsSilentlyAtFavoritesLimit() {
        val state = loadedState(
            favorites = listOf(
                FavoriteTrack(uniqueSongId = otherJobId, title = "Other", artist = "")
            ),
            maxFavorites = 1
        )

        assertNull(autoFavorite(state))
    }

    @Test
    fun mapsSourceProviderToSourceType() {
        assertEquals(
            FavoriteSourceType.Upload,
            autoFavorite(loadedState(), sourceProvider = "upload")?.sourceType
        )
        assertEquals(
            FavoriteSourceType.Youtube,
            autoFavorite(loadedState(), sourceProvider = "youtube")?.sourceType
        )
        assertEquals(
            FavoriteSourceType.SoundCloud,
            autoFavorite(loadedState(), sourceProvider = "soundcloud")?.sourceType
        )
        assertEquals(
            FavoriteSourceType.Bandcamp,
            autoFavorite(loadedState(), sourceProvider = "bandcamp")?.sourceType
        )
    }

    @Test
    fun infersSourceTypeFromSourceIdWhenProviderMissing() {
        assertEquals(
            FavoriteSourceType.Youtube,
            autoFavorite(loadedState(), sourceProvider = null, sourceId = youtubeId)?.sourceType
        )
        assertEquals(
            FavoriteSourceType.Youtube,
            autoFavorite(loadedState(), sourceProvider = "vimeo", sourceId = youtubeId)?.sourceType
        )
        assertEquals(
            FavoriteSourceType.Upload,
            autoFavorite(loadedState(), sourceProvider = null, sourceId = "  ")?.sourceType
        )
        assertEquals(
            FavoriteSourceType.Upload,
            autoFavorite(loadedState(), sourceProvider = null, sourceId = null)?.sourceType
        )
    }

    @Test
    fun capturesTuningOnlyInJukeboxMode() {
        val jukebox = autoFavorite(
            loadedState(playMode = PlaybackMode.Jukebox),
            engineTuningParams = { "sl=2" }
        )
        assertEquals("sl=2", jukebox?.tuningParams)

        var engineConsulted = false
        val autocanonizer = autoFavorite(
            loadedState(playMode = PlaybackMode.Autocanonizer),
            engineTuningParams = {
                engineConsulted = true
                "sl=2"
            }
        )
        assertNull(autocanonizer?.tuningParams)
        assertFalse(engineConsulted)
    }

    @Test
    fun fallsBackToUntitledTitleAndBlankArtist() {
        val favorite = autoFavorite(loadedState(trackTitle = "  ", trackArtist = null))

        assertEquals("Untitled", favorite?.title)
        assertEquals("", favorite?.artist)
    }
}
