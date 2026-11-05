package com.foreverjukebox.app.net

import android.os.Build
import com.foreverjukebox.app.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Submits in-app feedback and bug reports to a Google Form via its public `formResponse`
 * endpoint (form-urlencoded POST, no auth). A submission carries only the user's feedback
 * text, the app version, a basic device summary, and, in Server mode, the configured
 * server's origin. PRIVACY.md covers the Play build, which has no Server mode, so it lists
 * only the first three; keep it in sync with what a Play build sends. The form is shared
 * with the web app, whose host field carries the page origin.
 *
 * Google answers a successful submission with the confirmation page (following a redirect,
 * which OkHttp does by default) but also answers rejected submissions with HTTP 200 by
 * redirecting back to the form's `viewform` page, so success requires both
 * [okhttp3.Response.isSuccessful] and a final URL that isn't the form itself.
 * Version/device strings are passed in rather than read here so the network path stays
 * unit-testable on the JVM.
 */
class FeedbackClient(
    private val formUrl: String = DEFAULT_FORM_URL,
    private val client: OkHttpClient = defaultClient
) {
    /**
     * Returns true when Google accepted the submission; false on any HTTP or network
     * failure. Makes a single attempt; a failed submission is not retried or retained.
     */
    suspend fun submit(
        feedback: String,
        appVersion: String,
        deviceInfo: String,
        host: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add(ENTRY_FEEDBACK, feedback)
            .add(ENTRY_APP_VERSION, appVersion)
            .add(ENTRY_DEVICE_INFO, deviceInfo)
            .apply { if (!host.isNullOrBlank()) add(ENTRY_HOST, host) }
            .build()
        val request = Request.Builder().url(formUrl).post(body).build()
        try {
            client.newCall(request).execute().use {
                it.isSuccessful && !it.request.url.encodedPath.endsWith("viewform")
            }
        } catch (_: IOException) {
            false
        }
    }

    companion object {
        private const val FORM_ID = "1FAIpQLSfFuWOCsqy6_U2eSJu316aFR_O9_-d80yDGzjfmFpfCYvVb6Q"
        const val DEFAULT_FORM_URL = "https://docs.google.com/forms/d/e/$FORM_ID/formResponse"

        const val ENTRY_FEEDBACK = "entry.1981349269"
        const val ENTRY_APP_VERSION = "entry.921237093"
        const val ENTRY_DEVICE_INFO = "entry.1749929406"
        const val ENTRY_HOST = "entry.2053321578"

        /**
         * The origin (scheme, host, non-default port) of [baseUrl], e.g.
         * "https://jukebox.example.com:8443"; null when it isn't an http(s) URL. Path, query,
         * and credentials are dropped so only the server's identity is reported.
         */
        internal fun hostSummary(baseUrl: String): String? {
            val url = baseUrl.trim().toHttpUrlOrNull() ?: return null
            return url.newBuilder()
                .username("")
                .password("")
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
                .toString()
                .trimEnd('/')
        }

        /** e.g. "2026.08.1-dev (Build 42)". */
        fun appVersionSummary(): String =
            "${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

        /** Reads [android.os.Build], so it only works on Android — tests pass literals instead. */
        fun deviceSummary(): String =
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(SDK ${Build.VERSION.SDK_INT})"

        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Bounds the whole request (including cross-route retries) so a black-holed
            // network can't stall a submission indefinitely.
            .callTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor(CleartextGuardInterceptor)
            .build()
    }
}
