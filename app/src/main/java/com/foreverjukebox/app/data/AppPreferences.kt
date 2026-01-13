package com.foreverjukebox.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

class AppPreferences(private val context: Context) {
    companion object {
        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_VIZ_INDEX = intPreferencesKey("viz_index")
        private val KEY_FAVORITES = stringPreferencesKey("favorites")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val baseUrl: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_BASE_URL]
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        themeFromString(prefs[KEY_THEME])
    }

    val activeVizIndex: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_VIZ_INDEX] ?: 0
    }

    val favorites: Flow<List<FavoriteTrack>> = context.dataStore.data.map { prefs ->
        decodeFavorites(prefs[KEY_FAVORITES])
    }

    suspend fun setBaseUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = url
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
}
