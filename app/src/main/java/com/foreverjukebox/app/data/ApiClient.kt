package com.foreverjukebox.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class ApiClient(private val json: Json = Json { ignoreUnknownKeys = true }) {
    private val client = OkHttpClient()

    suspend fun searchSpotify(baseUrl: String, query: String): List<SpotifySearchItem> {
        val url = "${normalizeBaseUrl(baseUrl)}/api/search/spotify?q=${encode(query)}"
        val response = get(url)
        return json.decodeFromString<SearchResponse<SpotifySearchItem>>(response).items
    }

    suspend fun searchYoutube(
        baseUrl: String,
        query: String,
        duration: Double
    ): List<YoutubeSearchItem> {
        val url = "${normalizeBaseUrl(baseUrl)}/api/search/youtube?q=${encode(query)}&target_duration=${encode(duration.toString())}"
        val response = get(url)
        return json.decodeFromString<SearchResponse<YoutubeSearchItem>>(response).items
    }

    suspend fun startYoutubeAnalysis(
        baseUrl: String,
        youtubeId: String,
        title: String?,
        artist: String?
    ): AnalysisStartResponse {
        val url = "${normalizeBaseUrl(baseUrl)}/api/analysis/youtube"
        val body = AnalysisStartRequest(youtubeId, title, artist)
        val payload = json.encodeToString(body)
        val response = postJson(url, payload)
        return json.decodeFromString(response)
    }

    suspend fun getAnalysis(baseUrl: String, jobId: String): AnalysisResponse {
        val url = "${normalizeBaseUrl(baseUrl)}/api/analysis/${encode(jobId)}"
        val response = get(url)
        return json.decodeFromString(response)
    }

    suspend fun getJobByYoutube(baseUrl: String, youtubeId: String): AnalysisResponse {
        val url = "${normalizeBaseUrl(baseUrl)}/api/jobs/by-youtube/${encode(youtubeId)}"
        val response = get(url)
        return json.decodeFromString(response)
    }

    suspend fun fetchTopSongs(baseUrl: String, limit: Int = 20): List<TopSongItem> {
        val url = "${normalizeBaseUrl(baseUrl)}/api/top?limit=$limit"
        val response = get(url)
        return json.decodeFromString<TopSongsResponse>(response).items
    }

    suspend fun postPlay(baseUrl: String, jobId: String) {
        val url = "${normalizeBaseUrl(baseUrl)}/api/plays/${encode(jobId)}"
        postEmpty(url)
    }

    suspend fun fetchAudioBytes(baseUrl: String, jobId: String): ByteArray {
        val url = "${normalizeBaseUrl(baseUrl)}/api/audio/${encode(jobId)}"
        return getBytes(url)
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

    private fun normalizeBaseUrl(input: String): String {
        return input.trimEnd('/')
    }

    private fun encode(input: String): String {
        return java.net.URLEncoder.encode(input, "UTF-8")
    }
}
