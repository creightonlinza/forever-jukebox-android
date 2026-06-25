package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.ApiClient
import com.foreverjukebox.app.data.TopSongItem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchCoordinatorStateGuardTest {

    @Test
    fun resetRuntimeStateCancelsScheduledTopRefresh() = runTest {
        var currentState = UiState(
            appMode = null,
            baseUrl = "https://old.example.com",
            activeTab = TabId.Top
        )
        var searchState = SearchState()
        val loggedErrors = mutableListOf<String>()
        val coordinator = SearchCoordinator(
            scope = this,
            api = ApiClient(),
            getState = { currentState.copy(search = searchState) },
            updateSearchState = { transform ->
                searchState = transform(searchState)
                currentState = currentState.copy(search = searchState)
            },
            setSearchQuery = { query ->
                searchState = searchState.copy(query = query)
                currentState = currentState.copy(search = searchState)
            },
            logError = { message, _ -> loggedErrors += message }
        )

        coordinator.onTopTabActivated()
        currentState = currentState.copy(baseUrl = "https://new.example.com")
        coordinator.resetRuntimeState()
        advanceTimeBy(300)
        advanceUntilIdle()

        assertTrue(searchState.topSongs.isEmpty())
        assertFalse(searchState.topSongsLoading)
        assertTrue(loggedErrors.isEmpty())
    }

    @Test
    fun staleSpotifyResponseIsIgnoredAfterServerSwitchReset() = runTest {
        val releaseResponse = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                releaseResponse.await(2, TimeUnit.SECONDS)
                return MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {"items":[{"id":"old","name":"Old Song","artist":"Old Artist","duration":120.0}]}
                        """.trimIndent()
                    )
            }
        }
        server.start()
        try {
            var currentState = UiState(
                baseUrl = server.url("/").toString(),
                activeTab = TabId.Search
            )
            var searchState = SearchState()
            val coordinator = SearchCoordinator(
                scope = this,
                api = ApiClient(),
                getState = { currentState.copy(search = searchState) },
                updateSearchState = { transform ->
                    searchState = transform(searchState)
                    currentState = currentState.copy(search = searchState)
                },
                setSearchQuery = { query ->
                    searchState = searchState.copy(query = query)
                    currentState = currentState.copy(search = searchState)
                },
                logError = { _, _ -> }
            )

            coordinator.runSpotifySearch("old query")
            runCurrent()

            currentState = currentState.copy(baseUrl = "https://new.example.com")
            coordinator.resetRuntimeState()
            searchState = SearchState()
            currentState = currentState.copy(search = searchState)

            releaseResponse.countDown()
            advanceUntilIdle()

            assertTrue(searchState.spotifyResults.isEmpty())
            assertFalse(searchState.spotifyLoading)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun topFeedFailureSetsErrorAndAutomaticRetryCanRecover() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"items":[{"id":"job_top","title":"Top Song"}]}""")
        )
        server.start()
        try {
            var currentState = UiState(
                baseUrl = server.url("/").toString(),
                activeTab = TabId.Top
            )
            var searchState = SearchState()
            val loggedErrors = mutableListOf<String>()
            val coordinator = SearchCoordinator(
                scope = this,
                api = ApiClient(),
                getState = { currentState.copy(search = searchState) },
                updateSearchState = { transform ->
                    searchState = transform(searchState)
                    currentState = currentState.copy(search = searchState)
                },
                setSearchQuery = { query ->
                    searchState = searchState.copy(query = query)
                    currentState = currentState.copy(search = searchState)
                },
                logError = { message, _ -> loggedErrors += message }
            )

            coordinator.maybeRefreshForState(currentState)
            advanceUntilCondition { searchState.topSongsErrorMessage != null }

            assertEquals("Loading failed.", searchState.topSongsErrorMessage)
            assertFalse(searchState.topSongsLoading)
            assertTrue(searchState.topSongs.isEmpty())
            assertEquals(listOf("Song refresh failed for Top"), loggedErrors)

            coordinator.maybeRefreshForState(currentState)
            advanceUntilCondition { searchState.topSongs.isNotEmpty() }

            assertNull(searchState.topSongsErrorMessage)
            assertEquals(listOf(TopSongItem(id = "job_top", title = "Top Song")), searchState.topSongs)
            assertFalse(searchState.topSongsLoading)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun topFeedFailurePreservesExistingItems() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        server.start()
        try {
            val existingItems = listOf(TopSongItem(id = "job_old", title = "Old Top Song"))
            var currentState = UiState(
                baseUrl = server.url("/").toString(),
                activeTab = TabId.Top
            )
            var searchState = SearchState(topSongs = existingItems)
            val coordinator = SearchCoordinator(
                scope = this,
                api = ApiClient(),
                getState = { currentState.copy(search = searchState) },
                updateSearchState = { transform ->
                    searchState = transform(searchState)
                    currentState = currentState.copy(search = searchState)
                },
                setSearchQuery = { query ->
                    searchState = searchState.copy(query = query)
                    currentState = currentState.copy(search = searchState)
                },
                logError = { _, _ -> }
            )

            coordinator.refreshTopSongs()
            advanceUntilCondition { searchState.topSongsErrorMessage != null }

            assertEquals(existingItems, searchState.topSongs)
            assertEquals("Loading failed.", searchState.topSongsErrorMessage)
            assertFalse(searchState.topSongsLoading)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun recentFeedFailureSetsErrorInsteadOfEmptyState() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.start()
        try {
            var currentState = UiState(
                baseUrl = server.url("/").toString(),
                activeTab = TabId.Top,
                topSongsTab = TopSongsTab.Recent
            )
            var searchState = SearchState()
            val loggedErrors = mutableListOf<String>()
            val coordinator = SearchCoordinator(
                scope = this,
                api = ApiClient(),
                getState = { currentState.copy(search = searchState) },
                updateSearchState = { transform ->
                    searchState = transform(searchState)
                    currentState = currentState.copy(search = searchState)
                },
                setSearchQuery = { query ->
                    searchState = searchState.copy(query = query)
                    currentState = currentState.copy(search = searchState)
                },
                logError = { message, _ -> loggedErrors += message }
            )

            coordinator.refreshRecentSongs()
            advanceUntilCondition { searchState.recentSongsErrorMessage != null }

            assertEquals("Loading failed.", searchState.recentSongsErrorMessage)
            assertFalse(searchState.recentSongsLoading)
            assertTrue(searchState.recentSongs.isEmpty())
            assertEquals(listOf("Song refresh failed for Recent"), loggedErrors)
        } finally {
            server.shutdown()
        }
    }

    private fun TestScope.advanceUntilCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            advanceUntilIdle()
            if (condition()) {
                return
            }
            Thread.sleep(10)
        }
        advanceUntilIdle()
        assertTrue("Condition was not met before timeout", condition())
    }
}
