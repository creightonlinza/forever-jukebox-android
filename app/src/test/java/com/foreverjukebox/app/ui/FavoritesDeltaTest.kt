package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.FavoriteTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesDeltaTest {

    @Test
    fun reportsNothingToSyncWhenTheListIsUnchanged() {
        val delta = computeFavoritesDelta(listOf(favorite()), listOf(favorite()))

        assertTrue(delta.isNoop())
    }

    @Test
    fun reportsAnEditAsAnUpdateRatherThanAnAddOrARemove() {
        // Re-saving tuning onto a favorite leaves the id alone, so the sync has to carry it as an
        // edit; treated as neither added nor removed it would never reach the server at all.
        val delta = computeFavoritesDelta(
            listOf(favorite(tuningParams = null)),
            listOf(favorite(tuningParams = "jb=1&thresh=45"))
        )

        assertFalse(delta.isNoop())
        assertEquals(emptyList<FavoriteTrack>(), delta.added)
        assertEquals(emptySet<String>(), delta.removedIds)
        assertEquals(listOf("jb=1&thresh=45"), delta.updated.map { it.tuningParams })
    }

    @Test
    fun keepsAddsAndRemovesOutOfTheUpdateList() {
        val delta = computeFavoritesDelta(
            listOf(favorite(id = JOB_ID), favorite(id = OTHER_JOB_ID)),
            listOf(favorite(id = OTHER_JOB_ID), favorite(id = THIRD_JOB_ID))
        )

        assertEquals(listOf(THIRD_JOB_ID), delta.added.map { it.uniqueSongId })
        assertEquals(setOf(JOB_ID), delta.removedIds)
        assertEquals(emptyList<FavoriteTrack>(), delta.updated)
    }

    @Test
    fun overwritesTheServerCopyOfAnUpdatedFavorite() {
        val merged = mergeFavoritesDelta(
            serverFavorites = listOf(favorite(tuningParams = "jb=1"), favorite(id = OTHER_JOB_ID)),
            delta = computeFavoritesDelta(
                listOf(favorite(tuningParams = "jb=1")),
                listOf(favorite(tuningParams = "thresh=45"))
            )
        )

        assertEquals(listOf(JOB_ID, OTHER_JOB_ID), merged.map { it.uniqueSongId })
        assertEquals("thresh=45", merged.first { it.uniqueSongId == JOB_ID }.tuningParams)
    }

    @Test
    fun updatesAFavoriteHeldUnderALegacySourceId() {
        // Matching is by canonical id, so an entry the server holds under its YouTube id is the
        // same favorite and takes the edit rather than gaining a duplicate.
        val merged = mergeFavoritesDelta(
            serverFavorites = listOf(favorite(id = YOUTUBE_ID, tuningParams = null)),
            delta = FavoritesDelta(
                added = emptyList(),
                removedIds = emptySet(),
                updated = listOf(favorite(id = YOUTUBE_ID, tuningParams = "thresh=45"))
            )
        )

        assertEquals(1, merged.size)
        assertEquals("thresh=45", merged.single().tuningParams)
    }

    @Test
    fun restoresAnEditedFavoriteTheServerNoLongerHolds() {
        val merged = mergeFavoritesDelta(
            serverFavorites = listOf(favorite(id = OTHER_JOB_ID)),
            delta = computeFavoritesDelta(
                listOf(favorite(tuningParams = "jb=1")),
                listOf(favorite(tuningParams = "thresh=45"))
            )
        )

        // The edit is carried across beside the favorite this device never saw, rather than
        // dropped, which would delete it here when the response replaces local state.
        assertEquals(listOf(OTHER_JOB_ID, JOB_ID), merged.map { it.uniqueSongId })
    }

    @Test
    fun dropsARemovedFavoriteFromTheMerge() {
        val merged = mergeFavoritesDelta(
            serverFavorites = listOf(favorite(), favorite(id = OTHER_JOB_ID)),
            delta = computeFavoritesDelta(
                listOf(favorite(), favorite(id = OTHER_JOB_ID)),
                listOf(favorite(id = OTHER_JOB_ID))
            )
        )

        assertEquals(listOf(OTHER_JOB_ID), merged.map { it.uniqueSongId })
    }

    private fun favorite(
        id: String = JOB_ID,
        tuningParams: String? = null
    ): FavoriteTrack = FavoriteTrack(
        uniqueSongId = id,
        title = "Title",
        artist = "Artist",
        tuningParams = tuningParams
    )

    private companion object {
        const val JOB_ID = "a3f3c0dc73c6476c9db95c227f9206f2"
        const val OTHER_JOB_ID = "b4e4d1ed84d7587dae0a6d338a0317f3"
        const val THIRD_JOB_ID = "c5f5e2fe95e8698ebf1b7e449b1428a4"
        const val YOUTUBE_ID = "dQw4w9WgXcQ"
    }
}
