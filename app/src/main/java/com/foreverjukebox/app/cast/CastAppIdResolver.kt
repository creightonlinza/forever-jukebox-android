package com.foreverjukebox.app.cast

import android.content.Context
import androidx.core.net.toUri
import com.foreverjukebox.app.data.AppMode
import org.json.JSONObject

object CastAppIdResolver {
    /**
     * Receiver app ID for the Local-mode Cast relay (fj-android-cast). Placeholder until the owner
     * registers the receiver in the Cast Developer Console (PLAN §7 M6) — the real ID is a one-line
     * swap here.
     */
    const val RELAY_APP_ID = "RELAY_CAST_APP_ID_TBD"

    @Volatile
    private var cachedMap: Map<String, String>? = null

    /**
     * Deterministic, mode-aware receiver app ID. Never depends on `cast_app_ids.json` key iteration
     * order, so it is safe once more than one app ID exists. Local mode always casts to the relay;
     * Server mode maps the base URL to its server receiver. (CastContext still reads the app ID once
     * per process — switching modes needs an app restart.)
     */
    fun resolveForMode(context: Context, mode: AppMode?, baseUrl: String?): String? =
        appIdForMode(mode, resolve(context, baseUrl))

    /**
     * Pure mode→app-ID decision. Local (and pre-preferences `null`, since the play flavor is always
     * Local and full defaults into Local before a mode is chosen) → relay; Server → the resolved
     * server app ID.
     */
    internal fun appIdForMode(mode: AppMode?, serverAppId: String?): String? =
        when (mode) {
            AppMode.Local, null -> RELAY_APP_ID
            AppMode.Server -> serverAppId
        }

    fun resolve(context: Context, baseUrl: String?): String? {
        val normalized = normalize(baseUrl) ?: return null
        val map = cachedMap ?: loadMap(context).also { cachedMap = it }
        return map[normalized]
    }

    fun normalize(baseUrl: String?): String? {
        val trimmed = baseUrl?.trim()?.trimEnd('/') ?: return null
        if (trimmed.isBlank()) return null
        val uri = runCatching { trimmed.toUri() }.getOrNull() ?: return trimmed
        val scheme = uri.scheme?.lowercase() ?: return trimmed
        val host = uri.host?.lowercase() ?: return trimmed
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val path = uri.encodedPath?.trimEnd('/')?.takeIf { it.isNotBlank() && it != "/" } ?: ""
        return "$scheme://$host$port$path"
    }

    private fun loadMap(context: Context): Map<String, String> {
        return try {
            val raw = context.assets.open("cast_app_ids.json").bufferedReader().use { it.readText() }
            val json = JSONObject(raw)
            val result = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "").trim()
                if (value.isNotBlank()) {
                    normalize(key)?.let { normalizedKey ->
                        result[normalizedKey] = value
                    }
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
