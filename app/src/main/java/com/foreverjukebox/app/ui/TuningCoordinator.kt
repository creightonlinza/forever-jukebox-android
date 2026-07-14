package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.data.AppPreferences
import com.foreverjukebox.app.data.LOCAL_TRACK_ID_PREFIX
import com.foreverjukebox.app.engine.JukeboxConfig
import com.foreverjukebox.app.engine.JukeboxEngine
import com.foreverjukebox.app.engine.withMinimumJumpDistancePercent
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CastTuningUpdate(
    val nextTuning: TuningState,
    val castParams: String?
)

internal fun buildCastTuningResetParams(
    defaultConfig: JukeboxConfig,
    randomBranchDeltaPercentScale: Double,
    resetThreshold: Int? = null,
    audioMode: JukeboxAudioMode = JukeboxAudioMode.Off
): String {
    val minProb = (defaultConfig.minRandomBranchChance * 100.0).roundToInt().coerceIn(0, 100)
    val maxProb = (defaultConfig.maxRandomBranchChance * 100.0).roundToInt().coerceIn(0, 100)
    val ramp = (defaultConfig.randomBranchChanceDelta * randomBranchDeltaPercentScale)
        .roundToInt()
        .coerceIn(0, 100)
    val threshold = resetThreshold ?: defaultConfig.currentThreshold
    return listOf(
        "jb=${if (defaultConfig.justBackwards) 1 else 0}",
        "bl=${if (defaultConfig.justLongBranches) defaultConfig.minLongBranchPercent else 0}",
        "sq=${if (defaultConfig.removeSequentialBranches) 0 else 1}",
        "thresh=$threshold",
        "bp=$minProb,$maxProb,$ramp",
        "d=",
        TuningParamsCodec.buildAudioModeParam(audioMode)
    ).joinToString("&")
}

internal fun buildCastTuningUpdate(
    currentTuning: TuningState,
    currentAudioMode: JukeboxAudioMode = JukeboxAudioMode.Off,
    currentAudioModeWireValue: String = currentAudioMode.wireValue,
    threshold: Int,
    minProb: Double,
    maxProb: Double,
    ramp: Double,
    highlightAnchorBranch: Boolean,
    justBackwards: Boolean,
    minJumpDistancePercent: Int,
    removeSequentialBranches: Boolean,
    randomBranchDeltaPercentScale: Double,
    audioMode: JukeboxAudioMode = JukeboxAudioMode.Off,
    audioModeWireValue: String = audioMode.wireValue
): CastTuningUpdate {
    val nextTuning = currentTuning.copy(
        threshold = threshold.coerceAtLeast(2),
        minProb = (minProb * 100.0).roundToInt().coerceIn(0, 100),
        maxProb = (maxProb * 100.0).roundToInt().coerceIn(0, 100),
        ramp = (ramp * randomBranchDeltaPercentScale).roundToInt().coerceIn(0, 100),
        highlightAnchorBranch = highlightAnchorBranch,
        justBackwards = justBackwards,
        minJumpDistancePercent = minJumpDistancePercent,
        removeSequential = removeSequentialBranches
    )
    val params = mutableListOf<String>()
    if (currentTuning.justBackwards != nextTuning.justBackwards) {
        params.add("jb=${if (nextTuning.justBackwards) 1 else 0}")
    }
    if (currentTuning.minJumpDistancePercent != nextTuning.minJumpDistancePercent) {
        params.add("bl=${nextTuning.minJumpDistancePercent}")
    }
    if (currentTuning.removeSequential != nextTuning.removeSequential) {
        params.add("sq=${if (nextTuning.removeSequential) 0 else 1}")
    }
    if (currentTuning.threshold != nextTuning.threshold) {
        params.add("thresh=${nextTuning.threshold}")
    }
    if (currentTuning.branchProbabilityFields() != nextTuning.branchProbabilityFields()) {
        params.add("bp=${nextTuning.minProb},${nextTuning.maxProb},${nextTuning.ramp}")
    }
    if (currentTuning.highlightAnchorBranch != nextTuning.highlightAnchorBranch) {
        params.add(TuningParamsCodec.buildHighlightParam(nextTuning.highlightAnchorBranch))
    }
    val currentCastAudioMode = currentAudioModeWireValue.trim()
    val nextCastAudioMode = audioModeWireValue.trim()
    if (currentCastAudioMode != nextCastAudioMode) {
        TuningParamsCodec.buildAudioModeParam(nextCastAudioMode)?.let { params.add(it) }
    }
    val castParams = params.joinToString("&").ifBlank { null }
    return CastTuningUpdate(nextTuning = nextTuning, castParams = castParams)
}

/**
 * Saved-tuning key for the current Local-mode track. On-device the playback state carries the
 * `local-`-prefixed id directly; while casting a local track the receiver reports the bare
 * cache fingerprint as the job id (see castLocalTrackInternal), so the prefix is restored.
 */
internal fun localTrackTuningId(state: UiState): String? {
    if (state.appMode != AppMode.Local) return null
    val playback = state.playback
    val lastJobId = playback.lastJobId?.takeIf { it.isNotBlank() } ?: return null
    if (lastJobId.startsWith(LOCAL_TRACK_ID_PREFIX)) {
        return lastJobId
    }
    if (playback.isCasting && !playback.localSourceUri.isNullOrBlank()) {
        return LOCAL_TRACK_ID_PREFIX + lastJobId
    }
    return null
}

private data class CastBranchProbabilityFields(
    val minProb: Int,
    val maxProb: Int,
    val ramp: Int
)

private fun TuningState.branchProbabilityFields(): CastBranchProbabilityFields {
    return CastBranchProbabilityFields(
        minProb = minProb,
        maxProb = maxProb,
        ramp = ramp
    )
}

class TuningCoordinator(
    private val engine: JukeboxEngine,
    private val defaultConfig: JukeboxConfig,
    private val preferences: AppPreferences,
    private val playbackCoordinator: PlaybackCoordinator,
    private val castPlaybackCoordinator: CastPlaybackCoordinator,
    private val getState: () -> UiState,
    private val updateState: ((UiState) -> UiState) -> Unit,
    private val randomBranchDeltaPercentScale: Double,
    private val persistLocalTrackTuning: suspend (localId: String, params: String?) -> Unit,
    private val clearLocalTrackTuning: suspend (localId: String) -> Unit
) {
    private fun currentLocalTrackId(): String? = localTrackTuningId(getState())

    suspend fun applyTuning(
        threshold: Int,
        minProb: Double,
        maxProb: Double,
        ramp: Double,
        highlightAnchorBranch: Boolean,
        justBackwards: Boolean,
        minJumpDistancePercent: Int,
        removeSequentialBranches: Boolean,
        audioMode: JukeboxAudioMode,
        audioModeWireValue: String = audioMode.wireValue
    ) {
        if (getState().playback.isCasting) {
            applyCastTuning(
                threshold = threshold,
                minProb = minProb,
                maxProb = maxProb,
                ramp = ramp,
                highlightAnchorBranch = highlightAnchorBranch,
                justBackwards = justBackwards,
                minJumpDistancePercent = minJumpDistancePercent,
                removeSequentialBranches = removeSequentialBranches,
                audioMode = audioMode,
                audioModeWireValue = audioModeWireValue
            )
            return
        }
        applyLocalTuning(
            threshold = threshold,
            minProb = minProb,
            maxProb = maxProb,
            ramp = ramp,
            highlightAnchorBranch = highlightAnchorBranch,
            justBackwards = justBackwards,
            minJumpDistancePercent = minJumpDistancePercent,
            removeSequentialBranches = removeSequentialBranches
        )
    }

    suspend fun resetTuningDefaults() {
        if (getState().playback.isCasting) {
            resetCastTuningDefaults()
            return
        }
        resetLocalTuningDefaults()
    }

    private suspend fun applyCastTuning(
        threshold: Int,
        minProb: Double,
        maxProb: Double,
        ramp: Double,
        highlightAnchorBranch: Boolean,
        justBackwards: Boolean,
        minJumpDistancePercent: Int,
        removeSequentialBranches: Boolean,
        audioMode: JukeboxAudioMode,
        audioModeWireValue: String
    ) {
        val currentState = getState()
        val castUpdate = buildCastTuningUpdate(
            currentTuning = currentState.tuning,
            currentAudioMode = currentState.playback.jukeboxAudioMode,
            currentAudioModeWireValue = currentState.playback.castAudioModeWireValue,
            threshold = threshold,
            minProb = minProb,
            maxProb = maxProb,
            ramp = ramp,
            highlightAnchorBranch = highlightAnchorBranch,
            justBackwards = justBackwards,
            minJumpDistancePercent = minJumpDistancePercent,
            removeSequentialBranches = removeSequentialBranches,
            randomBranchDeltaPercentScale = randomBranchDeltaPercentScale,
            audioMode = audioMode,
            audioModeWireValue = audioModeWireValue
        )
        preferences.setHighlightAnchorBranch(highlightAnchorBranch)
        if (castUpdate.castParams != null) {
            castPlaybackCoordinator.sendCastTuningParams(castUpdate.castParams)
        }
        // Mirror applyLocalTuning's auto-save for local tracks: cast tuning edits never reach
        // the local engine, so without this they would vanish on disconnect.
        currentLocalTrackId()?.let { localId ->
            persistLocalTrackTuning(
                localId,
                TuningParamsCodec.buildSavedTuningParams(
                    tuning = castUpdate.nextTuning,
                    audioModeWireValue = audioModeWireValue
                )
            )
        }
        castPlaybackCoordinator.requestCastStatus()
    }

    private suspend fun applyLocalTuning(
        threshold: Int,
        minProb: Double,
        maxProb: Double,
        ramp: Double,
        highlightAnchorBranch: Boolean,
        justBackwards: Boolean,
        minJumpDistancePercent: Int,
        removeSequentialBranches: Boolean
    ) {
        val vizData = withContext(Dispatchers.Default) {
            val current = engine.getConfig()
            val graph = engine.getGraphState()
            val useAutoThreshold =
                current.currentThreshold == 0 && graph != null && threshold == graph.currentThreshold
            val nextConfig = current.copy(
                currentThreshold = if (useAutoThreshold) 0 else threshold,
                minRandomBranchChance = minProb,
                maxRandomBranchChance = maxProb,
                randomBranchChanceDelta = ramp,
                justBackwards = justBackwards,
                removeSequentialBranches = removeSequentialBranches
            ).withMinimumJumpDistancePercent(minJumpDistancePercent)
            engine.updateConfig(nextConfig)
            engine.rebuildGraph()
            engine.getVisualizationData()
        }
        updateState {
            it.copy(
                playback = it.playback.copy(vizData = vizData),
                tuning = it.tuning.copy(highlightAnchorBranch = highlightAnchorBranch)
            )
        }
        preferences.setHighlightAnchorBranch(highlightAnchorBranch)
        playbackCoordinator.syncTuningState()
        currentLocalTrackId()?.let { localId ->
            persistLocalTrackTuning(localId, playbackCoordinator.buildTuningParamsString())
        }
    }

    private suspend fun resetCastTuningDefaults() {
        val currentState = getState()
        castPlaybackCoordinator.sendCastTuningParams(
            buildCastTuningResetParams(
                defaultConfig = defaultConfig,
                randomBranchDeltaPercentScale = randomBranchDeltaPercentScale,
                resetThreshold = currentState.tuning.computedThreshold
            )
        )
        // Mirror resetLocalTuningDefaults: a reset while casting also discards the local
        // track's auto-saved tuning.
        currentLocalTrackId()?.let { localId -> clearLocalTrackTuning(localId) }
        castPlaybackCoordinator.requestCastStatus()
    }

    private suspend fun resetLocalTuningDefaults() {
        val preservedHighlight = getState().tuning.highlightAnchorBranch
        val vizData = withContext(Dispatchers.Default) {
            engine.clearDeletedEdges()
            engine.updateConfig(defaultConfig.copy(currentThreshold = 0))
            engine.rebuildGraph()
            engine.getVisualizationData()
        }
        updateState {
            it.copy(
                playback = it.playback.copy(vizData = vizData),
                tuning = TuningState(highlightAnchorBranch = preservedHighlight)
            )
        }
        playbackCoordinator.syncTuningState()
        currentLocalTrackId()?.let { localId -> clearLocalTrackTuning(localId) }
    }
}
