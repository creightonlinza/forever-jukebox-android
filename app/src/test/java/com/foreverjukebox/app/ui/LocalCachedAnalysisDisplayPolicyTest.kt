package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LocalCachedAnalysisDisplayPolicyTest {

    private fun track(
        localId: String,
        title: String,
        artist: String? = null
    ): LocalCachedTrack = LocalCachedTrack(
        localId = localId,
        title = title,
        artist = artist,
        sourceUri = null,
        durationSeconds = null
    )

    @Test
    fun filterReturnsOriginalListForBlankQuery() {
        val tracks = listOf(track("a", "One"), track("b", "Two"))
        assertSame(tracks, filterLocalCachedTracks(tracks, "   "))
    }

    @Test
    fun filterMatchesTitleAndArtistCaseInsensitively() {
        val tracks = listOf(
            track("a", "Sunrise", "Alice"),
            track("b", "Moonset", "Bob")
        )
        assertEquals(listOf("a"), filterLocalCachedTracks(tracks, "sun").map { it.localId })
        assertEquals(listOf("b"), filterLocalCachedTracks(tracks, "BOB").map { it.localId })
    }

    @Test
    fun sortByTitleAscendingAndDescending() {
        val tracks = listOf(
            track("a", "Bravo"),
            track("b", "alpha"),
            track("c", "Charlie")
        )
        assertEquals(
            listOf("b", "a", "c"),
            sortLocalCachedTracksForDisplay(
                tracks,
                FavoriteSortKey.Title,
                FavoriteSortDirection.Ascending
            ).map { it.localId }
        )
        assertEquals(
            listOf("c", "a", "b"),
            sortLocalCachedTracksForDisplay(
                tracks,
                FavoriteSortKey.Title,
                FavoriteSortDirection.Descending
            ).map { it.localId }
        )
    }

    @Test
    fun sortByArtistFallsBackToTitleThenId() {
        val tracks = listOf(
            track("a", "Zed", "Same"),
            track("b", "Alpha", "Same"),
            track("c", "Mid", "Other")
        )
        // "Other" < "Same"; within "Same", title Alpha < Zed.
        assertEquals(
            listOf("c", "b", "a"),
            sortLocalCachedTracksForDisplay(
                tracks,
                FavoriteSortKey.Artist,
                FavoriteSortDirection.Ascending
            ).map { it.localId }
        )
    }

    @Test
    fun sortTreatsUnknownArtistAsBlank() {
        val tracks = listOf(
            track("a", "One", "Beta"),
            track("b", "Two", "Unknown")
        )
        // "Unknown" is hidden (treated as blank), so it sorts before "Beta".
        assertEquals(
            listOf("b", "a"),
            sortLocalCachedTracksForDisplay(
                tracks,
                FavoriteSortKey.Artist,
                FavoriteSortDirection.Ascending
            ).map { it.localId }
        )
    }

    @Test
    fun sortLeavesSingletonListUntouched() {
        val tracks = listOf(track("a", "Only"))
        assertSame(
            tracks,
            sortLocalCachedTracksForDisplay(
                tracks,
                FavoriteSortKey.Title,
                FavoriteSortDirection.Ascending
            )
        )
    }
}
