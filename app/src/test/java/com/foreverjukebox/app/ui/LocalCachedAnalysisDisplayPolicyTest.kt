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
            sortLocalCachedTracksByTitle(tracks, ascending = true).map { it.localId }
        )
        assertEquals(
            listOf("c", "a", "b"),
            sortLocalCachedTracksByTitle(tracks, ascending = false).map { it.localId }
        )
    }

    @Test
    fun sortLeavesSingletonListUntouched() {
        val tracks = listOf(track("a", "Only"))
        assertSame(tracks, sortLocalCachedTracksByTitle(tracks, ascending = true))
    }
}
