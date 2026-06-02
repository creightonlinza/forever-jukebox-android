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

    @Test
    fun sortFavoritesForDisplayDefaultsToTitleAscending() {
        val favorites = listOf(
            favorite("song:b", title = "banana", artist = "Diane"),
            favorite("song:u", title = "", artist = "Alice"),
            favorite("song:a", title = "Apple", artist = "Carol")
        )

        val result = sortFavoritesForDisplay(
            favorites,
            FavoriteSortKey.Title,
            FavoriteSortDirection.Ascending
        )

        assertEquals(
            listOf("song:a", "song:b", "song:u"),
            result.map { it.uniqueSongId }
        )
    }

    @Test
    fun sortFavoritesForDisplaySortsTitleDescending() {
        val favorites = listOf(
            favorite("song:b", title = "banana", artist = "Diane"),
            favorite("song:u", title = "", artist = "Alice"),
            favorite("song:a", title = "Apple", artist = "Carol")
        )

        val result = sortFavoritesForDisplay(
            favorites,
            FavoriteSortKey.Title,
            FavoriteSortDirection.Descending
        )

        assertEquals(
            listOf("song:u", "song:b", "song:a"),
            result.map { it.uniqueSongId }
        )
    }

    @Test
    fun sortFavoritesForDisplaySortsArtistAscendingAndDescending() {
        val favorites = listOf(
            favorite("song:c", title = "C", artist = "Carol"),
            favorite("song:a", title = "A", artist = "Alice"),
            favorite("song:b", title = "B", artist = "bob")
        )

        val ascending = sortFavoritesForDisplay(
            favorites,
            FavoriteSortKey.Artist,
            FavoriteSortDirection.Ascending
        )
        val descending = sortFavoritesForDisplay(
            favorites,
            FavoriteSortKey.Artist,
            FavoriteSortDirection.Descending
        )

        assertEquals(listOf("song:a", "song:b", "song:c"), ascending.map { it.uniqueSongId })
        assertEquals(listOf("song:c", "song:b", "song:a"), descending.map { it.uniqueSongId })
    }

    @Test
    fun favoriteDisplayArtistHidesBlankAndUnknownArtists() {
        assertEquals("", favoriteDisplayArtist(null))
        assertEquals("", favoriteDisplayArtist(""))
        assertEquals("", favoriteDisplayArtist("   "))
        assertEquals("", favoriteDisplayArtist("Unknown"))
        assertEquals("", favoriteDisplayArtist(" unknown "))
        assertEquals("Alice", favoriteDisplayArtist(" Alice "))
    }

    @Test
    fun sortFavoritesForDisplayUsesOtherColumnThenIdAsTieBreakers() {
        val favorites = listOf(
            favorite("song:c", title = "Same", artist = "Beta"),
            favorite("song:b", title = "Same", artist = "Alpha"),
            favorite("song:a", title = "Same", artist = "Alpha")
        )

        val result = sortFavoritesForDisplay(
            favorites,
            FavoriteSortKey.Title,
            FavoriteSortDirection.Ascending
        )

        assertEquals(
            listOf("song:a", "song:b", "song:c"),
            result.map { it.uniqueSongId }
        )
    }

    @Test
    fun favoriteSortDirectionTogglesBetweenAscendingAndDescending() {
        assertEquals(FavoriteSortDirection.Descending, FavoriteSortDirection.Ascending.toggled())
        assertEquals(FavoriteSortDirection.Ascending, FavoriteSortDirection.Descending.toggled())
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
