package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.SOURCE_PROVIDER_YOUTUBE
import com.foreverjukebox.app.data.buildJobStableTrackId
import com.foreverjukebox.app.data.buildSourceStableTrackId
import com.foreverjukebox.app.visualization.visualizationCount
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CastStatusMessage(
    val jobId: String? = null,
    val createdAt: String? = null,
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

    val jobId = stringField("jobId").ifBlank { null }
    val createdAt = stringField("createdAt").ifBlank { null }
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
        jobId = jobId,
        createdAt = createdAt,
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
    val currentPlayback = current.playback
    val parsedCreatedAtEpochMs = parseCreatedAtEpochMs(status.createdAt)
    val resolvedLastJobId = when {
        !status.jobId.isNullOrBlank() -> status.jobId
        else -> currentPlayback.lastJobId
    }
    val canCarryCreatedAtFromState = resolvedLastJobId == currentPlayback.lastJobId &&
        !currentPlayback.isCastLoading
    val resolvedCreatedAtEpochMs = when {
        parsedCreatedAtEpochMs != null -> parsedCreatedAtEpochMs
        canCarryCreatedAtFromState -> currentPlayback.lastTrackCreatedAtEpochMs
        else -> null
    }
    val canApplyReceiverMetadata = resolvedCreatedAtEpochMs != null
    val hasTitle = canApplyReceiverMetadata && status.title.isNotBlank()
    val hasArtist = canApplyReceiverMetadata && status.artist.isNotBlank()
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
    val fallbackStableTrackId = when {
        status.jobId.isNullOrBlank() -> null
        isLikelyJobId(status.jobId) -> buildJobStableTrackId(status.jobId)
        else -> null
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
        else -> null
    }
    val normalizedStableTrackId = when {
        !resolvedStableTrackId.isNullOrBlank() -> resolvedStableTrackId
        hasSourceIdentity && resolvedSourceProvider == SOURCE_PROVIDER_YOUTUBE -> {
            buildSourceStableTrackId(SOURCE_PROVIDER_YOUTUBE, resolvedSourceId.orEmpty())
        }
        else -> null
    }
    val resolvedDeleteEligible = computeDeleteEligibility(
        jobId = resolvedLastJobId,
        createdAtEpochMs = resolvedCreatedAtEpochMs
    )
    val nextPlayback = currentPlayback.copy(
        playMode = PlaybackMode.Jukebox,
        isRunning = resolvedIsRunning,
        isPaused = resolvedIsPaused,
        playTitle = displayTitle,
        trackTitle = resolvedTrackTitle,
        trackArtist = resolvedTrackArtist,
        trackDurationSeconds = if (canApplyReceiverMetadata) {
            status.trackDurationSeconds ?: currentPlayback.trackDurationSeconds
        } else {
            currentPlayback.trackDurationSeconds
        },
        castTotalBeats = if (canApplyReceiverMetadata) {
            status.totalBeats ?: currentPlayback.castTotalBeats
        } else {
            currentPlayback.castTotalBeats
        },
        castTotalBranches = if (canApplyReceiverMetadata) {
            status.totalBranches ?: currentPlayback.castTotalBranches
        } else {
            currentPlayback.castTotalBranches
        },
        lastSourceProvider = resolvedSourceProvider,
        lastSourceId = resolvedSourceId,
        lastStableTrackId = normalizedStableTrackId,
        lastYouTubeId = resolvedYouTubeId,
        lastTrackCreatedAtEpochMs = resolvedCreatedAtEpochMs,
        lastJobId = resolvedLastJobId,
        analysisErrorMessage = if (status.error.isNotBlank()) status.error else currentPlayback.analysisErrorMessage,
        analysisInFlight = resolvedIsLoading,
        isCastLoading = resolvedIsLoading,
        deleteEligible = resolvedDeleteEligible,
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

private fun parseCreatedAtEpochMs(createdAt: String?): Long? {
    val raw = createdAt?.trim().orEmpty()
    if (raw.isBlank()) {
        return null
    }
    return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
}

private fun isLikelyJobId(value: String): Boolean {
    return value.length == CAST_JOB_ID_HEX_LENGTH &&
        value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

private fun computeDeleteEligibility(jobId: String?, createdAtEpochMs: Long?): Boolean {
    if (jobId.isNullOrBlank() || createdAtEpochMs == null) {
        return false
    }
    val ageMs = System.currentTimeMillis() - createdAtEpochMs
    return ageMs <= DELETE_ELIGIBILITY_WINDOW_MS
}

private const val CAST_JOB_ID_HEX_LENGTH = 32
private const val DELETE_ELIGIBILITY_WINDOW_MS = 30L * 60L * 1000L
