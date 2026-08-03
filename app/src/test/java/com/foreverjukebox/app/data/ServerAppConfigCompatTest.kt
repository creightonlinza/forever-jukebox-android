package com.foreverjukebox.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAppConfigCompatTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Configs persisted before the user-source fields existed must still decode, with the new
     * features defaulting to off — this pins the DataStore backward-compat guarantee.
     */
    @Test
    fun legacyPersistedConfigDecodesWithUserSourceFeaturesOff() {
        val legacy = """{"allowFavoritesSync":true,"maxFavorites":50,"maxTrackLength":12.0}"""

        val config = json.decodeFromString<ServerAppConfig>(legacy)

        assertEquals(true, config.allowFavoritesSync)
        assertEquals(50, config.maxFavorites)
        assertEquals(12.0, config.maxTrackLength!!, 0.0)
        assertFalse(config.allowUserUrl)
        assertFalse(config.allowUserUpload)
        assertNull(config.maxUploadSize)
        assertEquals(emptyList<String>(), config.allowedUploadExts)
    }

    @Test
    fun fullConfigRoundTrips() {
        val config = ServerAppConfig(
            allowFavoritesSync = true,
            maxFavorites = 100,
            maxTrackLength = 12.0,
            allowUserUrl = true,
            allowUserUpload = true,
            maxUploadSize = 20L * 1024 * 1024,
            allowedUploadExts = listOf(".mp3", ".m4a")
        )

        val decoded = json.decodeFromString<ServerAppConfig>(Json.encodeToString(config))

        assertEquals(config, decoded)
    }
}
