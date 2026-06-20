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
    private val jobId = "a3f3c0dc73c6476c9db95c227f9206f2"
    private val secondJobId = "0123456789abcdef0123456789abcdef"
    private val thirdJobId = "ffffffffffffffffffffffffffffffff"

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
            AnalysisStartResponse(id = jobId)
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
            AnalysisStartResponse(id = jobId)
        }

        assertEquals(jobId, queuedJobId)
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
            AnalysisStartResponse(id = jobId)
        }

        assertEquals(jobId, queuedJobId)
        assertNull(resolvedTitle)
        assertNull(resolvedArtist)
    }

    @Test
    fun resolveYoutubeTrackSelectionPrefersPendingSpotifyMetadata() {
        val selection = resolveYoutubeTrackSelection(
            item = YoutubeSearchItem(
                id = "yt1",
                title = "Official Video With Extra Words"
            ),
            search = SearchState(
                pendingTrackName = "Spotify Track",
                pendingTrackArtist = "Spotify Artist"
            )
        )

        assertEquals("yt1", selection?.youtubeId)
        assertEquals("Spotify Track", selection?.title)
        assertEquals("Spotify Artist", selection?.artist)
    }

    @Test
    fun resolveYoutubeTrackSelectionNormalizesPendingSpotifyMetadata() {
        val selection = resolveYoutubeTrackSelection(
            item = YoutubeSearchItem(
                id = " yt1 ",
                title = "YouTube Title"
            ),
            search = SearchState(
                pendingTrackName = " Spotify Track ",
                pendingTrackArtist = "   "
            )
        )

        assertEquals("yt1", selection?.youtubeId)
        assertEquals("Spotify Track", selection?.title)
        assertNull(selection?.artist)
    }

    @Test
    fun resolveYoutubeTrackSelectionUsesYoutubeTitleWithoutPendingMetadata() {
        val selection = resolveYoutubeTrackSelection(
            item = YoutubeSearchItem(
                id = "yt1",
                title = " YouTube Title "
            ),
            search = SearchState()
        )

        assertEquals("yt1", selection?.youtubeId)
        assertEquals("YouTube Title", selection?.title)
        assertNull(selection?.artist)
    }

    @Test
    fun resolveYoutubeTrackSelectionRejectsBlankYoutubeId() {
        val selection = resolveYoutubeTrackSelection(
            item = YoutubeSearchItem(
                id = "   ",
                title = "YouTube Title"
            ),
            search = SearchState(
                pendingTrackName = "Spotify Track",
                pendingTrackArtist = "Spotify Artist"
            )
        )

        assertNull(selection)
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
            topSongsErrorMessage = "Loading failed.",
            trendingSongs = listOf(trendingSong),
            trendingSongsLoading = true,
            trendingSongsErrorMessage = "Loading failed.",
            recentSongs = listOf(recentSong),
            recentSongsLoading = true,
            recentSongsErrorMessage = "Loading failed.",
            spotifyLoading = true
        )

        val reset = resetSearchStateAfterTrackSelection(original)

        assertEquals(listOf(topSong), reset.topSongs)
        assertTrue(reset.topSongsLoading)
        assertEquals("Loading failed.", reset.topSongsErrorMessage)
        assertEquals(listOf(trendingSong), reset.trendingSongs)
        assertTrue(reset.trendingSongsLoading)
        assertEquals("Loading failed.", reset.trendingSongsErrorMessage)
        assertEquals(listOf(recentSong), reset.recentSongs)
        assertTrue(reset.recentSongsLoading)
        assertEquals("Loading failed.", reset.recentSongsErrorMessage)
        assertTrue(reset.spotifyLoading)
    }

    @Test
    fun favoriteRemovalTrackIdsForDeletionIncludesYoutubeAndJobIdentity() {
        val playback = PlaybackState(
            lastYouTubeId = "dQw4w9WgXcQ",
            lastJobId = jobId
        )

        val trackIds = favoriteRemovalTrackIdsForDeletion(playback)

        assertTrue(trackIds.contains("dQw4w9WgXcQ"))
        assertTrue(trackIds.contains(jobId))
    }

    @Test
    fun favoriteRemovalTrackIdsForDeletionUsesFallbackJobIdWhenPlaybackJobMissing() {
        val playback = PlaybackState()

        val trackIds = favoriteRemovalTrackIdsForDeletion(
            playback = playback,
            fallbackJobId = secondJobId
        )

        assertEquals(setOf(secondJobId), trackIds)
    }

    @Test
    fun removeFavoritesForTrackIdsRemovesMatchingSimpleIds() {
        val favorites = listOf(
            FavoriteTrack(uniqueSongId = "dQw4w9WgXcQ", title = "YouTube", artist = "Artist"),
            FavoriteTrack(uniqueSongId = jobId, title = "Job", artist = "Artist"),
            FavoriteTrack(uniqueSongId = "other", title = "Other", artist = "Artist")
        )

        val filtered = removeFavoritesForTrackIds(
            favorites = favorites,
            trackIds = setOf("dQw4w9WgXcQ", jobId)
        )

        assertEquals(1, filtered.size)
        assertEquals("other", filtered.first().uniqueSongId)
    }

    @Test
    fun resolveKnownJobIdForSourceFindsJobIdInTopSongs() {
        val state = UiState(
            search = SearchState(
                topSongs = listOf(
                    TopSongItem(
                        id = jobId,
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

        assertEquals(jobId, resolved)
    }

    @Test
    fun resolveKnownJobIdForSourceFindsJobIdInTrendingAndRecent() {
        val state = UiState(
            search = SearchState(
                trendingSongs = listOf(
                    TopSongItem(
                        id = secondJobId,
                        sourceProvider = "bandcamp",
                        sourceId = "bc_42",
                        title = "Trending Song"
                    )
                ),
                recentSongs = listOf(
                    TopSongItem(
                        id = thirdJobId,
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

        assertEquals(secondJobId, trendingResolved)
        assertEquals(thirdJobId, recentResolved)
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
    fun shouldReuseLookupJobReturnsTrueForFailedLookupResponse() {
        val failed = AnalysisResponse(
            id = jobId,
            youtubeId = "dQw4w9WgXcQ",
            status = "failed",
            error = "Blocked"
        )

        assertTrue(shouldReuseLookupJob(failed))
    }

    @Test
    fun shouldReuseLookupJobReturnsTrueForInProgressLookupResponse() {
        val queued = AnalysisResponse(
            id = jobId,
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
            id = jobId,
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
        assertFalse(
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
