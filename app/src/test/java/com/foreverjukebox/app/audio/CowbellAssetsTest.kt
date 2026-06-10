package com.foreverjukebox.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CowbellAssetsTest {

    @Test
    fun requiredCowbellAssetsExist() {
        val assetDir = listOf(
            File("src/main/assets/cowbell/sounds"),
            File("app/src/main/assets/cowbell/sounds")
        ).firstOrNull { it.exists() }

        assertTrue("Cowbell asset directory should exist", assetDir?.isDirectory == true)
        requireNotNull(assetDir)

        val actual = assetDir.listFiles()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
        val expected = (COWBELL_SAMPLE_NAMES + listOf(TRILL_SAMPLE_NAME) + WALKEN_SAMPLE_NAMES)
            .toSet()

        assertEquals(expected, actual)
    }
}
