package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.FavoriteTrack
import com.foreverjukebox.app.data.ServerAppConfig
import java.io.File
import java.io.InputStream

fun createServerGateway(): ServerGateway = PlayServerGateway

private object PlayServerGateway : ServerGateway {
    override val isAvailable: Boolean = false

    override suspend fun searchMusic(baseUrl: String, query: String): List<RemoteMusicSearchItem> {
        return emptyList()
    }

    override suspend fun searchVideos(
        baseUrl: String,
        query: String,
        duration: Double
    ): List<RemoteVideoSearchItem> {
        return emptyList()
    }

    override suspend fun fetchTopSongs(baseUrl: String, limit: Int): List<RemoteSongItem> {
        return emptyList()
    }

    override suspend fun fetchTrendingSongs(baseUrl: String): List<RemoteSongItem> {
        return emptyList()
    }

    override suspend fun fetchRecentSongs(baseUrl: String, limit: Int): List<RemoteSongItem> {
        return emptyList()
    }

    override suspend fun getAppConfig(baseUrl: String): ServerAppConfig {
        return ServerAppConfig()
    }

    override suspend fun getAnalysis(baseUrl: String, jobId: String): TrackAnalysisResult {
        unsupported()
    }

    override suspend fun retryJob(baseUrl: String, jobId: String): TrackAnalysisResult {
        unsupported()
    }

    override suspend fun getJobBySource(
        baseUrl: String,
        sourceProvider: String,
        sourceId: String
    ): TrackAnalysisResult? {
        return null
    }

    override suspend fun getJobByTrack(
        baseUrl: String,
        title: String,
        artist: String
    ): TrackAnalysisResult? {
        return null
    }

    override suspend fun startVideoAnalysis(
        baseUrl: String,
        videoId: String,
        title: String?,
        artist: String?
    ): TrackAnalysisStartResult {
        unsupported()
    }

    override suspend fun startUrlAnalysis(
        baseUrl: String,
        url: String,
        title: String?,
        artist: String?
    ): TrackAnalysisResult {
        unsupported()
    }

    override suspend fun uploadTrack(
        baseUrl: String,
        fileName: String,
        sizeBytes: Long,
        contentType: String?,
        onBytesWritten: ((Long) -> Unit)?,
        streamProvider: () -> InputStream
    ): TrackAnalysisResult {
        unsupported()
    }

    override suspend fun postPlay(baseUrl: String, jobId: String) = Unit

    override suspend fun fetchAudioToFile(baseUrl: String, jobId: String, target: File): File {
        unsupported()
    }

    override suspend fun deleteJob(baseUrl: String, jobId: String, adminKey: String?) = Unit

    override suspend fun createFavoritesSync(
        baseUrl: String,
        favorites: List<FavoriteTrack>,
        maxFavorites: Int
    ): FavoritesSyncResult {
        return FavoritesSyncResult(favorites = favorites)
    }

    override suspend fun updateFavoritesSync(
        baseUrl: String,
        code: String,
        favorites: List<FavoriteTrack>,
        maxFavorites: Int
    ): FavoritesSyncResult {
        return FavoritesSyncResult(code = code, favorites = favorites)
    }

    override suspend fun fetchFavoritesSync(baseUrl: String, code: String): List<FavoriteTrack> {
        return emptyList()
    }

    override suspend fun fetchLatestRelease(owner: String, repo: String): ReleaseInfo? {
        return null
    }

    private fun unsupported(): Nothing {
        throw UnsupportedOperationException("Server mode is not available in this build.")
    }
}
