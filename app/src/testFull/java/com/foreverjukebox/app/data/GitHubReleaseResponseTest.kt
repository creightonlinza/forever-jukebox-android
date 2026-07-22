package com.foreverjukebox.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseResponseTest {

    private fun release(vararg assetNames: String?): GitHubReleaseResponse =
        GitHubReleaseResponse(
            tagName = "v1.2.3",
            htmlUrl = "https://example.com/release",
            assets = assetNames.map { GitHubReleaseAsset(name = it) }
        )

    @Test
    fun hasApkAssetTrueWhenAnApkIsAttached() {
        assertTrue(release("forever-jukebox-v1.2.3.apk").hasApkAsset())
        assertTrue(release("mapping.txt", "app-full-release.apk").hasApkAsset())
    }

    @Test
    fun hasApkAssetIgnoresCaseAndSurroundingWhitespace() {
        assertTrue(release("FOO.APK").hasApkAsset())
        assertTrue(release(" foo.apk ").hasApkAsset())
    }

    @Test
    fun hasApkAssetFalseWithoutApkAsset() {
        assertFalse(release().hasApkAsset())
        assertFalse(release("mapping.txt", "notes.md").hasApkAsset())
        assertFalse(release(null).hasApkAsset())
        assertFalse(release("apk").hasApkAsset())
    }
}
