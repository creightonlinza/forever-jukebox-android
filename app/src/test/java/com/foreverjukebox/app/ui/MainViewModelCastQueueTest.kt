package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AnalysisResponse
import com.foreverjukebox.app.data.AnalysisStartResponse
import com.foreverjukebox.app.data.FavoriteTrack
import com.foreverjukebox.app.data.SpotifySearchItem
import com.foreverjukebox.app.data.TopSongItem
import com.foreverjukebox.app.data.YoutubeSearchItem
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelCastQueueTest {

    @Test
    fun tryQueueYoutubeAnalysisForCastSkipsBlankBaseUrl() = runTest {
        var called = false

        val queuedJobId = tryQueueYoutubeAnalysisForCast(
            baseUrl = "   ",
            youtubeId = "dQw4w9WgXcQ",
            title = "Track",
            artist = "Artist"
        ) { _, _, _, _ ->
            called = true
            AnalysisStartResponse(id = "job_123")
        }

        assertNull(queuedJobId)
        assertFalse(called)
    }

    @Test
    fun tryQueueYoutubeAnalysisForCastInvokesStartWithNormalizedValues() = runTest {
        var resolvedBaseUrl: String? = null
        var resolvedYoutubeId: String? = null
        var resolvedTitle: String? = null
        var resolvedArtist: String? = null

        val queuedJobId = tryQueueYoutubeAnalysisForCast(
            baseUrl = " https://api.example.com ",
            youtubeId = "dQw4w9WgXcQ",
            title = "Track",
            artist = "Artist"
        ) { baseUrl, youtubeId, title, artist ->
            resolvedBaseUrl = baseUrl
            resolvedYoutubeId = youtubeId
            resolvedTitle = title
            resolvedArtist = artist
            AnalysisStartResponse(id = "job_abc123")
        }

        assertEquals("job_abc123", queuedJobId)
        assertEquals("https://api.example.com", resolvedBaseUrl)
        assertEquals("dQw4w9WgXcQ", resolvedYoutubeId)
        assertEquals("Track", resolvedTitle)
        assertEquals("Artist", resolvedArtist)
    }

    @Test
    fun tryQueueYoutubeAnalysisForCastReturnsFalseWhenStartFails() = runTest {
        val queuedJobId = tryQueueYoutubeAnalysisForCast(
            baseUrl = "https://api.example.com",
            youtubeId = "dQw4w9WgXcQ",
            title = null,
            artist = null
        ) { _, _, _, _ ->
            throw IOException("boom")
        }

        assertNull(queuedJobId)
    }

    @Test
    fun tryQueueYoutubeAnalysisForCastSupportsNullMetadata() = runTest {
        var resolvedTitle: String? = "unexpected"
        var resolvedArtist: String? = "unexpected"

        val queuedJobId = tryQueueYoutubeAnalysisForCast(
            baseUrl = "https://api.example.com",
            youtubeId = "dQw4w9WgXcQ",
            title = null,
            artist = null
        ) { _, _, title, artist ->
            resolvedTitle = title
            resolvedArtist = artist
            AnalysisStartResponse(id = "job_metadata")
        }

        assertEquals("job_metadata", queuedJobId)
        assertNull(resolvedTitle)
        assertNull(resolvedArtist)
    }

    @Test
    fun resetSearchStateAfterTrackSelectionClearsTransientSelectionFields() {
        val original = SearchState(
            query = "daft punk",
            spotifyResults = listOf(SpotifySearchItem(id = "sp1", name = "Track")),
            youtubeMatches = listOf(YoutubeSearchItem(id = "yt1", title = "Track")),
            youtubeLoading = true,
            pendingTrackName = "Track",
            pendingTrackArtist = "Artist"
        )

        val reset = resetSearchStateAfterTrackSelection(original)

        assertEquals("", reset.query)
        assertTrue(reset.spotifyResults.isEmpty())
        assertTrue(reset.youtubeMatches.isEmpty())
        assertFalse(reset.youtubeLoading)
        assertNull(reset.pendingTrackName)
        assertNull(reset.pendingTrackArtist)
    }

    @Test
    fun resetSearchStateAfterTrackSelectionPreservesLibraryAndLoadingState() {
        val topSong = TopSongItem(youtubeId = "yt_top", title = "Top")
        val trendingSong = TopSongItem(youtubeId = "yt_trending", title = "Trending")
        val recentSong = TopSongItem(youtubeId = "yt_recent", title = "Recent")
        val original = SearchState(
            topSongs = listOf(topSong),
            topSongsLoading = true,
            trendingSongs = listOf(trendingSong),
            trendingSongsLoading = true,
            recentSongs = listOf(recentSong),
            recentSongsLoading = true,
            spotifyLoading = true
        )

        val reset = resetSearchStateAfterTrackSelection(original)

        assertEquals(listOf(topSong), reset.topSongs)
        assertTrue(reset.topSongsLoading)
        assertEquals(listOf(trendingSong), reset.trendingSongs)
        assertTrue(reset.trendingSongsLoading)
        assertEquals(listOf(recentSong), reset.recentSongs)
        assertTrue(reset.recentSongsLoading)
        assertTrue(reset.spotifyLoading)
    }

    @Test
    fun favoriteRemovalTrackIdsForDeletionIncludesSourceAndJobIdentity() {
        val playback = PlaybackState(
            lastSourceProvider = "youtube",
            lastSourceId = "dQw4w9WgXcQ",
            lastJobId = "job_123"
        )

        val trackIds = favoriteRemovalTrackIdsForDeletion(playback)

        assertTrue(trackIds.contains("src:youtube:dQw4w9WgXcQ"))
        assertTrue(trackIds.contains("job:job_123"))
    }

    @Test
    fun favoriteRemovalTrackIdsForDeletionUsesFallbackJobIdWhenPlaybackJobMissing() {
        val playback = PlaybackState()

        val trackIds = favoriteRemovalTrackIdsForDeletion(
            playback = playback,
            fallbackJobId = "job_fallback"
        )

        assertEquals(setOf("job:job_fallback"), trackIds)
    }

    @Test
    fun removeFavoritesForTrackIdsRemovesCanonicalAndLegacyMatches() {
        val favorites = listOf(
            FavoriteTrack(uniqueSongId = "dQw4w9WgXcQ", title = "Legacy", artist = "Artist"),
            FavoriteTrack(
                uniqueSongId = "src:youtube:dQw4w9WgXcQ",
                title = "Canonical",
                artist = "Artist"
            ),
            FavoriteTrack(uniqueSongId = "job:other", title = "Other", artist = "Artist")
        )

        val filtered = removeFavoritesForTrackIds(
            favorites = favorites,
            trackIds = setOf("src:youtube:dQw4w9WgXcQ")
        )

        assertEquals(1, filtered.size)
        assertEquals("job:other", filtered.first().uniqueSongId)
    }

    @Test
    fun resolveKnownJobIdForSourceFindsJobIdInTopSongs() {
        val state = UiState(
            search = SearchState(
                topSongs = listOf(
                    TopSongItem(
                        id = "job_top_1",
                        sourceProvider = "soundcloud",
                        sourceId = "sc_123",
                        title = "Top Song"
                    )
                )
            )
        )

        val resolved = resolveKnownJobIdForSource(
            state = state,
            sourceProvider = "soundcloud",
            sourceId = "sc_123"
        )

        assertEquals("job_top_1", resolved)
    }

    @Test
    fun resolveKnownJobIdForSourceFindsJobIdInTrendingAndRecent() {
        val state = UiState(
            search = SearchState(
                trendingSongs = listOf(
                    TopSongItem(
                        id = "job_trending_1",
                        sourceProvider = "bandcamp",
                        sourceId = "bc_42",
                        title = "Trending Song"
                    )
                ),
                recentSongs = listOf(
                    TopSongItem(
                        id = "job_recent_1",
                        sourceProvider = "upload",
                        sourceId = "upload_9",
                        title = "Recent Song"
                    )
                )
            )
        )

        val trendingResolved = resolveKnownJobIdForSource(
            state = state,
            sourceProvider = "bandcamp",
            sourceId = "bc_42"
        )
        val recentResolved = resolveKnownJobIdForSource(
            state = state,
            sourceProvider = "upload",
            sourceId = "upload_9"
        )

        assertEquals("job_trending_1", trendingResolved)
        assertEquals("job_recent_1", recentResolved)
    }

    @Test
    fun resolveKnownJobIdForSourceReturnsNullWhenMissingOrInvalid() {
        val state = UiState(
            search = SearchState(
                topSongs = listOf(
                    TopSongItem(
                        id = null,
                        sourceProvider = "soundcloud",
                        sourceId = "sc_123",
                        title = "Song"
                    )
                )
            )
        )

        assertNull(
            resolveKnownJobIdForSource(
                state = state,
                sourceProvider = "soundcloud",
                sourceId = "sc_123"
            )
        )
        assertNull(
            resolveKnownJobIdForSource(
                state = state,
                sourceProvider = "soundcloud",
                sourceId = "missing"
            )
        )
        assertNull(
            resolveKnownJobIdForSource(
                state = state,
                sourceProvider = " ",
                sourceId = "sc_123"
            )
        )
    }

    @Test
    fun shouldReuseLookupJobReturnsFalseForFailedLookupResponse() {
        val failed = AnalysisResponse(
            id = "job_1",
            youtubeId = "dQw4w9WgXcQ",
            status = "failed",
            error = "Blocked"
        )

        assertFalse(shouldReuseLookupJob(failed))
    }

    @Test
    fun shouldReuseLookupJobReturnsTrueForInProgressLookupResponse() {
        val queued = AnalysisResponse(
            id = "job_1",
            youtubeId = "dQw4w9WgXcQ",
            status = "queued"
        )
        val downloading = queued.copy(status = "downloading")
        val processing = queued.copy(status = "processing")

        assertTrue(shouldReuseLookupJob(queued))
        assertTrue(shouldReuseLookupJob(downloading))
        assertTrue(shouldReuseLookupJob(processing))
    }

    @Test
    fun shouldReuseLookupJobReturnsTrueForCompleteLookupResponse() {
        val complete = AnalysisResponse(
            id = "job_1",
            youtubeId = "dQw4w9WgXcQ",
            status = "complete"
        )

        assertTrue(shouldReuseLookupJob(complete))
    }

    @Test
    fun shouldReuseLookupJobReturnsFalseWhenLookupIsMissingOrIncomplete() {
        assertFalse(shouldReuseLookupJob(null))
        assertFalse(
            shouldReuseLookupJob(
                AnalysisResponse(
                    id = null,
                    youtubeId = "dQw4w9WgXcQ",
                    status = "complete"
                )
            )
        )
        assertTrue(
            shouldReuseLookupJob(
                AnalysisResponse(
                    id = "job_1",
                    youtubeId = null,
                    status = "complete"
                )
            )
        )
    }
}
