package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppPreferences
import com.foreverjukebox.app.engine.JukeboxConfig
import com.foreverjukebox.app.engine.JukeboxEngine
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
        "lg=${if (defaultConfig.justLongBranches) 1 else 0}",
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
    justLongBranches: Boolean,
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
        justLong = justLongBranches,
        removeSequential = removeSequentialBranches
    )
    val params = mutableListOf<String>()
    if (currentTuning.justBackwards != nextTuning.justBackwards) {
        params.add("jb=${if (nextTuning.justBackwards) 1 else 0}")
    }
    if (currentTuning.justLong != nextTuning.justLong) {
        params.add("lg=${if (nextTuning.justLong) 1 else 0}")
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
    private val randomBranchDeltaPercentScale: Double
) {
    suspend fun applyTuning(
        threshold: Int,
        minProb: Double,
        maxProb: Double,
        ramp: Double,
        highlightAnchorBranch: Boolean,
        justBackwards: Boolean,
        justLongBranches: Boolean,
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
                justLongBranches = justLongBranches,
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
            justLongBranches = justLongBranches,
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
        justLongBranches: Boolean,
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
            justLongBranches = justLongBranches,
            removeSequentialBranches = removeSequentialBranches,
            randomBranchDeltaPercentScale = randomBranchDeltaPercentScale,
            audioMode = audioMode,
            audioModeWireValue = audioModeWireValue
        )
        preferences.setHighlightAnchorBranch(highlightAnchorBranch)
        if (castUpdate.castParams != null) {
            castPlaybackCoordinator.sendCastTuningParams(castUpdate.castParams)
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
        justLongBranches: Boolean,
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
                justLongBranches = justLongBranches,
                removeSequentialBranches = removeSequentialBranches
            )
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
    }
}
