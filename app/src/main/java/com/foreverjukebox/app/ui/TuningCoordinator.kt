package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppPreferences
import com.foreverjukebox.app.engine.JukeboxConfig
import com.foreverjukebox.app.engine.JukeboxEngine
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CastTuningUpdate(
    val nextTuning: TuningState,
    val castParams: String
)

internal fun buildCastTuningResetParams(highlightAnchorBranch: Boolean): String? {
    return if (highlightAnchorBranch) "ah=1" else null
}

internal fun buildCastTuningUpdate(
    currentTuning: TuningState,
    threshold: Int,
    minProb: Double,
    maxProb: Double,
    ramp: Double,
    highlightAnchorBranch: Boolean,
    justBackwards: Boolean,
    justLongBranches: Boolean,
    removeSequentialBranches: Boolean,
    randomBranchDeltaPercentScale: Double,
    audioMode: JukeboxAudioMode = JukeboxAudioMode.Off
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
    val castParams = TuningParamsCodec.buildFromTuningState(nextTuning, audioMode)
    return CastTuningUpdate(nextTuning = nextTuning, castParams = castParams)
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
        audioMode: JukeboxAudioMode
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
                audioMode = audioMode
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
        audioMode: JukeboxAudioMode
    ) {
        val castUpdate = buildCastTuningUpdate(
            currentTuning = getState().tuning,
            threshold = threshold,
            minProb = minProb,
            maxProb = maxProb,
            ramp = ramp,
            highlightAnchorBranch = highlightAnchorBranch,
            justBackwards = justBackwards,
            justLongBranches = justLongBranches,
            removeSequentialBranches = removeSequentialBranches,
            randomBranchDeltaPercentScale = randomBranchDeltaPercentScale,
            audioMode = audioMode
        )
        preferences.setHighlightAnchorBranch(highlightAnchorBranch)
        castPlaybackCoordinator.sendCastTuningParams(castUpdate.castParams)
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
        val preservedHighlight = getState().tuning.highlightAnchorBranch
        castPlaybackCoordinator.sendCastTuningParams(buildCastTuningResetParams(preservedHighlight))
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
