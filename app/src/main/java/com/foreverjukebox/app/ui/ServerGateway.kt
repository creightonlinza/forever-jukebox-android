package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.FavoriteTrack
import com.foreverjukebox.app.data.SOURCE_PROVIDER_YOUTUBE
import com.foreverjukebox.app.data.ServerAppConfig
import com.foreverjukebox.app.data.canonicalJobId
import com.foreverjukebox.app.data.sourceProviderFromRaw
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

data class RemoteMusicSearchItem(
    val id: String? = null,
    val name: String? = null,
    val artist: String? = null,
    val duration: Double? = null
)

data class RemoteVideoSearchItem(
    val id: String? = null,
    val title: String? = null,
    val duration: Double? = null
)

data class RemoteSongItem(
    val id: String? = null,
    val sourceId: String? = null,
    val sourceProvider: String? = null,
    val legacyVideoId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val playCount: Int? = null
)

@Serializable
data class TrackAnalysisResult(
    val id: String? = null,
    val status: String? = null,
    val progress: Double? = null,
    val message: String? = null,
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("source_provider") val sourceProvider: String? = null,
    @SerialName("youtube_id") val legacyVideoId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val result: JsonElement? = null,
    val error: String? = null,
    @SerialName("error_code") val errorCode: String? = null
)

data class TrackAnalysisStartResult(
    val id: String? = null,
    val status: String? = null,
    val progress: Double? = null,
    val message: String? = null,
    val sourceId: String? = null,
    val sourceProvider: String? = null,
    val error: String? = null,
    val errorCode: String? = null
)

data class FavoritesSyncResult(
    val code: String? = null,
    val favorites: List<FavoriteTrack> = emptyList()
)

data class ReleaseInfo(
    val latestVersion: String? = null,
    val downloadUrl: String? = null
)

interface ServerGateway {
    val isAvailable: Boolean

    suspend fun searchMusic(baseUrl: String, query: String): List<RemoteMusicSearchItem>

    suspend fun searchVideos(
        baseUrl: String,
        query: String,
        duration: Double
    ): List<RemoteVideoSearchItem>

    suspend fun fetchTopSongs(baseUrl: String, limit: Int): List<RemoteSongItem>

    suspend fun fetchTrendingSongs(baseUrl: String): List<RemoteSongItem>

    suspend fun fetchRecentSongs(baseUrl: String, limit: Int): List<RemoteSongItem>

    suspend fun getAppConfig(baseUrl: String): ServerAppConfig

    suspend fun getAnalysis(baseUrl: String, jobId: String): TrackAnalysisResult

    suspend fun retryJob(baseUrl: String, jobId: String): TrackAnalysisResult

    suspend fun getJobBySource(
        baseUrl: String,
        sourceProvider: String,
        sourceId: String
    ): TrackAnalysisResult?

    suspend fun getJobByTrack(
        baseUrl: String,
        title: String,
        artist: String
    ): TrackAnalysisResult?

    suspend fun startVideoAnalysis(
        baseUrl: String,
        videoId: String,
        title: String?,
        artist: String?
    ): TrackAnalysisStartResult

    suspend fun postPlay(baseUrl: String, jobId: String)

    suspend fun fetchAudioToFile(baseUrl: String, jobId: String, target: File): File

    suspend fun deleteJob(baseUrl: String, jobId: String, adminKey: String?)

    suspend fun createFavoritesSync(
        baseUrl: String,
        favorites: List<FavoriteTrack>,
        maxFavorites: Int
    ): FavoritesSyncResult

    suspend fun updateFavoritesSync(
        baseUrl: String,
        code: String,
        favorites: List<FavoriteTrack>,
        maxFavorites: Int
    ): FavoritesSyncResult

    suspend fun fetchFavoritesSync(baseUrl: String, code: String): List<FavoriteTrack>

    suspend fun fetchLatestRelease(owner: String, repo: String): ReleaseInfo?
}

fun trackIdFromRemoteSong(item: RemoteSongItem): String? {
    return canonicalJobId(item.id)
}

fun videoTrackIdFromRemoteSong(item: RemoteSongItem): String? {
    val provider = sourceProviderFromRaw(item.sourceProvider)
    val sourceId = item.sourceId?.trim().orEmpty().ifBlank { null }
    if (provider == SOURCE_PROVIDER_YOUTUBE && sourceId != null) {
        return sourceId
    }
    val legacyVideoId = item.legacyVideoId?.trim().orEmpty().ifBlank { null }
    if (legacyVideoId != null) {
        return legacyVideoId
    }
    return null
}
