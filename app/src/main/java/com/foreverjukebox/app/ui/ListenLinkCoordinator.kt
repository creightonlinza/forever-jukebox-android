package com.foreverjukebox.app.ui

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class ListenLinkCoordinator(
    private val buildTuningParamsString: () -> String?,
    private val getState: () -> UiState,
    private val setPlaybackMode: (PlaybackMode) -> Unit,
    private val loadTrackByStableId: (
        stableId: String,
        title: String?,
        artist: String?,
        tuningParams: String?
    ) -> Unit
) {
    fun buildShareUrl(): String? {
        val playback = getState().playback
        val trackId = playback.stableTrackIdOrNull() ?: return null
        val baseUrl = getState().baseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) return null
        val encodedId = encodeUriComponent(trackId)
        val query = when (playback.playMode) {
            PlaybackMode.Autocanonizer -> "mode=autocanonizer"
            PlaybackMode.Jukebox -> buildTuningParamsString()
        }
        return if (query.isNullOrBlank()) {
            "$baseUrl/listen/$encodedId"
        } else {
            "$baseUrl/listen/$encodedId?$query"
        }
    }

    fun handleDeepLink(uriString: String?) {
        if (uriString.isNullOrBlank()) return
        val uri = runCatching { URI(uriString) }.getOrNull() ?: return
        val base = getState().baseUrl.trim().trimEnd('/')
        val baseUri = runCatching { URI(base) }.getOrNull()
        if (!matchesKnownListenHost(uri, baseUri)) return
        val segments = uri.path
            ?.trim('/')
            ?.split('/')
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        if (segments.size >= 2 && segments.firstOrNull() == "listen") {
            val id = decodeUriComponent(segments[1])
            val queryParams = parseQueryParams(uri.rawQuery)
            val mode = if (queryParams["mode"]?.firstOrNull() == "autocanonizer") {
                PlaybackMode.Autocanonizer
            } else {
                PlaybackMode.Jukebox
            }
            setPlaybackMode(mode)
            val tuningParams = if (mode == PlaybackMode.Jukebox) {
                buildQueryWithoutMode(queryParams)
            } else {
                null
            }
            loadTrackByStableId(id, null, null, tuningParams)
        }
    }

    private fun matchesKnownListenHost(uri: URI, baseUri: URI?): Boolean {
        val uriScheme = uri.scheme?.lowercase()
        val uriHost = uri.host?.lowercase()
        if (uriScheme == "https" && uriHost == CANONICAL_LISTEN_HOST) {
            return true
        }
        if (baseUri == null) {
            return false
        }
        if (uriScheme != baseUri.scheme?.lowercase()) {
            return false
        }
        if (uriHost != baseUri.host?.lowercase()) {
            return false
        }
        if (baseUri.port != -1 && uri.port != baseUri.port) {
            return false
        }
        return true
    }

    private fun parseQueryParams(rawQuery: String?): LinkedHashMap<String, List<String>> {
        if (rawQuery.isNullOrBlank()) return linkedMapOf()
        val params = linkedMapOf<String, MutableList<String>>()
        rawQuery.split('&')
            .filter { it.isNotBlank() }
            .forEach { token ->
                val parts = token.split('=', limit = 2)
                val name = decodeUriComponent(parts[0])
                val value = if (parts.size == 2) decodeUriComponent(parts[1]) else null
                val values = params.getOrPut(name) { mutableListOf() }
                if (value == null) {
                    if (values.isEmpty()) {
                        values.add("")
                    }
                } else {
                    values.add(value)
                }
            }
        return LinkedHashMap(params.mapValues { it.value.toList() })
    }

    private fun buildQueryWithoutMode(paramsMap: LinkedHashMap<String, List<String>>): String? {
        val params = mutableListOf<String>()
        for ((name, values) in paramsMap) {
            if (name == "mode") continue
            if (values.isEmpty() || (values.size == 1 && values.first().isBlank())) {
                params.add(encodeUriComponent(name))
            } else {
                for (value in values) {
                    params.add("${encodeUriComponent(name)}=${encodeUriComponent(value)}")
                }
            }
        }
        return params.joinToString("&").ifBlank { null }
    }

    private fun encodeUriComponent(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    private fun decodeUriComponent(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }

    private companion object {
        private const val CANONICAL_LISTEN_HOST = "foreverjukebox.com"
    }
}
