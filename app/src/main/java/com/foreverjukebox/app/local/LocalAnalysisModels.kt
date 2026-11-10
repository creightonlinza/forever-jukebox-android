package com.foreverjukebox.app.local

import kotlinx.serialization.json.JsonElement
import java.io.File

data class DecodedLocalAudio(
    var monoSamples: FloatArray,
    val sampleRate: Int,
    val durationSeconds: Double,
    val sourceUri: String,
    val displayName: String?,
    val tagTitle: String? = null,
    val tagArtist: String? = null
)

data class LocalAnalysisArtifact(
    val localId: String,
    val analysisJson: JsonElement,
    val analysisJsonFile: File,
    val sourceUri: String,
    val title: String?,
    val artist: String?
)

data class CachedLocalAnalysis(
    val localId: String,
    val title: String,
    val artist: String?,
    val sourceUri: String?,
    val durationSeconds: Double?,
    val lastUpdatedEpochMs: Long
)

// Pipeline stages in run order; Cached marks a stored analysis that skips them.
enum class LocalAnalysisStage { Decode, Beats, Features, WrapUp, Cached }

sealed interface LocalAnalysisUpdate {
    data class Progress(
        val percent: Int,
        val status: String,
        val stage: LocalAnalysisStage,
        // 0..1 within the stage; null when the stage reports no progress.
        val stageProgress: Float? = null
    ) : LocalAnalysisUpdate

    data class Completed(
        val artifact: LocalAnalysisArtifact
    ) : LocalAnalysisUpdate
}
