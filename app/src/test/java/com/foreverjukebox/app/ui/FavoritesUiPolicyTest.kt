package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.FavoriteSourceType
import com.foreverjukebox.app.data.FavoriteTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesUiPolicyTest {

    @Test
    fun spinnerShowsOnlyWhenSyncPathExistsAndListenToggleIsInFlight() {
        val inFlightWithSync = UiState(
            allowFavoritesSync = true,
            favoritesSyncCode = "alpha-beta-gamma",
            listenFavoriteToggleInFlight = true
        )
        val inFlightWithoutCode = inFlightWithSync.copy(favoritesSyncCode = null)
        val inFlightSyncDisabled = inFlightWithSync.copy(allowFavoritesSync = false)
        val notInFlight = inFlightWithSync.copy(listenFavoriteToggleInFlight = false)

        assertTrue(shouldShowListenFavoriteSpinner(inFlightWithSync))
        assertFalse(shouldShowListenFavoriteSpinner(inFlightWithoutCode))
        assertFalse(shouldShowListenFavoriteSpinner(inFlightSyncDisabled))
        assertFalse(shouldShowListenFavoriteSpinner(notInFlight))
    }

    @Test
    fun secondTapIsBlockedWhenListenFavoriteSyncIsInFlight() {
        val blockedState = UiState(
            allowFavoritesSync = true,
            favoritesSyncCode = "alpha-beta-gamma",
            listenFavoriteToggleInFlight = true
        )
        val localOnlyState = blockedState.copy(
            allowFavoritesSync = false,
            listenFavoriteToggleInFlight = true
        )

        assertTrue(shouldBlockListenFavoriteToggle(blockedState))
        assertFalse(shouldBlockListenFavoriteToggle(localOnlyState))
    }

    @Test
    fun filterFavoritesReturnsOriginalListForBlankQuery() {
        val favorites = sampleFavorites()

        val result = filterFavorites(favorites, "   ")

        assertSame(favorites, result)
    }

    @Test
    fun filterFavoritesMatchesTitleArtistIdAndSourceCaseInsensitively() {
        val favorites = sampleFavorites()

        assertEquals(listOf("yt:one"), filterFavorites(favorites, " one ").map { it.uniqueSongId })
        assertEquals(listOf("sc:two"), filterFavorites(favorites, "ALICE").map { it.uniqueSongId })
        assertEquals(listOf("bc:three"), filterFavorites(favorites, "THREE").map { it.uniqueSongId })
        assertEquals(listOf("sc:two"), filterFavorites(favorites, "soundcloud").map { it.uniqueSongId })
    }

    @Test
    fun filterFavoritesPreservesSavedOrderWhenHidingNonMatches() {
        val favorites = sampleFavorites()

        val result = filterFavorites(favorites, "song")

        assertEquals(
            listOf("yt:one", "sc:two", "bc:three"),
            result.map { it.uniqueSongId }
        )
    }

    private fun sampleFavorites(): List<FavoriteTrack> {
        return listOf(
            FavoriteTrack(
                uniqueSongId = "yt:one",
                title = "First Song",
                artist = "Shared Artist",
                sourceType = FavoriteSourceType.Youtube
            ),
            FavoriteTrack(
                uniqueSongId = "sc:two",
                title = "Second Song",
                artist = "Alice",
                sourceType = FavoriteSourceType.SoundCloud
            ),
            FavoriteTrack(
                uniqueSongId = "bc:three",
                title = "Third Song",
                artist = "Shared Artist",
                sourceType = FavoriteSourceType.Bandcamp
            )
        )
    }
}
