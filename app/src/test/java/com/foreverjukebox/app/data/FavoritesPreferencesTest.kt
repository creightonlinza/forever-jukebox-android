package com.foreverjukebox.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesPreferencesTest {

    @Test
    fun decodeFavoritesKeepsUnknownPlayModeVerbatim() {
        val raw = """[{"uniqueSongId":"a","title":"A","artist":"X","playMode":"futuremode"}]"""

        val decoded = decodeFavorites(raw)

        assertEquals(1, decoded.size)
        assertEquals("a", decoded.first().uniqueSongId)
        assertEquals(FavoritePlayMode("futuremode"), decoded.first().playMode)
        assertTrue(tolerantJson().encodeToString(FavoriteTrack.serializer(), decoded.first()).contains("\"futuremode\""))
    }

    @Test
    fun decodeFavoritesKeepsUnknownSourceTypeVerbatim() {
        val raw = """[{"uniqueSongId":"a","title":"A","artist":"X","sourceType":"mixcloud"}]"""

        val decoded = decodeFavorites(raw)

        assertEquals(1, decoded.size)
        assertEquals(FavoriteSourceType("mixcloud"), decoded.first().sourceType)
    }

    @Test
    fun decodeFavoritesKeepsKnownValuesAndIgnoresUnknownKeys() {
        val raw = """[{"uniqueSongId":"a","title":"A","artist":"X","playMode":"autocanonizer",""" +
            """"sourceType":"youtube","duration":null,"futureField":{"x":1}}]"""

        val decoded = decodeFavorites(raw)

        assertEquals(FavoritePlayMode.Autocanonizer, decoded.first().playMode)
        assertEquals(FavoriteSourceType.Youtube, decoded.first().sourceType)
        assertNull(decoded.first().duration)
    }

    @Test
    fun decodeFavoritesDropsOnlyTheMalformedEntry() {
        val raw = """[{"uniqueSongId":"a","title":"A","artist":"X"},""" +
            """{"title":"missing id"},""" +
            """"not an object",""" +
            """{"uniqueSongId":"c","title":"C","artist":"Z","playMode":"jukebox"}]"""

        val decoded = decodeFavorites(raw)

        assertEquals(listOf("a", "c"), decoded.map { it.uniqueSongId })
        assertEquals(FavoritePlayMode.Jukebox, decoded.last().playMode)
    }

    @Test
    fun decodeFavoritesFallsBackToEmptyOnMalformedJson() {
        assertTrue(decodeFavorites("{not-json").isEmpty())
        assertTrue(decodeFavorites("""{"favorites":[]}""").isEmpty())
        assertTrue(decodeFavorites(null).isEmpty())
    }

    @Test
    fun decodeFavoritesReadsTheWubMachinePlayMode() {
        val raw = """[{"uniqueSongId":"a","title":"A","artist":"X","playMode":"wubmachine"}]"""

        assertEquals(FavoritePlayMode.WubMachine, decodeFavorites(raw).single().playMode)
    }
}
