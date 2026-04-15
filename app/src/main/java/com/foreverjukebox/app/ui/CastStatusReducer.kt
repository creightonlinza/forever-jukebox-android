package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.SOURCE_PROVIDER_YOUTUBE
import com.foreverjukebox.app.data.buildJobStableTrackId
import com.foreverjukebox.app.data.buildSourceStableTrackId
import com.foreverjukebox.app.data.isYoutubeLikeSourceId
import com.foreverjukebox.app.visualization.visualizationCount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CastStatusMessage(
    val songId: String,
    val title: String,
    val artist: String,
    val trackDurationSeconds: Double?,
    val totalBeats: Int?,
    val totalBranches: Int?,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val playbackState: String,
    val error: String,
    val errorCode: String? = null,
    val activeVizIndex: Int?,
    val resolvedThreshold: Int?
)

fun parseCastStatusMessage(message: String): CastStatusMessage? {
    val json = runCatching { Json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
    val type = json["type"]?.jsonPrimitive?.contentOrNull
    if (type != "status") {
        return null
    }

    fun stringField(name: String): String {
        return json[name]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeUnless { it == "null" }
            ?: ""
    }

    val songId = stringField("songId")
    val title = stringField("title")
    val artist = stringField("artist")
    val trackDurationSeconds = json["trackDurationSeconds"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toDoubleOrNull()
        ?.takeIf { it > 0.0 }
    val totalBeats = json["totalBeats"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toIntOrNull()
        ?.takeIf { it >= 0 }
    val totalBranches = json["totalBranches"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toIntOrNull()
        ?.takeIf { it >= 0 }
    val isPlaying = json["isPlaying"]?.jsonPrimitive?.booleanOrNull ?: false
    val isLoading = json["isLoading"]?.jsonPrimitive?.booleanOrNull ?: false
    val playbackState = stringField("playbackState")
    val error = stringField("error")
    val errorCode = sequenceOf("errorCode", "error_code")
        .map(::stringField)
        .firstOrNull { it.isNotBlank() }
    val activeVizIndex = json["activeVizIndex"]?.jsonPrimitive?.intOrNull
    val resolvedThreshold = json["resolvedThreshold"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toIntOrNull()
        ?.takeIf { it >= 2 }
    return CastStatusMessage(
        songId = songId,
        title = title,
        artist = artist,
        trackDurationSeconds = trackDurationSeconds,
        totalBeats = totalBeats,
        totalBranches = totalBranches,
        isPlaying = isPlaying,
        isLoading = isLoading,
        playbackState = playbackState,
        error = error,
        errorCode = errorCode,
        activeVizIndex = activeVizIndex,
        resolvedThreshold = resolvedThreshold
    )
}

fun reduceCastStatus(current: UiState, status: CastStatusMessage): UiState {
    val hasTitle = status.title.isNotBlank()
    val hasArtist = status.artist.isNotBlank()
    val currentPlayback = current.playback
    val resolvedTrackTitle = when {
        hasTitle && currentPlayback.trackTitle.isNullOrBlank() -> status.title
        else -> currentPlayback.trackTitle
    }
    val resolvedTrackArtist = when {
        hasArtist && currentPlayback.trackArtist.isNullOrBlank() -> status.artist
        else -> currentPlayback.trackArtist
    }
    val metadataBackfilled = (hasTitle && currentPlayback.trackTitle.isNullOrBlank()) ||
        (hasArtist && currentPlayback.trackArtist.isNullOrBlank())
    val displayTitle = if (metadataBackfilled || currentPlayback.playTitle.isBlank()) {
        when {
            !resolvedTrackArtist.isNullOrBlank() -> {
                "${resolvedTrackTitle?.takeIf { it.isNotBlank() } ?: "Unknown"} — $resolvedTrackArtist"
            }
            !resolvedTrackTitle.isNullOrBlank() -> resolvedTrackTitle
            else -> currentPlayback.playTitle
        }
    } else {
        currentPlayback.playTitle
    }
    val resolvedIsLoading = when (status.playbackState) {
        "loading" -> true
        "playing", "paused", "idle", "error" -> false
        else -> status.isLoading
    }
    val resolvedIsRunning = when (status.playbackState) {
        "playing" -> true
        "paused", "idle", "error" -> false
        "loading" -> current.playback.isRunning
        else -> if (resolvedIsLoading) current.playback.isRunning else status.isPlaying
    }
    val resolvedIsPaused = when (status.playbackState) {
        "paused" -> true
        "playing", "loading", "idle", "error" -> false
        else -> !resolvedIsLoading && !resolvedIsRunning && current.playback.isPaused
    }
    val resolvedLastJobId = if (status.songId.isBlank()) {
        currentPlayback.lastJobId
    } else {
        status.songId
    }
    val fallbackStableTrackId = if (status.songId.isBlank()) {
        null
    } else {
        buildJobStableTrackId(status.songId)
    }
    val resolvedStableTrackId = currentPlayback.lastStableTrackId ?: fallbackStableTrackId
    val hasSourceIdentity = !currentPlayback.lastSourceProvider.isNullOrBlank() &&
        !currentPlayback.lastSourceId.isNullOrBlank()
    val resolvedSourceProvider = currentPlayback.lastSourceProvider
    val resolvedSourceId = currentPlayback.lastSourceId
    val resolvedYouTubeId = when {
        !currentPlayback.lastYouTubeId.isNullOrBlank() -> currentPlayback.lastYouTubeId
        hasSourceIdentity && currentPlayback.lastSourceProvider == SOURCE_PROVIDER_YOUTUBE ->
            currentPlayback.lastSourceId
        isYoutubeLikeSourceId(status.songId) -> status.songId
        else -> null
    }
    val normalizedStableTrackId = when {
        !resolvedStableTrackId.isNullOrBlank() -> resolvedStableTrackId
        hasSourceIdentity && resolvedSourceProvider == SOURCE_PROVIDER_YOUTUBE -> {
            buildSourceStableTrackId(SOURCE_PROVIDER_YOUTUBE, resolvedSourceId.orEmpty())
        }
        else -> null
    }
    val nextPlayback = currentPlayback.copy(
        playMode = PlaybackMode.Jukebox,
        isRunning = resolvedIsRunning,
        isPaused = resolvedIsPaused,
        playTitle = displayTitle,
        trackTitle = resolvedTrackTitle,
        trackArtist = resolvedTrackArtist,
        trackDurationSeconds = status.trackDurationSeconds ?: currentPlayback.trackDurationSeconds,
        castTotalBeats = status.totalBeats ?: currentPlayback.castTotalBeats,
        castTotalBranches = status.totalBranches ?: currentPlayback.castTotalBranches,
        lastSourceProvider = resolvedSourceProvider,
        lastSourceId = resolvedSourceId,
        lastStableTrackId = normalizedStableTrackId,
        lastYouTubeId = resolvedYouTubeId,
        lastJobId = resolvedLastJobId,
        analysisErrorMessage = if (status.error.isNotBlank()) status.error else currentPlayback.analysisErrorMessage,
        analysisInFlight = resolvedIsLoading,
        isCastLoading = resolvedIsLoading,
        activeVizIndex = if ((status.activeVizIndex ?: -1) in 0 until visualizationCount) {
            status.activeVizIndex ?: currentPlayback.activeVizIndex
        } else {
            currentPlayback.activeVizIndex
        }
    )
    return current.copy(
        playback = nextPlayback,
        tuning = if (status.resolvedThreshold != null) {
            current.tuning.copy(threshold = status.resolvedThreshold)
        } else {
            current.tuning
        }
    )
}
