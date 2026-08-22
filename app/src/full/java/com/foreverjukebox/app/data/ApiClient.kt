package com.foreverjukebox.app.data
import com.foreverjukebox.app.net.CleartextGuardInterceptor
import com.foreverjukebox.app.net.streamingRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class ApiClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val githubApiBaseUrl: String = DEFAULT_GITHUB_API_BASE_URL
) {
    private val jsonWithDefaults = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
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

    /**
     * Start (or dedupe onto) an analysis job for a user-supplied YouTube/SoundCloud/Bandcamp URL.
     * The server resolves the source metadata synchronously, so the call can block for tens of
     * seconds; it runs on [slowCallClient]. A 200 can carry a completed job's full result — or a
     * failed job — so callers must branch on the response's `status` field.
     */
    suspend fun startUrlAnalysis(
        baseUrl: String,
        url: String,
        title: String? = null,
        artist: String? = null
    ): AnalysisResponse {
        val requestUrl = buildUrl(baseUrl, ApiPaths.ANALYSIS_URL)
        val payload = json.encodeToString(UrlAnalysisRequest(url, title, artist))
        return postJson(requestUrl, payload, slowCallClient).let { json.decodeFromString(it) }
    }

    /**
     * Upload an audio file for analysis. The part name must be exactly "file" and the filename is
     * required — the server validates by filename extension only and uses the stem as the track
     * title. Streams from [streamProvider] without buffering; [onBytesWritten] is invoked on
     * OkHttp's IO thread with the cumulative byte count.
     */
    suspend fun uploadTrack(
        baseUrl: String,
        fileName: String,
        sizeBytes: Long,
        contentType: String?,
        onBytesWritten: ((Long) -> Unit)? = null,
        streamProvider: () -> InputStream
    ): AnalysisResponse = withContext(Dispatchers.IO) {
        val requestUrl = buildUrl(baseUrl, ApiPaths.UPLOAD)
        val fileBody = streamingRequestBody(
            contentType = contentType?.toMediaTypeOrNull(),
            sizeBytes = sizeBytes,
            onBytesWritten = onBytesWritten,
            streamProvider = streamProvider
        )
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, fileBody)
            .build()
        val request = Request.Builder().url(requestUrl).post(body).build()
        slowCallClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwHttpStatus(response)
            }
            json.decodeFromString(response.body?.string() ?: "")
        }
    }

    suspend fun getAnalysis(baseUrl: String, jobId: String): AnalysisResponse {
        val url = buildUrl(baseUrl, ApiPaths.analysisJob(jobId))
        return getJson(url)
    }

    suspend fun retryJob(baseUrl: String, jobId: String): AnalysisResponse {
        val url = buildUrl(baseUrl, ApiPaths.retryJob(jobId))
        return postEmpty(url).let { json.decodeFromString(it) }
    }

    suspend fun getJobBySource(
        baseUrl: String,
        sourceProvider: String,
        sourceId: String
    ): AnalysisResponse? {
        val normalizedProvider = sourceProvider.trim().lowercase()
        val normalizedSourceId = sourceId.trim()
        require(normalizedProvider.isNotBlank()) { "sourceProvider must not be blank" }
        require(normalizedSourceId.isNotBlank()) { "sourceId must not be blank" }
        val url = buildUrl(baseUrl, ApiPaths.jobBySource(normalizedProvider, normalizedSourceId))
        val response = getNullableOn404(url) ?: return null
        return json.decodeFromString(response)
    }

    suspend fun getJobByYoutube(baseUrl: String, youtubeId: String): AnalysisResponse? {
        return getJobBySource(
            baseUrl = baseUrl,
            sourceProvider = SOURCE_PROVIDER_YOUTUBE,
            sourceId = youtubeId
        )
    }

    suspend fun getJobByTrack(baseUrl: String, title: String, artist: String): AnalysisResponse? {
        val url = buildUrl(baseUrl, ApiPaths.JOB_BY_TRACK) {
            addQueryParameter("title", title)
            addQueryParameter("artist", artist)
        }
        val response = getNullableOn404(url) ?: return null
        return json.decodeFromString(response)
    }

    suspend fun fetchTopSongs(baseUrl: String, limit: Int = TOP_SONGS_LIMIT): List<TopSongItem> {
        val url = buildUrl(baseUrl, ApiPaths.TOP) {
            addQueryParameter("limit", limit.toString())
        }
        return getJson<TopSongsResponse>(url).items
    }

    suspend fun fetchTrendingSongs(baseUrl: String): List<TopSongItem> {
        val url = buildUrl(baseUrl, ApiPaths.TRENDING) {
            addQueryParameter("limit", TRENDING_LIMIT.toString())
        }
        return getJson<TopSongsResponse>(url).items
    }

    suspend fun fetchRecentSongs(baseUrl: String, limit: Int = TOP_SONGS_LIMIT): List<TopSongItem> {
        val url = buildUrl(baseUrl, ApiPaths.RECENT) {
            addQueryParameter("limit", limit.toString())
        }
        return getJson<TopSongsResponse>(url).items
    }

    suspend fun createFavoritesSync(
        baseUrl: String,
        favorites: List<FavoriteTrack>,
        maxFavorites: Int = DEFAULT_MAX_FAVORITES
    ): FavoritesSyncResponse {
        val url = buildUrl(baseUrl, ApiPaths.FAVORITES_SYNC)
        val trimmed = favorites.take(sanitizeMaxFavorites(maxFavorites))
        val payload = jsonWithDefaults.encodeToString(FavoritesSyncRequest(trimmed))
        return postJson(url, payload).let { json.decodeFromString(it) }
    }

    suspend fun updateFavoritesSync(
        baseUrl: String,
        code: String,
        favorites: List<FavoriteTrack>,
        maxFavorites: Int = DEFAULT_MAX_FAVORITES
    ): FavoritesSyncResponse {
        val url = buildUrl(baseUrl, ApiPaths.favoritesSync(code))
        val trimmed = favorites.take(sanitizeMaxFavorites(maxFavorites))
        val payload = jsonWithDefaults.encodeToString(FavoritesSyncRequest(trimmed))
        return putJson(url, payload).let { json.decodeFromString(it) }
    }

    suspend fun fetchFavoritesSync(baseUrl: String, code: String): List<FavoriteTrack> {
        val url = buildUrl(baseUrl, ApiPaths.favoritesSync(code))
        return getJson<FavoritesSyncPayload>(url).favorites
    }

    suspend fun getAppConfig(baseUrl: String): AppConfigResponse {
        val url = buildUrl(baseUrl, ApiPaths.APP_CONFIG)
        return getJson(url)
    }

    suspend fun postPlay(baseUrl: String, jobId: String) {
        val url = buildUrl(baseUrl, ApiPaths.play(jobId))
        postEmpty(url)
    }

    suspend fun fetchAudioToFile(baseUrl: String, jobId: String, target: File): File {
        val url = buildUrl(baseUrl, ApiPaths.audio(jobId))
        return getToFile(url, target)
    }

    suspend fun deleteJob(
        baseUrl: String,
        jobId: String,
        adminKey: String? = null
    ): DeleteJobResponse {
        val url = buildUrl(baseUrl, ApiPaths.job(jobId))
        return deleteJson(url, adminKey).let { json.decodeFromString(it) }
    }

    suspend fun fetchLatestGitHubRelease(
        owner: String,
        repo: String
    ): GitHubReleaseResponse {
        val url = buildUrl(
            baseUrl = githubApiBaseUrl,
            pathSegments = listOf("repos", owner, repo, "releases", "latest")
        )
        return getGitHubJson(url)
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwHttpStatus(response)
            }
            response.body?.string() ?: ""
        }
    }

    private suspend fun getNullableOn404(url: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) {
                return@withContext null
            }
            if (!response.isSuccessful) {
                throwHttpStatus(response)
            }
            response.body?.string() ?: ""
        }
    }

    private suspend fun getToFile(url: String, target: File): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwHttpStatus(response)
            }
            val body = response.body ?: throw IOException("Empty response body")
            target.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            target
        }
    }

    private suspend fun postJson(
        url: String,
        payload: String,
        callClient: OkHttpClient = client
    ): String = withContext(Dispatchers.IO) {
        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        callClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwHttpStatus(response)
            }
            response.body?.string() ?: ""
        }
    }

    private suspend fun putJson(url: String, payload: String): String = withContext(Dispatchers.IO) {
        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).put(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwHttpStatus(response)
            }
            response.body?.string() ?: ""
        }
    }

    private suspend fun postEmpty(url: String): String = withContext(Dispatchers.IO) {
        val body = ByteArray(0).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwHttpStatus(response)
            }
            response.body?.string() ?: ""
        }
    }

    private suspend fun deleteJson(
        url: String,
        adminKey: String? = null
    ): String = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url).delete()
        val trimmedAdminKey = adminKey?.trim()
        if (!trimmedAdminKey.isNullOrBlank()) {
            requestBuilder.header("X-Admin-Key", trimmedAdminKey)
        }
        val request = requestBuilder.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwHttpStatus(response)
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

    private suspend inline fun <reified T> getGitHubJson(url: String): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "ForeverJukebox-Android")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throwHttpStatus(response)
            }
            val body = response.body?.string() ?: ""
            json.decodeFromString(body)
        }
    }

    private fun throwHttpStatus(response: Response): Nothing {
        val body = response.body?.string()
        throw HttpStatusException(response.code, body)
    }

    private object ApiPaths {
        val SEARCH_SPOTIFY = listOf("api", "search", "spotify")
        val SEARCH_YOUTUBE = listOf("api", "search", "youtube")
        val ANALYSIS_YOUTUBE = listOf("api", "analysis", "youtube")
        val ANALYSIS_URL = listOf("api", "analysis", "url")
        val UPLOAD = listOf("api", "upload")
        val JOB_BY_TRACK = listOf("api", "jobs", "by-track")
        val TOP = listOf("api", "top")
        val TRENDING = listOf("api", "trending")
        val RECENT = listOf("api", "recent")
        val APP_CONFIG = listOf("api", "app-config")
        val FAVORITES_SYNC = listOf("api", "favorites", "sync")

        fun analysisJob(jobId: String) = listOf("api", "analysis", jobId)
        fun jobBySource(sourceProvider: String, sourceId: String) =
            listOf("api", "jobs", "by-source", sourceProvider, sourceId)
        fun job(jobId: String) = listOf("api", "jobs", jobId)
        fun retryJob(jobId: String) = listOf("api", "jobs", jobId, "retry")
        fun play(jobId: String) = listOf("api", "plays", jobId)
        fun audio(jobId: String) = listOf("api", "audio", jobId)
        fun favoritesSync(code: String) = listOf("api", "favorites", "sync", code)
    }

    companion object {
        private const val DEFAULT_GITHUB_API_BASE_URL = "https://api.github.com"
        private const val TRENDING_LIMIT = 25
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .addNetworkInterceptor(CleartextGuardInterceptor)
            .build()

        // URL analysis blocks on server-side source resolution (tens of seconds) and uploads are
        // followed by a server-side duration probe, so those calls get a long read timeout. Shares
        // the connection pool and dispatcher with sharedClient.
        private val slowCallClient = sharedClient.newBuilder()
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
