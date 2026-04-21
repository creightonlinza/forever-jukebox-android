package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppPreferences
import com.foreverjukebox.app.data.ApiClient
import com.foreverjukebox.app.data.canonicalStableTrackId
import com.foreverjukebox.app.data.FavoriteTrack
import com.foreverjukebox.app.data.favoriteSourceTypeFromProvider
import com.foreverjukebox.app.data.favoriteUniqueSongIdFromTrackId
import com.foreverjukebox.app.data.parseTrackStableId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FavoritesController(
    private val scope: CoroutineScope,
    private val api: ApiClient,
    private val preferences: AppPreferences,
    private val getState: () -> UiState,
    private val updateState: ((UiState) -> UiState) -> Unit,
    private val showToast: suspend (String) -> Unit
) {
    private var favoritesSyncHydratedFor: String? = null
    private var pendingSyncDelta: FavoritesDelta? = null
    private var syncInFlight = false
    private var listenToggleSyncInFlight = false
    private var runtimeGeneration = 0L
    private var refreshFavoritesJob: Job? = null
    private var createSyncCodeJob: Job? = null
    private var hydrateFavoritesJob: Job? = null
    private var syncFavoritesJob: Job? = null

    fun resetRuntimeState() {
        runtimeGeneration += 1
        favoritesSyncHydratedFor = null
        pendingSyncDelta = null
        syncInFlight = false
        listenToggleSyncInFlight = false
        refreshFavoritesJob?.cancel()
        refreshFavoritesJob = null
        createSyncCodeJob?.cancel()
        createSyncCodeJob = null
        hydrateFavoritesJob?.cancel()
        hydrateFavoritesJob = null
        syncFavoritesJob?.cancel()
        syncFavoritesJob = null
        updateState {
            it.copy(
                favoritesSyncLoading = false,
                listenFavoriteToggleInFlight = false
            )
        }
    }

    fun refreshFavoritesFromSync() {
        refreshFavoritesJob?.cancel()
        refreshFavoritesJob = scope.launch {
            val state = getState()
            if (!state.allowFavoritesSync) {
                showToast("Favorites sync is disabled.")
                return@launch
            }
            if (state.favoritesSyncCode.isNullOrBlank()) {
                showToast("Create or enter a sync code first.")
                return@launch
            }
            val snapshot = currentSyncSnapshot(requireCode = true) ?: return@launch
            updateState { it.copy(favoritesSyncLoading = true) }
            try {
                val result = fetchFavoritesFromSync(snapshot)
                if (!isSnapshotCurrent(snapshot)) {
                    return@launch
                }
                if (result == null) {
                    showToast("Favorites sync failed.")
                } else {
                    updateFavorites(result, sync = false)
                    favoritesSyncHydratedFor = snapshot.code
                    showToast("Favorites refreshed.")
                }
            } finally {
                if (isSnapshotCurrent(snapshot, requireCodeMatch = false)) {
                    updateState { it.copy(favoritesSyncLoading = false) }
                }
            }
        }
    }

    fun createFavoritesSyncCode() {
        createSyncCodeJob?.cancel()
        createSyncCodeJob = scope.launch {
            val state = getState()
            if (!state.allowFavoritesSync) {
                showToast("Favorites sync is disabled.")
                return@launch
            }
            val baseUrl = state.baseUrl
            if (baseUrl.isBlank()) {
                showToast("API base URL is required.")
                return@launch
            }
            val snapshot = currentSyncSnapshot(requireCode = false) ?: return@launch
            try {
                val favorites = state.favorites
                val response = api.createFavoritesSync(baseUrl, favorites)
                if (!isSnapshotCurrent(snapshot, requireCodeMatch = false)) {
                    return@launch
                }
                val code = response.code ?: return@launch
                preferences.setFavoritesSyncCode(code)
                favoritesSyncHydratedFor = code.trim().lowercase()
                val normalized = normalizeFavorites(response.favorites)
                if (normalized.isNotEmpty()) {
                    updateFavorites(normalized, sync = false)
                }
                showToast("Favorites synced.")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                showToast("Favorites sync failed.")
            }
        }
    }

    suspend fun fetchFavoritesPreview(code: String): List<FavoriteTrack>? {
        val snapshot = currentSyncSnapshot(codeOverride = code, requireCode = true) ?: return null
        val favorites = fetchFavoritesFromSync(snapshot) ?: return null
        if (!isSnapshotCurrent(snapshot, requireCodeMatch = false)) {
            return null
        }
        return favorites
    }

    fun applyFavoritesSync(code: String, favorites: List<FavoriteTrack>) {
        scope.launch {
            val state = getState()
            if (!state.allowFavoritesSync) {
                showToast("Favorites sync is disabled.")
                return@launch
            }
            val snapshot = currentSyncSnapshot(requireCode = false) ?: return@launch
            if (!isSnapshotCurrent(snapshot, requireCodeMatch = false)) {
                return@launch
            }
            preferences.setFavoritesSyncCode(code)
            favoritesSyncHydratedFor = code.trim().lowercase()
            updateFavorites(normalizeFavorites(favorites), sync = false)
        }
    }

    fun updateFavorites(
        favorites: List<FavoriteTrack>,
        sync: Boolean = true,
        fromListenToggle: Boolean = false
    ) {
        val previous = getState().favorites
        val normalized = normalizeFavorites(favorites).take(MAX_FAVORITES)
        updateState { it.copy(favorites = normalized) }
        scope.launch {
            preferences.setFavorites(normalized)
        }
        if (!sync) {
            return
        }
        val delta = computeFavoritesDelta(previous, normalized)
        if (delta.isNoop()) {
            return
        }
        scheduleFavoritesSync(delta, fromListenToggle)
    }

    fun maybeHydrateFavoritesFromSync() {
        val state = getState()
        val code = state.favoritesSyncCode?.trim()?.lowercase()
        val baseUrl = state.baseUrl.trim()
        if (code.isNullOrBlank() || baseUrl.isBlank() || !state.allowFavoritesSync) {
            return
        }
        if (favoritesSyncHydratedFor == code) {
            return
        }
        favoritesSyncHydratedFor = code
        val snapshot = currentSyncSnapshot(codeOverride = code, requireCode = true) ?: return
        hydrateFavoritesJob?.cancel()
        hydrateFavoritesJob = scope.launch {
            val favorites = fetchFavoritesFromSync(snapshot)
            if (!isSnapshotCurrent(snapshot)) {
                return@launch
            }
            if (favorites == null) {
                favoritesSyncHydratedFor = null
                showToast("Favorites sync failed.")
                return@launch
            }
            updateFavorites(favorites, sync = false)
        }
    }

    private fun scheduleFavoritesSync(delta: FavoritesDelta, fromListenToggle: Boolean = false) {
        val state = getState()
        if (!state.allowFavoritesSync) {
            return
        }
        val snapshot = currentSyncSnapshot(requireCode = true) ?: return
        if (fromListenToggle) {
            listenToggleSyncInFlight = true
            updateState { it.copy(listenFavoriteToggleInFlight = true) }
        }
        if (syncInFlight) {
            pendingSyncDelta = delta
            return
        }
        syncInFlight = true
        syncFavoritesJob?.cancel()
        syncFavoritesJob = scope.launch {
            syncFavoritesToBackend(snapshot, delta)
        }
    }

    private suspend fun syncFavoritesToBackend(snapshot: SyncSnapshot, delta: FavoritesDelta) {
        try {
            val state = getState()
            if (!state.allowFavoritesSync || !isSnapshotCurrent(snapshot)) {
                return
            }
            val code = snapshot.code ?: return
            val serverFavorites = fetchFavoritesFromSync(snapshot) ?: return
            if (!isSnapshotCurrent(snapshot)) {
                return
            }
            val merged = applyFavoritesDelta(serverFavorites, delta)
            val response = api.updateFavoritesSync(snapshot.baseUrl, code, merged)
            if (!isSnapshotCurrent(snapshot)) {
                return
            }
            val normalized = normalizeFavorites(response.favorites)
            if (normalized.isNotEmpty()) {
                updateFavorites(normalized, sync = false)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (isSnapshotCurrent(snapshot, requireCodeMatch = false)) {
                showToast("Favorites sync failed.")
            }
        } finally {
            syncInFlight = false
            val pending = pendingSyncDelta
            pendingSyncDelta = null
            if (pending != null && !pending.isNoop() && isSnapshotCurrent(snapshot)) {
                scheduleFavoritesSync(pending)
            } else if (listenToggleSyncInFlight) {
                listenToggleSyncInFlight = false
                if (snapshot.generation == runtimeGeneration) {
                    updateState { it.copy(listenFavoriteToggleInFlight = false) }
                }
            }
        }
    }

    private fun computeFavoritesDelta(
        previous: List<FavoriteTrack>,
        next: List<FavoriteTrack>
    ): FavoritesDelta {
        val prevMap = previous.associateBy { canonicalStableTrackId(it.uniqueSongId) ?: it.uniqueSongId }
        val nextMap = next.associateBy { canonicalStableTrackId(it.uniqueSongId) ?: it.uniqueSongId }
        val added = next.filter { item ->
            val key = canonicalStableTrackId(item.uniqueSongId) ?: item.uniqueSongId
            !prevMap.containsKey(key)
        }
        val removedIds = prevMap.keys.filterNot { nextMap.containsKey(it) }.toSet()
        return FavoritesDelta(added = added, removedIds = removedIds)
    }

    private fun applyFavoritesDelta(
        serverFavorites: List<FavoriteTrack>,
        delta: FavoritesDelta
    ): List<FavoriteTrack> {
        val filtered = serverFavorites.filter { favorite ->
            val canonical = canonicalStableTrackId(favorite.uniqueSongId) ?: favorite.uniqueSongId
            canonical !in delta.removedIds
        }
        val merged = filtered + delta.added.filter { added ->
            val addedCanonical = canonicalStableTrackId(added.uniqueSongId) ?: added.uniqueSongId
            filtered.none { existing ->
                val existingCanonical =
                    canonicalStableTrackId(existing.uniqueSongId) ?: existing.uniqueSongId
                existingCanonical == addedCanonical
            }
        }
        return normalizeFavorites(merged).take(MAX_FAVORITES)
    }

    fun normalizeFavorites(items: List<FavoriteTrack>): List<FavoriteTrack> {
        val normalized = items.mapNotNull { item ->
            val parsedIdentity = parseTrackStableId(item.uniqueSongId) ?: return@mapNotNull null
            val uniqueSongId =
                favoriteUniqueSongIdFromTrackId(parsedIdentity.stableId) ?: return@mapNotNull null
            val resolvedSourceType = if (parsedIdentity.sourceProvider != null) {
                favoriteSourceTypeFromProvider(parsedIdentity.sourceProvider)
            } else {
                item.sourceType
            }
            item.copy(
                uniqueSongId = uniqueSongId,
                title = item.title.ifBlank { "Untitled" },
                artist = item.artist,
                sourceType = resolvedSourceType,
                tuningParams = TuningParamsCodec.stripHighlightAnchorParam(item.tuningParams)
            )
        }
        return sortFavorites(normalized).take(MAX_FAVORITES)
    }

    private suspend fun fetchFavoritesFromSync(snapshot: SyncSnapshot): List<FavoriteTrack>? {
        val state = getState()
        if (!state.allowFavoritesSync) return null
        val code = snapshot.code ?: return null
        return try {
            api.fetchFavoritesSync(snapshot.baseUrl, code)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    private fun currentSyncSnapshot(
        codeOverride: String? = null,
        requireCode: Boolean
    ): SyncSnapshot? {
        val state = getState()
        val baseUrl = state.baseUrl.trim()
        if (baseUrl.isBlank()) {
            return null
        }
        val code = normalizeSyncCode(codeOverride ?: state.favoritesSyncCode)
        if (requireCode && code == null) {
            return null
        }
        return SyncSnapshot(
            generation = runtimeGeneration,
            baseUrl = baseUrl,
            code = code
        )
    }

    private fun isSnapshotCurrent(snapshot: SyncSnapshot, requireCodeMatch: Boolean = true): Boolean {
        if (snapshot.generation != runtimeGeneration) {
            return false
        }
        val state = getState()
        if (state.baseUrl.trim() != snapshot.baseUrl) {
            return false
        }
        if (!requireCodeMatch) {
            return true
        }
        return normalizeSyncCode(state.favoritesSyncCode) == snapshot.code
    }

    private fun normalizeSyncCode(code: String?): String? {
        return code?.trim()?.lowercase()?.ifBlank { null }
    }

    fun sortFavorites(items: List<FavoriteTrack>): List<FavoriteTrack> {
        val deduped = items.distinctBy { canonicalStableTrackId(it.uniqueSongId) ?: it.uniqueSongId }
        return deduped.sortedWith(
            compareBy<FavoriteTrack, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist }
        )
    }

    companion object {
        private const val MAX_FAVORITES = 100
    }

    private data class SyncSnapshot(
        val generation: Long,
        val baseUrl: String,
        val code: String?
    )
}

private data class FavoritesDelta(
    val added: List<FavoriteTrack>,
    val removedIds: Set<String>
)

private fun FavoritesDelta.isNoop(): Boolean {
    return added.isEmpty() && removedIds.isEmpty()
}
