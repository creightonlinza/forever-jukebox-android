package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.ApiClient
import com.foreverjukebox.app.data.TOP_SONGS_LIMIT
import com.foreverjukebox.app.data.TopSongItem
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchCoordinator(
    private val scope: CoroutineScope,
    private val api: ApiClient,
    private val getState: () -> UiState,
    private val updateSearchState: ((SearchState) -> SearchState) -> Unit,
    private val setSearchQuery: (String) -> Unit,
    private val logError: (String, Throwable) -> Unit
) {
    private enum class SongsFeed {
        Top,
        Recent,
        Trending
    }

    private var refreshSongsJob: Job? = null
    private var topSongsRefreshJob: Job? = null
    private var recentSongsRefreshJob: Job? = null
    private var trendingSongsRefreshJob: Job? = null
    private var spotifySearchJob: Job? = null
    private var youtubeMatchesJob: Job? = null
    private var requestGeneration = 0L
    private var topSongsLoaded = false
    private var trendingSongsLoaded = false
    private var recentSongsLoaded = false

    fun resetRuntimeState() {
        requestGeneration += 1
        cancelAllJobs()
        topSongsLoaded = false
        trendingSongsLoaded = false
        recentSongsLoaded = false
    }

    fun onTopTabActivated() {
        scheduleSongsRefresh(SongsFeed.Top)
    }

    fun onTopSongsTabSelected(tab: TopSongsTab) {
        when (tab) {
            TopSongsTab.TopSongs -> scheduleSongsRefresh(SongsFeed.Top)
            TopSongsTab.Recent -> scheduleSongsRefresh(SongsFeed.Recent)
            TopSongsTab.Trending -> scheduleSongsRefresh(SongsFeed.Trending)
            TopSongsTab.Favorites -> Unit
        }
    }

    fun maybeRefreshForState(currentState: UiState) {
        if (currentState.activeTab != TabId.Top || currentState.baseUrl.isBlank()) {
            return
        }
        if (!topSongsLoaded) {
            refreshSongs(SongsFeed.Top)
        }
        if (currentState.topSongsTab == TopSongsTab.Trending && !trendingSongsLoaded) {
            refreshSongs(SongsFeed.Trending)
        }
        if (currentState.topSongsTab == TopSongsTab.Recent && !recentSongsLoaded) {
            refreshSongs(SongsFeed.Recent)
        }
    }

    fun refreshTopSongs() {
        refreshSongs(SongsFeed.Top)
    }

    fun refreshRecentSongs() {
        refreshSongs(SongsFeed.Recent)
    }

    fun refreshTrendingSongs() {
        refreshSongs(SongsFeed.Trending)
    }

    fun runSpotifySearch(query: String) {
        val baseUrl = getState().baseUrl.trim()
        if (baseUrl.isBlank()) return
        if (getState().search.spotifyLoading) return
        val generation = requestGeneration
        setSearchQuery(query)
        updateSearchState {
            it.copy(
                youtubeMatches = emptyList(),
                spotifyResults = emptyList(),
                spotifyLoading = true
            )
        }
        spotifySearchJob?.cancel()
        spotifySearchJob = scope.launch {
            try {
                val items = api.searchSpotify(baseUrl, query)
                if (!isRequestCurrent(generation, baseUrl)) {
                    return@launch
                }
                updateSearchState { it.copy(spotifyResults = items) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: IOException) {
                if (!isRequestCurrent(generation, baseUrl)) {
                    return@launch
                }
                logError("Spotify search failed", error)
                updateSearchState { it.copy(spotifyResults = emptyList()) }
            } catch (error: IllegalArgumentException) {
                if (!isRequestCurrent(generation, baseUrl)) {
                    return@launch
                }
                logError("Spotify search failed", error)
                updateSearchState { it.copy(spotifyResults = emptyList()) }
            } catch (error: IllegalStateException) {
                if (!isRequestCurrent(generation, baseUrl)) {
                    return@launch
                }
                logError("Spotify search failed", error)
                updateSearchState { it.copy(spotifyResults = emptyList()) }
            } finally {
                if (isRequestCurrent(generation, baseUrl)) {
                    updateSearchState { it.copy(spotifyLoading = false) }
                }
            }
        }
    }

    fun fetchYoutubeMatches(name: String, artist: String, duration: Double) {
        val baseUrl = getState().baseUrl.trim()
        if (baseUrl.isBlank()) return
        val generation = requestGeneration
        val query = if (artist.isNotBlank()) "$artist - $name" else name
        updateSearchState {
            it.copy(
                pendingTrackName = name,
                pendingTrackArtist = artist,
                spotifyResults = emptyList(),
                youtubeMatches = emptyList(),
                youtubeLoading = true
            )
        }
        youtubeMatchesJob?.cancel()
        youtubeMatchesJob = scope.launch {
            try {
                val items = api.searchYoutube(baseUrl, query, duration)
                if (!isRequestCurrent(generation, baseUrl)) {
                    return@launch
                }
                updateSearchState { it.copy(youtubeMatches = items) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: IOException) {
                if (!isRequestCurrent(generation, baseUrl)) {
                    return@launch
                }
                logError("YouTube match search failed", error)
                updateSearchState { it.copy(youtubeMatches = emptyList()) }
            } catch (error: IllegalArgumentException) {
                if (!isRequestCurrent(generation, baseUrl)) {
                    return@launch
                }
                logError("YouTube match search failed", error)
                updateSearchState { it.copy(youtubeMatches = emptyList()) }
            } catch (error: IllegalStateException) {
                if (!isRequestCurrent(generation, baseUrl)) {
                    return@launch
                }
                logError("YouTube match search failed", error)
                updateSearchState { it.copy(youtubeMatches = emptyList()) }
            } finally {
                if (isRequestCurrent(generation, baseUrl)) {
                    updateSearchState { it.copy(youtubeLoading = false) }
                }
            }
        }
    }

    private fun scheduleSongsRefresh(feed: SongsFeed) {
        val baseUrl = getState().baseUrl.trim()
        if (baseUrl.isBlank() || isFeedLoaded(feed)) return
        val generation = requestGeneration
        refreshSongsJob?.cancel()
        refreshSongsJob = scope.launch {
            delay(250)
            if (!isRequestCurrent(generation, baseUrl)) {
                return@launch
            }
            refreshSongs(feed)
        }
    }

    private fun refreshSongs(feed: SongsFeed) {
        val baseUrl = getState().baseUrl.trim()
        if (baseUrl.isBlank()) return
        val generation = requestGeneration
        currentFeedJob(feed)?.cancel()
        var feedJob: Job? = null
        feedJob = scope.launch {
            if (!isRequestCurrent(generation, baseUrl)) {
                return@launch
            }
            updateSearchState { setFeedLoading(it, feed, true) }
            try {
                val items = fetchSongs(feed, baseUrl)
                if (!isRequestCurrent(generation, baseUrl)) {
                    return@launch
                }
                markFeedLoaded(feed)
                updateSearchState { setFeedItems(it, feed, items) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: IOException) {
                if (!isRequestCurrent(generation, baseUrl)) {
                    return@launch
                }
                logError("Song refresh failed for $feed", error)
                updateSearchState { setFeedItems(it, feed, emptyList()) }
            } catch (error: IllegalArgumentException) {
                if (!isRequestCurrent(generation, baseUrl)) {
                    return@launch
                }
                logError("Song refresh failed for $feed", error)
                updateSearchState { setFeedItems(it, feed, emptyList()) }
            } catch (error: IllegalStateException) {
                if (!isRequestCurrent(generation, baseUrl)) {
                    return@launch
                }
                logError("Song refresh failed for $feed", error)
                updateSearchState { setFeedItems(it, feed, emptyList()) }
            } finally {
                if (isRequestCurrent(generation, baseUrl)) {
                    updateSearchState { setFeedLoading(it, feed, false) }
                }
                if (currentFeedJob(feed) === feedJob) {
                    setFeedJob(feed, null)
                }
            }
        }
        setFeedJob(feed, feedJob)
    }

    private suspend fun fetchSongs(feed: SongsFeed, baseUrl: String): List<TopSongItem> {
        return when (feed) {
            SongsFeed.Top -> api.fetchTopSongs(baseUrl, TOP_SONGS_LIMIT)
            SongsFeed.Recent -> api.fetchRecentSongs(baseUrl, TOP_SONGS_LIMIT)
            SongsFeed.Trending -> api.fetchTrendingSongs(baseUrl)
        }
    }

    private fun setFeedLoading(search: SearchState, feed: SongsFeed, loading: Boolean): SearchState {
        return when (feed) {
            SongsFeed.Top -> search.copy(topSongsLoading = loading)
            SongsFeed.Recent -> search.copy(recentSongsLoading = loading)
            SongsFeed.Trending -> search.copy(trendingSongsLoading = loading)
        }
    }

    private fun setFeedItems(search: SearchState, feed: SongsFeed, items: List<TopSongItem>): SearchState {
        return when (feed) {
            SongsFeed.Top -> search.copy(topSongs = items)
            SongsFeed.Recent -> search.copy(recentSongs = items)
            SongsFeed.Trending -> search.copy(trendingSongs = items)
        }
    }

    private fun isFeedLoaded(feed: SongsFeed): Boolean {
        return when (feed) {
            SongsFeed.Top -> topSongsLoaded
            SongsFeed.Recent -> recentSongsLoaded
            SongsFeed.Trending -> trendingSongsLoaded
        }
    }

    private fun markFeedLoaded(feed: SongsFeed) {
        when (feed) {
            SongsFeed.Top -> topSongsLoaded = true
            SongsFeed.Recent -> recentSongsLoaded = true
            SongsFeed.Trending -> trendingSongsLoaded = true
        }
    }

    private fun isRequestCurrent(generation: Long, baseUrl: String): Boolean {
        return generation == requestGeneration && getState().baseUrl.trim() == baseUrl
    }

    private fun currentFeedJob(feed: SongsFeed): Job? {
        return when (feed) {
            SongsFeed.Top -> topSongsRefreshJob
            SongsFeed.Recent -> recentSongsRefreshJob
            SongsFeed.Trending -> trendingSongsRefreshJob
        }
    }

    private fun setFeedJob(feed: SongsFeed, job: Job?) {
        when (feed) {
            SongsFeed.Top -> topSongsRefreshJob = job
            SongsFeed.Recent -> recentSongsRefreshJob = job
            SongsFeed.Trending -> trendingSongsRefreshJob = job
        }
    }

    private fun cancelAllJobs() {
        refreshSongsJob?.cancel()
        refreshSongsJob = null
        topSongsRefreshJob?.cancel()
        topSongsRefreshJob = null
        recentSongsRefreshJob?.cancel()
        recentSongsRefreshJob = null
        trendingSongsRefreshJob?.cancel()
        trendingSongsRefreshJob = null
        spotifySearchJob?.cancel()
        spotifySearchJob = null
        youtubeMatchesJob?.cancel()
        youtubeMatchesJob = null
    }
}
