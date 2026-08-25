package com.foreverjukebox.app.export

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportedAudioStoreTest {

    @Test
    fun buildsFileNameFromTrackTitle() {
        assertEquals(
            "Blinding Lights_forever.m4a",
            ExportedAudioStore.buildDisplayName("Blinding Lights")
        )
    }

    @Test
    fun sanitizesIllegalFilenameCharacters() {
        assertEquals(
            "A B C D_forever.m4a",
            ExportedAudioStore.buildDisplayName("A/B:C*D")
        )
    }

    @Test
    fun fallsBackWhenTitleIsMissingOrBlank() {
        assertEquals("jukebox_forever.m4a", ExportedAudioStore.buildDisplayName(null))
        assertEquals("jukebox_forever.m4a", ExportedAudioStore.buildDisplayName("   "))
        assertEquals("jukebox_forever.m4a", ExportedAudioStore.buildDisplayName("///"))
    }
}
