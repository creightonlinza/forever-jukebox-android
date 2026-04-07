package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.ApiClient
import com.foreverjukebox.app.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun stateAfterPrepareForExit(current: UiState): UiState {
    val nextTab = defaultTabForMode(current.appMode)
    return current.copy(activeTab = nextTab, topSongsTab = TopSongsTab.TopSongs)
}

internal fun resolveVersionUpdatePrompt(
    currentVersionName: String,
    latestVersionRaw: String?,
    downloadUrlRaw: String?
): VersionUpdatePrompt? {
    val latestVersion = latestVersionRaw?.trim().orEmpty()
    val downloadUrl = downloadUrlRaw?.trim().orEmpty()
    if (latestVersion.isBlank() || downloadUrl.isBlank()) {
        return null
    }
    if (!isLatestVersionNewer(currentVersionName, latestVersion)) {
        return null
    }
    return VersionUpdatePrompt(
        latestVersion = latestVersion,
        downloadUrl = downloadUrl
    )
}

class AppLifecycleCoordinator(
    private val scope: CoroutineScope,
    private val api: ApiClient,
    private val controller: PlaybackController,
    private val playbackCoordinator: PlaybackCoordinator,
    private val localAnalysisCoordinator: LocalAnalysisCoordinator,
    private val serverTrackLoadCoordinator: ServerTrackLoadCoordinator,
    private val updateState: ((UiState) -> UiState) -> Unit,
    private val isDebugBuild: Boolean,
    private val currentVersionName: String,
    private val githubRepoOwner: String,
    private val githubRepoName: String
) {
    private var versionCheckAttempted = false

    fun prepareForExit() {
        serverTrackLoadCoordinator.cancel()
        localAnalysisCoordinator.cancelLocalAnalysisInternal(showCancelledMessage = false)
        playbackCoordinator.resetForNewTrack()
        controller.engine.clearAnalysis()
        controller.player.clear()
        controller.setTrackMeta(null, null)
        updateState(::stateAfterPrepareForExit)
    }

    fun clearCache() {
        playbackCoordinator.clearCache()
        scope.launch {
            delay(150)
            localAnalysisCoordinator.refreshLocalCachedTracks()
        }
    }

    fun checkForAppUpdateOnce() {
        if (isDebugBuild) return
        if (versionCheckAttempted) return
        versionCheckAttempted = true
        scope.launch {
            val latest = runCatching {
                api.fetchLatestGitHubRelease(
                    owner = githubRepoOwner,
                    repo = githubRepoName
                )
            }.getOrNull() ?: return@launch
            val prompt = resolveVersionUpdatePrompt(
                currentVersionName = currentVersionName,
                latestVersionRaw = latest.tagName,
                downloadUrlRaw = latest.htmlUrl
            ) ?: return@launch
            updateState {
                it.copy(versionUpdatePrompt = prompt)
            }
        }
    }
}
