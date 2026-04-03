package com.foreverjukebox.app.ui

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
}
