package com.foreverjukebox.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient(private val json: Json = Json { ignoreUnknownKeys = true }) {
    private val client = sharedClient

    suspend fun searchSpotify(baseUrl: String, query: String): List<SpotifySearchItem> {
        val url = buildUrl(baseUrl, ApiPaths.SEARCH_SPOTIFY) {
            addQueryParameter("q", query)
        }
        return getJson<SearchResponse<SpotifySearchItem>>(url).items
    }

    suspend fun searchYoutube(
        baseUrl: String,
        query: String,
        duration: Double
    ): List<YoutubeSearchItem> {
        val url = buildUrl(baseUrl, ApiPaths.SEARCH_YOUTUBE) {
            addQueryParameter("q", query)
            addQueryParameter("target_duration", duration.toString())
        }
        return getJson<SearchResponse<YoutubeSearchItem>>(url).items
    }

    suspend fun startYoutubeAnalysis(
        baseUrl: String,
        youtubeId: String,
        title: String?,
        artist: String?
    ): AnalysisStartResponse {
        val url = buildUrl(baseUrl, ApiPaths.ANALYSIS_YOUTUBE)
        val body = AnalysisStartRequest(youtubeId, title, artist)
        val payload = json.encodeToString(body)
        return postJson(url, payload).let { json.decodeFromString(it) }
    }

    suspend fun getAnalysis(baseUrl: String, jobId: String): AnalysisResponse {
        val url = buildUrl(baseUrl, ApiPaths.analysisJob(jobId))
        return getJson(url)
    }

    suspend fun getJobByYoutube(baseUrl: String, youtubeId: String): AnalysisResponse {
        val url = buildUrl(baseUrl, ApiPaths.jobByYoutube(youtubeId))
        return getJson(url)
    }

    suspend fun getJobByTrack(baseUrl: String, title: String, artist: String): AnalysisResponse {
        val url = buildUrl(baseUrl, ApiPaths.JOB_BY_TRACK) {
            addQueryParameter("title", title)
            addQueryParameter("artist", artist)
        }
        return getJson(url)
    }

    suspend fun fetchTopSongs(baseUrl: String, limit: Int = 20): List<TopSongItem> {
        val url = buildUrl(baseUrl, ApiPaths.TOP) {
            addQueryParameter("limit", limit.toString())
        }
        return getJson<TopSongsResponse>(url).items
    }

    suspend fun postPlay(baseUrl: String, jobId: String) {
        val url = buildUrl(baseUrl, ApiPaths.play(jobId))
        postEmpty(url)
    }

    suspend fun fetchAudioBytes(baseUrl: String, jobId: String): ByteArray {
        val url = buildUrl(baseUrl, ApiPaths.audio(jobId))
        return getBytes(url)
    }

    suspend fun repairJob(baseUrl: String, jobId: String): AnalysisResponse {
        val url = buildUrl(baseUrl, ApiPaths.repair(jobId))
        return postEmptyJson(url).let { json.decodeFromString(it) }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            response.body?.string() ?: ""
        }
    }

    private suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    private suspend fun postJson(url: String, payload: String): String = withContext(Dispatchers.IO) {
        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            response.body?.string() ?: ""
        }
    }

    private suspend fun postEmpty(url: String) = withContext(Dispatchers.IO) {
        val body = ByteArray(0).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
        }
    }

    private suspend fun postEmptyJson(url: String): String = withContext(Dispatchers.IO) {
        val body = ByteArray(0).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            response.body?.string() ?: ""
        }
    }

    private fun buildUrl(
        baseUrl: String,
        pathSegments: List<String>,
        builder: (HttpUrl.Builder.() -> Unit)? = null
    ): String {
        val normalized = baseUrl.trimEnd('/')
        val base = normalized.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid base URL")
        val urlBuilder = base.newBuilder()
        pathSegments.forEach { urlBuilder.addPathSegment(it) }
        builder?.invoke(urlBuilder)
        return urlBuilder.build().toString()
    }

    private suspend inline fun <reified T> getJson(url: String): T {
        val response = get(url)
        return json.decodeFromString(response)
    }

    private object ApiPaths {
        val SEARCH_SPOTIFY = listOf("api", "search", "spotify")
        val SEARCH_YOUTUBE = listOf("api", "search", "youtube")
        val ANALYSIS_YOUTUBE = listOf("api", "analysis", "youtube")
        val JOB_BY_TRACK = listOf("api", "jobs", "by-track")
        val TOP = listOf("api", "top")

        fun analysisJob(jobId: String) = listOf("api", "analysis", jobId)
        fun jobByYoutube(youtubeId: String) = listOf("api", "jobs", "by-youtube", youtubeId)
        fun play(jobId: String) = listOf("api", "plays", jobId)
        fun audio(jobId: String) = listOf("api", "audio", jobId)
        fun repair(jobId: String) = listOf("api", "repair", jobId)
    }

    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
