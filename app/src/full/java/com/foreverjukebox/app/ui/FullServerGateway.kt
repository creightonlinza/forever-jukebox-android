package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AnalysisResponse
import com.foreverjukebox.app.data.AnalysisStartResponse
import com.foreverjukebox.app.data.ApiClient
import com.foreverjukebox.app.data.AppConfigResponse
import com.foreverjukebox.app.data.FavoriteTrack
import com.foreverjukebox.app.data.FavoritesSyncResponse
import com.foreverjukebox.app.data.GitHubReleaseResponse
import com.foreverjukebox.app.data.hasApkAsset
import com.foreverjukebox.app.data.ServerAppConfig
import com.foreverjukebox.app.data.SpotifySearchItem
import com.foreverjukebox.app.data.TopSongItem
import com.foreverjukebox.app.data.YoutubeSearchItem
import java.io.File
import java.io.InputStream

fun createServerGateway(): ServerGateway = FullServerGateway()

private class FullServerGateway(
    private val api: ApiClient = ApiClient()
) : ServerGateway {
    override val isAvailable: Boolean = true

    // Retry only idempotent read/download calls. Mutations must not be replayed after a
    // transient response failure unless the server provides idempotency guarantees.
    private suspend fun <T> withReadRetry(block: suspend () -> T): T =
        retryTransientRemoteLoad(block = block)

    override suspend fun searchMusic(baseUrl: String, query: String): List<RemoteMusicSearchItem> =
        withReadRetry { api.searchSpotify(baseUrl, query).map { it.toRemoteMusicSearchItem() } }

    override suspend fun searchVideos(
        baseUrl: String,
        query: String,
        duration: Double
    ): List<RemoteVideoSearchItem> =
        withReadRetry { api.searchYoutube(baseUrl, query, duration).map { it.toRemoteVideoSearchItem() } }

    override suspend fun fetchTopSongs(baseUrl: String, limit: Int): List<RemoteSongItem> =
        withReadRetry { api.fetchTopSongs(baseUrl, limit).map { it.toRemoteSongItem() } }

    override suspend fun fetchTrendingSongs(baseUrl: String): List<RemoteSongItem> =
        withReadRetry { api.fetchTrendingSongs(baseUrl).map { it.toRemoteSongItem() } }

    override suspend fun fetchRecentSongs(baseUrl: String, limit: Int): List<RemoteSongItem> =
        withReadRetry { api.fetchRecentSongs(baseUrl, limit).map { it.toRemoteSongItem() } }

    override suspend fun getAppConfig(baseUrl: String): ServerAppConfig =
        withReadRetry { api.getAppConfig(baseUrl).toServerAppConfig() }

    override suspend fun getAnalysis(baseUrl: String, jobId: String): TrackAnalysisResult =
        withReadRetry { api.getAnalysis(baseUrl, jobId).toTrackAnalysisResult() }

    override suspend fun retryJob(baseUrl: String, jobId: String): TrackAnalysisResult =
        api.retryJob(baseUrl, jobId).toTrackAnalysisResult()

    override suspend fun getJobBySource(
        baseUrl: String,
        sourceProvider: String,
        sourceId: String
    ): TrackAnalysisResult? =
        withReadRetry { api.getJobBySource(baseUrl, sourceProvider, sourceId)?.toTrackAnalysisResult() }

    override suspend fun getJobByTrack(
        baseUrl: String,
        title: String,
        artist: String
    ): TrackAnalysisResult? =
        withReadRetry { api.getJobByTrack(baseUrl, title, artist)?.toTrackAnalysisResult() }

    override suspend fun startVideoAnalysis(
        baseUrl: String,
        videoId: String,
        title: String?,
        artist: String?
    ): TrackAnalysisStartResult =
        api.startYoutubeAnalysis(baseUrl, videoId, title, artist).toTrackAnalysisStartResult()

    override suspend fun startUrlAnalysis(
        baseUrl: String,
        url: String,
        title: String?,
        artist: String?
    ): TrackAnalysisResult =
        api.startUrlAnalysis(baseUrl, url, title, artist).toTrackAnalysisResult()

    override suspend fun uploadTrack(
        baseUrl: String,
        fileName: String,
        sizeBytes: Long,
        contentType: String?,
        onBytesWritten: ((Long) -> Unit)?,
        streamProvider: () -> InputStream
    ): TrackAnalysisResult =
        api.uploadTrack(baseUrl, fileName, sizeBytes, contentType, onBytesWritten, streamProvider)
            .toTrackAnalysisResult()

    override suspend fun postPlay(baseUrl: String, jobId: String) {
        api.postPlay(baseUrl, jobId)
    }

    override suspend fun fetchAudioToFile(baseUrl: String, jobId: String, target: File): File =
        withReadRetry { api.fetchAudioToFile(baseUrl, jobId, target) }

    override suspend fun deleteJob(baseUrl: String, jobId: String, adminKey: String?) {
        api.deleteJob(baseUrl, jobId, adminKey)
    }

    override suspend fun createFavoritesSync(
        baseUrl: String,
        favorites: List<FavoriteTrack>,
        maxFavorites: Int
    ): FavoritesSyncResult =
        api.createFavoritesSync(baseUrl, favorites, maxFavorites).toFavoritesSyncResult()

    override suspend fun updateFavoritesSync(
        baseUrl: String,
        code: String,
        favorites: List<FavoriteTrack>,
        maxFavorites: Int
    ): FavoritesSyncResult =
        api.updateFavoritesSync(baseUrl, code, favorites, maxFavorites).toFavoritesSyncResult()

    override suspend fun fetchFavoritesSync(baseUrl: String, code: String): List<FavoriteTrack> =
        withReadRetry { api.fetchFavoritesSync(baseUrl, code) }

    override suspend fun fetchLatestRelease(owner: String, repo: String): ReleaseInfo? =
        withReadRetry { api.fetchLatestGitHubRelease(owner, repo).toReleaseInfo() }
}

private fun SpotifySearchItem.toRemoteMusicSearchItem(): RemoteMusicSearchItem {
    return RemoteMusicSearchItem(
        id = id,
        name = name,
        artist = artist,
        duration = duration
    )
}

private fun YoutubeSearchItem.toRemoteVideoSearchItem(): RemoteVideoSearchItem {
    return RemoteVideoSearchItem(
        id = id,
        title = title,
        duration = duration
    )
}

private fun TopSongItem.toRemoteSongItem(): RemoteSongItem {
    return RemoteSongItem(
        id = id,
        sourceId = sourceId,
        sourceProvider = sourceProvider,
        legacyVideoId = youtubeId,
        title = title,
        artist = artist,
        playCount = playCount
    )
}

private fun AnalysisResponse.toTrackAnalysisResult(): TrackAnalysisResult {
    return TrackAnalysisResult(
        id = id,
        status = status,
        progress = progress,
        message = message,
        sourceId = sourceId,
        sourceProvider = sourceProvider,
        legacyVideoId = youtubeId,
        createdAt = createdAt,
        result = result,
        error = error,
        errorCode = errorCode
    )
}

private fun AnalysisStartResponse.toTrackAnalysisStartResult(): TrackAnalysisStartResult {
    return TrackAnalysisStartResult(
        id = id,
        status = status,
        progress = progress,
        message = message,
        sourceId = sourceId,
        sourceProvider = sourceProvider,
        error = error,
        errorCode = errorCode
    )
}

private fun AppConfigResponse.toServerAppConfig(): ServerAppConfig {
    return ServerAppConfig(
        allowFavoritesSync = allowFavoritesSync,
        maxFavorites = maxFavorites,
        maxTrackLength = maxTrackLength,
        allowUserUrl = allowUserUrl,
        allowUserUpload = allowUserUpload,
        maxUploadSize = maxUploadSize,
        // Kept as the server sent it; the extension rules in UserSourcePolicy own normalization.
        allowedUploadExts = allowedUploadExts.orEmpty()
    )
}

private fun FavoritesSyncResponse.toFavoritesSyncResult(): FavoritesSyncResult {
    return FavoritesSyncResult(
        code = code,
        favorites = favorites
    )
}

private fun GitHubReleaseResponse.toReleaseInfo(): ReleaseInfo? {
    if (!hasApkAsset()) return null
    return ReleaseInfo(
        latestVersion = tagName,
        downloadUrl = htmlUrl
    )
}
