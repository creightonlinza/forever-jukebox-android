package com.foreverjukebox.app.engine

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The engine-parity fixtures are a behavior contract shared with the
 * forever-jukebox web repo; both repos verify their copies against
 * manifest.json. If this test fails, the local fixtures drifted from the
 * manifest — re-sync with scripts/sync-parity-fixtures.sh. Intentional
 * contract changes are made in the web repo (npm run fixtures:manifest in
 * packages/jukebox-engine) and then synced here.
 */
class ParityFixtureManifestTest {

    private fun fixtureDir(): File {
        val url = checkNotNull(
            Thread.currentThread().contextClassLoader?.getResource("engine-parity"),
        ) { "engine-parity test resources not found" }
        return File(url.toURI())
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun manifestListsEveryFixtureCaseFile() {
        val manifest = loadEngineParityFixture("manifest.json")
        val listed = manifest["files"]!!.jsonObject.keys.sorted()
        val onDisk = fixtureDir()
            .listFiles { file -> file.name.endsWith("-cases.json") }!!
            .map { it.name }
            .sorted()
        assertEquals(listed, onDisk)
    }

    @Test
    fun fixtureContentMatchesManifestHashes() {
        val manifest = loadEngineParityFixture("manifest.json")
        val dir = fixtureDir()
        for ((name, digest) in manifest["files"]!!.jsonObject) {
            assertEquals(
                "stale fixture: $name (run scripts/sync-parity-fixtures.sh)",
                digest.jsonPrimitive.content,
                sha256(File(dir, name)),
            )
        }
    }
}
