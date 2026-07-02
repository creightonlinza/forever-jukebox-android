package com.foreverjukebox.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.foreverjukebox.app.visualization.defaultVisualizationIndex
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "fj_preferences")

enum class ThemeMode {
    System,
    Light,
    Dark
}

@Serializable
enum class SavedPlaylistTrackType {
    Server,
    LocalCached
}

@Serializable
data class SavedPlaylistTrack(
    val id: String,
    val type: SavedPlaylistTrackType,
    val title: String? = null,
    val artist: String? = null,
    val tuningParams: String? = null,
    val playMode: FavoritePlayMode? = null
)

internal fun encodeSavedPlaylistTracks(
    items: List<SavedPlaylistTrack>,
    json: Json = Json { ignoreUnknownKeys = true }
): String {
    return json.encodeToString(ListSerializer(SavedPlaylistTrack.serializer()), items)
}

internal fun decodeSavedPlaylistTracks(
    raw: String?,
    json: Json = Json { ignoreUnknownKeys = true }
): List<SavedPlaylistTrack> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        json.decodeFromString(ListSerializer(SavedPlaylistTrack.serializer()), raw)
    } catch (_: Exception) {
        emptyList()
    }
}

class AppPreferences(private val context: Context) {
    companion object {
        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_ADMIN_KEY = stringPreferencesKey("admin_key")
        private val KEY_APP_MODE = stringPreferencesKey("app_mode")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_VIZ_INDEX = intPreferencesKey("viz_index")
        private val KEY_FAVORITES = stringPreferencesKey("favorites")
        private val KEY_FAVORITES_SYNC_CODE = stringPreferencesKey("favorites_sync_code")
        private val KEY_FAVORITES_SORT_KEY = stringPreferencesKey("favorites_sort_key")
        private val KEY_FAVORITES_SORT_DIRECTION =
            stringPreferencesKey("favorites_sort_direction")
        private val KEY_LOCAL_ANALYSIS_SORT_KEY = stringPreferencesKey("local_analysis_sort_key")
        private val KEY_LOCAL_ANALYSIS_SORT_DIRECTION =
            stringPreferencesKey("local_analysis_sort_direction")
        private val KEY_APP_CONFIG = stringPreferencesKey("app_config")
        private val KEY_CANONIZER_FINISH = booleanPreferencesKey("canonizer_finish_out_song")
        private val KEY_HIGHLIGHT_ANCHOR_BRANCH = booleanPreferencesKey("highlight_anchor_branch")
        private val KEY_LOADING_AUDIO_FEEDBACK = booleanPreferencesKey("loading_audio_feedback")
        private val KEY_SAVED_PLAYLIST = stringPreferencesKey("saved_playlist")
        private val KEY_WHATS_NEW_VERSION_CODE = intPreferencesKey("whats_new_version_code")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val baseUrl: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_BASE_URL]
    }

    val adminKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ADMIN_KEY]
    }

    val appMode: Flow<AppMode?> = context.dataStore.data.map { prefs ->
        appModeFromString(prefs[KEY_APP_MODE])
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        themeFromString(prefs[KEY_THEME])
    }

    val activeVizIndex: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_VIZ_INDEX] ?: defaultVisualizationIndex
    }

    val favorites: Flow<List<FavoriteTrack>> = context.dataStore.data.map { prefs ->
        decodeFavorites(prefs[KEY_FAVORITES])
    }

    val favoritesSyncCode: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_FAVORITES_SYNC_CODE]
    }

    val favoritesSortKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_FAVORITES_SORT_KEY]
    }

    val favoritesSortDirection: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_FAVORITES_SORT_DIRECTION]
    }

    val localAnalysisSortKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_LOCAL_ANALYSIS_SORT_KEY]
    }

    val localAnalysisSortDirection: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_LOCAL_ANALYSIS_SORT_DIRECTION]
    }

    val appConfig: Flow<ServerAppConfig?> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_APP_CONFIG] ?: return@map null
        runCatching { json.decodeFromString<ServerAppConfig>(raw) }.getOrNull()
    }

    val canonizerFinishOutSong: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CANONIZER_FINISH] ?: false
    }

    val highlightAnchorBranch: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_HIGHLIGHT_ANCHOR_BRANCH] ?: false
    }

    val loadingAudioFeedback: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_LOADING_AUDIO_FEEDBACK] ?: false
    }

    val savedPlaylist: Flow<List<SavedPlaylistTrack>> = context.dataStore.data.map { prefs ->
        decodeSavedPlaylistTracks(prefs[KEY_SAVED_PLAYLIST], json)
    }

    val whatsNewVersionCode: Flow<Int?> = context.dataStore.data.map { prefs ->
        prefs[KEY_WHATS_NEW_VERSION_CODE]
    }

    suspend fun setBaseUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = url
        }
    }

    suspend fun setAdminKey(key: String) {
        context.dataStore.edit { prefs ->
            val trimmedKey = key.trim()
            if (trimmedKey.isBlank()) {
                prefs.remove(KEY_ADMIN_KEY)
            } else {
                prefs[KEY_ADMIN_KEY] = trimmedKey
            }
        }
    }

    suspend fun setAppMode(mode: AppMode) {
        context.dataStore.edit { prefs ->
            prefs[KEY_APP_MODE] = mode.name
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME] = mode.name
        }
    }

    suspend fun setActiveVizIndex(index: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VIZ_INDEX] = index
        }
    }

    suspend fun setFavorites(items: List<FavoriteTrack>) {
        context.dataStore.edit { prefs ->
            val payload = json.encodeToString(ListSerializer(FavoriteTrack.serializer()), items)
            prefs[KEY_FAVORITES] = payload
        }
    }

    suspend fun setFavoritesSyncCode(code: String?) {
        context.dataStore.edit { prefs ->
            if (code.isNullOrBlank()) {
                prefs.remove(KEY_FAVORITES_SYNC_CODE)
            } else {
                prefs[KEY_FAVORITES_SYNC_CODE] = code.trim().lowercase()
            }
        }
    }

    suspend fun setFavoritesSort(sortKey: String, sortDirection: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FAVORITES_SORT_KEY] = sortKey
            prefs[KEY_FAVORITES_SORT_DIRECTION] = sortDirection
        }
    }

    suspend fun setLocalAnalysisSort(sortKey: String, sortDirection: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LOCAL_ANALYSIS_SORT_KEY] = sortKey
            prefs[KEY_LOCAL_ANALYSIS_SORT_DIRECTION] = sortDirection
        }
    }

    suspend fun setAppConfig(config: ServerAppConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_APP_CONFIG] = json.encodeToString(config)
        }
    }

    suspend fun clearAppConfig() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_APP_CONFIG)
        }
    }

    suspend fun setCanonizerFinishOutSong(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CANONIZER_FINISH] = enabled
        }
    }

    suspend fun setHighlightAnchorBranch(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HIGHLIGHT_ANCHOR_BRANCH] = enabled
        }
    }

    suspend fun setLoadingAudioFeedback(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LOADING_AUDIO_FEEDBACK] = enabled
        }
    }

    suspend fun setSavedPlaylist(items: List<SavedPlaylistTrack>) {
        context.dataStore.edit { prefs ->
            if (items.isEmpty()) {
                prefs.remove(KEY_SAVED_PLAYLIST)
            } else {
                prefs[KEY_SAVED_PLAYLIST] = encodeSavedPlaylistTracks(items, json)
            }
        }
    }

    suspend fun setWhatsNewVersionCode(versionCode: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WHATS_NEW_VERSION_CODE] = versionCode
        }
    }

    private fun decodeFavorites(raw: String?): List<FavoriteTrack> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(FavoriteTrack.serializer()), raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun themeFromString(raw: String?): ThemeMode {
        return when (raw) {
            ThemeMode.System.name -> ThemeMode.System
            ThemeMode.Light.name -> ThemeMode.Light
            ThemeMode.Dark.name -> ThemeMode.Dark
            else -> ThemeMode.System
        }
    }

    private fun appModeFromString(raw: String?): AppMode? {
        return when (raw) {
            AppMode.Local.name -> AppMode.Local
            AppMode.Server.name -> AppMode.Server
            else -> null
        }
    }
}
