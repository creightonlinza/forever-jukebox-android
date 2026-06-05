package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.FavoriteTrack
import com.foreverjukebox.app.data.SOURCE_PROVIDER_YOUTUBE
import com.foreverjukebox.app.data.TopSongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMetadataPolicyTest {

    @Test
    fun resolveTrackLoadMetadataPrefersExplicitMetadataOverLookup() {
        val metadata = resolveTrackLoadMetadata(
            trackId = "dQw4w9WgXcQ",
            title = " Explicit Track ",
            artist = " Explicit Artist ",
            search = searchWithTopSong(
                sourceId = "dQw4w9WgXcQ",
                title = "Top Track",
                artist = "Top Artist"
            ),
            favorites = listOf(favorite("dQw4w9WgXcQ", "Favorite Track", "Favorite Artist"))
        )

        assertEquals("Explicit Track", metadata.title)
        assertEquals("Explicit Artist", metadata.artist)
    }

    @Test
    fun resolveTrackLoadMetadataFallsBackToLookupWhenExplicitMetadataIsBlank() {
        val metadata = resolveTrackLoadMetadata(
            trackId = "dQw4w9WgXcQ",
            title = " ",
            artist = "",
            search = searchWithTopSong(
                sourceId = "dQw4w9WgXcQ",
                title = "Top Track",
                artist = "Top Artist"
            ),
            favorites = emptyList()
        )

        assertEquals("Top Track", metadata.title)
        assertEquals("Top Artist", metadata.artist)
    }

    @Test
    fun resolveTrackMetaFindsTopSongByCanonicalYoutubeSourceId() {
        val metadata = resolveTrackMeta(
            trackId = "dQw4w9WgXcQ",
            search = searchWithTopSong(
                sourceId = "dQw4w9WgXcQ",
                title = "Top Track",
                artist = "Top Artist"
            ),
            favorites = emptyList()
        )

        assertEquals("Top Track", metadata.title)
        assertEquals("Top Artist", metadata.artist)
    }

    @Test
    fun resolveTrackMetaFindsTrendingSongByJobId() {
        val metadata = resolveTrackMeta(
            trackId = "job_123",
            search = SearchState(
                trendingSongs = listOf(
                    TopSongItem(
                        id = "job_123",
                        title = "Trending Track",
                        artist = "Trending Artist"
                    )
                )
            ),
            favorites = emptyList()
        )

        assertEquals("Trending Track", metadata.title)
        assertEquals("Trending Artist", metadata.artist)
    }

    @Test
    fun resolveTrackMetaUsesFavoritesWhenFeedsDoNotMatch() {
        val metadata = resolveTrackMeta(
            trackId = "favorite_job",
            search = searchWithTopSong(
                sourceId = "dQw4w9WgXcQ",
                title = "Top Track",
                artist = "Top Artist"
            ),
            favorites = listOf(favorite("favorite_job", "Favorite Track", "Favorite Artist"))
        )

        assertEquals("Favorite Track", metadata.title)
        assertEquals("Favorite Artist", metadata.artist)
    }

    @Test
    fun resolveTrackMetaReturnsNullMetadataForUnknownTrack() {
        val metadata = resolveTrackMeta(
            trackId = "unknown_job",
            search = searchWithTopSong(
                sourceId = "dQw4w9WgXcQ",
                title = "Top Track",
                artist = "Top Artist"
            ),
            favorites = listOf(favorite("favorite_job", "Favorite Track", "Favorite Artist"))
        )

        assertNull(metadata.title)
        assertNull(metadata.artist)
    }

    private fun searchWithTopSong(
        sourceId: String,
        title: String,
        artist: String
    ): SearchState {
        return SearchState(
            topSongs = listOf(
                TopSongItem(
                    sourceId = sourceId,
                    sourceProvider = SOURCE_PROVIDER_YOUTUBE,
                    title = title,
                    artist = artist
                )
            )
        )
    }

    private fun favorite(
        uniqueSongId: String,
        title: String,
        artist: String
    ): FavoriteTrack {
        return FavoriteTrack(
            uniqueSongId = uniqueSongId,
            title = title,
            artist = artist
        )
    }
}
