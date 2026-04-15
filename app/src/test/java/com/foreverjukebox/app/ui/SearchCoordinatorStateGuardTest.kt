package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.ApiClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertFalse
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
                logError = { _, _ -> Unit }
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
}
