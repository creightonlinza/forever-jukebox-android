package com.foreverjukebox.app.ui

import com.foreverjukebox.app.engine.DEFAULT_MIN_LONG_BRANCH_PERCENT
import com.foreverjukebox.app.visualization.visualizationCount
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CastBranchProbabilityStatus(
    val minPercent: Int,
    val maxPercent: Int,
    val deltaPercent: Int
)

data class CastTuningStatus(
    val justBackwards: Boolean,
    val justLongBranches: Boolean,
    val minLongBranchPercent: Int,
    val removeSequentialBranches: Boolean,
    val threshold: Int?,
    val computedThreshold: Int?,
    val branchProbability: CastBranchProbabilityStatus,
    val deletedEdgeIds: List<Int>,
    val highlightAnchorBranch: Boolean,
    val audioModeWireValue: String
)

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
    val supportedAudioModes: List<AudioModeOption> = emptyList(),
    val tuning: CastTuningStatus? = null
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
    val supportedAudioModes = parseSupportedAudioModes(json["supportedAudioModes"] as? JsonArray)
    val tuning = parseCastTuningStatus(json["tuning"] as? JsonObject)
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
        supportedAudioModes = supportedAudioModes,
        tuning = tuning
    )
}

private fun parseSupportedAudioModes(json: JsonArray?): List<AudioModeOption> {
    if (json == null) return emptyList()
    val parsed = linkedMapOf<String, AudioModeOption>()
    json.forEach { element ->
        val option = element as? JsonObject ?: return@forEach
        val wireValue = option.stringValue("wireValue")?.trim()?.takeIf { it.isNotBlank() }
            ?: return@forEach
        val label = option.stringValue("label")?.trim()?.takeIf { it.isNotBlank() }
            ?: return@forEach
        parsed.putIfAbsent(wireValue, AudioModeOption(wireValue = wireValue, label = label))
    }
    return parsed.values.toList()
}

private fun parseCastTuningStatus(json: JsonObject?): CastTuningStatus? {
    if (json == null) return null
    val branchProbability = json["branchProbability"] as? JsonObject ?: return null
    val audioModeWireValue = json.stringValue("audioMode")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: JukeboxAudioMode.Off.wireValue
    val justLongBranches = json.booleanValue("justLongBranches")
    val minLongBranchPercent = json.intValue("minLongBranchPercent")
        ?.takeIf { it == 0 || it in ALLOWED_BRANCH_LENGTHS }
        ?: if (justLongBranches) DEFAULT_MIN_LONG_BRANCH_PERCENT else 0
    return CastTuningStatus(
        justBackwards = json.booleanValue("justBackwards"),
        justLongBranches = justLongBranches,
        minLongBranchPercent = minLongBranchPercent,
        removeSequentialBranches = json.booleanValue("removeSequentialBranches"),
        threshold = json.intValue("threshold")?.takeIf { it >= 2 },
        computedThreshold = json.intValue("computedThreshold")?.takeIf { it >= 2 },
        branchProbability = CastBranchProbabilityStatus(
            minPercent = branchProbability.intValue("minPercent")?.coerceIn(0, 100) ?: TuningState().minProb,
            maxPercent = branchProbability.intValue("maxPercent")?.coerceIn(0, 100) ?: TuningState().maxProb,
            deltaPercent = branchProbability.intValue("deltaPercent")?.coerceIn(0, 100) ?: TuningState().ramp
        ),
        deletedEdgeIds = json["deletedEdgeIds"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.intOrNull?.takeIf { id -> id >= 0 } }
            ?: emptyList(),
        highlightAnchorBranch = json.booleanValue("highlightAnchorBranch"),
        audioModeWireValue = audioModeWireValue
    )
}

private fun JsonObject.stringValue(name: String): String? {
    return this[name]?.jsonPrimitive?.contentOrNull?.takeUnless { it == "null" }
}

private fun JsonObject.booleanValue(name: String): Boolean {
    return this[name]?.jsonPrimitive?.booleanOrNull ?: false
}

private fun JsonObject.intValue(name: String): Int? {
    return this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
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
    // Receiver metadata is per-track; only accept it when the status identifies the track the
    // sender believes is current. During a transfer the transferred track is the only acceptable
    // identity, so statuses from the previous, still-playing track can't stomp the new one.
    val currentTransfer = currentPlayback.castTransfer
    val canApplyReceiverMetadata = when {
        currentTransfer != null ->
            !status.jobId.isNullOrBlank() && status.jobId == currentTransfer.trackId
        currentPlayback.lastJobId.isNullOrBlank() -> true
        else -> status.jobId == currentPlayback.lastJobId
    }
    val isNewTrackStatus = canApplyReceiverMetadata &&
        !status.jobId.isNullOrBlank() &&
        status.jobId != currentPlayback.lastJobId
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
    val statusAudioModeWireValue = status.tuning?.audioModeWireValue
    val resolvedCastAudioModeWireValue = statusAudioModeWireValue
        ?: currentPlayback.castAudioModeWireValue
    val resolvedAudioMode = if (statusAudioModeWireValue != null) {
        JukeboxAudioMode.fromWireValue(statusAudioModeWireValue) ?: JukeboxAudioMode.Off
    } else {
        currentPlayback.jukeboxAudioMode
    }
    val resolvedSupportedAudioModes = status.supportedAudioModes
    val metadataBackfilled = (hasTitle && currentPlayback.trackTitle.isNullOrBlank()) ||
        (hasArtist && currentPlayback.trackArtist.isNullOrBlank())
    val audioModeChanged = resolvedCastAudioModeWireValue != currentPlayback.castAudioModeWireValue
    val audioModeOptionsChanged = resolvedSupportedAudioModes != currentPlayback.castSupportedAudioModes
    val displayTitle = if (
        metadataBackfilled ||
        currentPlayback.playTitle.isBlank() ||
        audioModeChanged ||
        audioModeOptionsChanged
    ) {
        buildCastPlaybackTitle(
            title = resolvedTrackTitle,
            artist = resolvedTrackArtist,
            audioModeWireValue = resolvedCastAudioModeWireValue,
            supportedAudioModes = resolvedSupportedAudioModes,
            fallback = currentPlayback.playTitle
        )
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
    val resolvedYouTubeId = currentPlayback.lastYouTubeId
    val resolvedDeleteEligible = computeDeleteEligibility(
        jobId = resolvedLastJobId,
        createdAtEpochMs = resolvedCreatedAtEpochMs
    )
    // The sender owns castTransfer; only clear it once the receiver acknowledges the transferred
    // track (or reports a terminal error). Statuses for the previous, still-playing track must not
    // stomp an in-flight transfer.
    val resolvedCastTransfer = when {
        currentTransfer == null -> null
        !status.jobId.isNullOrBlank() && status.jobId == currentTransfer.trackId -> null
        status.error.isNotBlank() -> null
        else -> currentTransfer
    }
    val nextPlayback = currentPlayback.copy(
        playMode = PlaybackMode.Jukebox,
        isRunning = resolvedIsRunning,
        isPaused = resolvedIsPaused,
        playTitle = displayTitle,
        trackTitle = resolvedTrackTitle,
        trackArtist = resolvedTrackArtist,
        // Duration/beats/branches are per-track: carry the current value only while the status is
        // for the same track; on a track change a missing field clears instead of showing the
        // previous track's numbers.
        trackDurationSeconds = when {
            !canApplyReceiverMetadata -> currentPlayback.trackDurationSeconds
            isNewTrackStatus -> status.trackDurationSeconds
            else -> status.trackDurationSeconds ?: currentPlayback.trackDurationSeconds
        },
        castTotalBeats = when {
            !canApplyReceiverMetadata -> currentPlayback.castTotalBeats
            isNewTrackStatus -> status.totalBeats
            else -> status.totalBeats ?: currentPlayback.castTotalBeats
        },
        castTotalBranches = when {
            !canApplyReceiverMetadata -> currentPlayback.castTotalBranches
            isNewTrackStatus -> status.totalBranches
            else -> status.totalBranches ?: currentPlayback.castTotalBranches
        },
        jukeboxAudioMode = resolvedAudioMode,
        castAudioModeWireValue = resolvedCastAudioModeWireValue,
        castSupportedAudioModes = resolvedSupportedAudioModes,
        lastYouTubeId = resolvedYouTubeId,
        lastTrackCreatedAtEpochMs = resolvedCreatedAtEpochMs,
        castPlaybackState = status.playbackState,
        lastJobId = resolvedLastJobId,
        analysisErrorMessage = if (status.error.isNotBlank()) {
            ErrorDisplay.clean(status.error)
        } else {
            currentPlayback.analysisErrorMessage
        },
        // analysisInFlight is owned by the local analysis pipeline; receiver loading is tracked by
        // isCastLoading/castPlaybackState so old-track statuses can't hide analysis progress.
        isCastLoading = resolvedIsLoading,
        castTransfer = resolvedCastTransfer,
        deleteEligible = resolvedDeleteEligible,
        activeVizIndex = if ((status.activeVizIndex ?: -1) in 0 until visualizationCount) {
            status.activeVizIndex ?: currentPlayback.activeVizIndex
        } else {
            currentPlayback.activeVizIndex
        }
    )
    return current.copy(
        playback = nextPlayback,
        tuning = resolveCastTuningState(current.tuning, status.tuning)
    )
}

private fun resolveCastTuningState(
    current: TuningState,
    tuning: CastTuningStatus?
): TuningState {
    if (tuning == null) return current
    return current.copy(
        threshold = tuning.threshold ?: tuning.computedThreshold ?: current.threshold,
        computedThreshold = tuning.computedThreshold,
        minProb = tuning.branchProbability.minPercent,
        maxProb = tuning.branchProbability.maxPercent,
        ramp = tuning.branchProbability.deltaPercent,
        highlightAnchorBranch = tuning.highlightAnchorBranch,
        justBackwards = tuning.justBackwards,
        minJumpDistancePercent = tuning.minLongBranchPercent,
        removeSequential = tuning.removeSequentialBranches
    )
}

private fun buildCastPlaybackTitle(
    title: String?,
    artist: String?,
    audioModeWireValue: String,
    supportedAudioModes: List<AudioModeOption>,
    fallback: String
): String {
    val baseTitle = title?.takeIf { it.isNotBlank() } ?: return fallback
    val normalizedWireValue = audioModeWireValue.trim()
    val audioModeLabel = supportedAudioModes
        .firstOrNull { it.wireValue == normalizedWireValue }
        ?.label
    val titledMode = if (
        normalizedWireValue.isBlank() ||
        normalizedWireValue == JukeboxAudioMode.Off.wireValue ||
        audioModeLabel.isNullOrBlank()
    ) {
        baseTitle
    } else {
        "$baseTitle ($audioModeLabel)"
    }
    return if (artist.isNullOrBlank()) {
        titledMode
    } else {
        "$titledMode — $artist"
    }
}

private fun parseCreatedAtEpochMs(createdAt: String?): Long? {
    val raw = createdAt?.trim().orEmpty()
    if (raw.isBlank()) {
        return null
    }
    return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
}

private fun computeDeleteEligibility(jobId: String?, createdAtEpochMs: Long?): Boolean {
    if (jobId.isNullOrBlank() || createdAtEpochMs == null) {
        return false
    }
    val ageMs = System.currentTimeMillis() - createdAtEpochMs
    return ageMs <= DELETE_ELIGIBILITY_WINDOW_MS
}

private const val DELETE_ELIGIBILITY_WINDOW_MS = 30L * 60L * 1000L
