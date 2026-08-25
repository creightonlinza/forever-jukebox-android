package com.foreverjukebox.app.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.foreverjukebox.app.engine.JukeboxConfig
import com.foreverjukebox.app.engine.RandomMode
import com.foreverjukebox.app.engine.createRng
import com.foreverjukebox.app.engine.normalizeAnalysis
import com.foreverjukebox.app.export.ExportedAudioStore
import com.foreverjukebox.app.export.JukeboxPathGenerator
import com.foreverjukebox.app.export.M4aExportEncoder
import com.foreverjukebox.app.export.OfflineJukeboxRenderer
import com.foreverjukebox.app.playback.PlaybackController
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Owns the offline audio-export pipeline: plans a fresh jukebox path from the
 * cached local analysis, renders it through the native DSP on a second player
 * instance, encodes to M4A, and publishes the result to the Music collection.
 * Live playback is untouched and keeps running throughout.
 */
class ExportCoordinator(
    private val scope: CoroutineScope,
    private val application: Application,
    private val controller: PlaybackController,
    private val getState: () -> UiState,
    private val updateState: ((UiState) -> UiState) -> Unit,
    private val audioLoadHold: AudioLoadHold,
    private val logError: (String, Throwable) -> Unit
) {
    private var exportJob: Job? = null

    fun isExporting(): Boolean = exportJob?.isActive == true

    fun startExport(durationSeconds: Int) {
        if (isExporting()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val state = getState()
        if (!shouldShowExportAction(state.appMode, state.playback, Build.VERSION.SDK_INT)) return
        val jsonPath = state.localAnalysisJsonPath
        if (jsonPath.isNullOrBlank()) {
            setError("Track analysis is unavailable for export.")
            return
        }
        val player = controller.player
        if (!player.hasAudio()) {
            setError("Playback audio is not ready yet.")
            return
        }
        if (player.getSampleRate() > M4aExportEncoder.MAX_AAC_SAMPLE_RATE ||
            player.getChannelCount() > M4aExportEncoder.MAX_AAC_CHANNELS
        ) {
            setError("This track's audio format can't be exported.")
            return
        }
        val request = buildRequest(state, jsonPath, clampExportDurationSeconds(durationSeconds))
        updateState { it.copy(export = ExportUiState(isExporting = true)) }
        exportJob = scope.launch(Dispatchers.Default) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runExport(request)
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        updateState { it.copy(export = ExportUiState()) }
    }

    fun consumeExportResult() {
        updateState { it.copy(export = it.export.copy(completedFileName = null, errorMessage = null)) }
    }

    // Everything selection-dependent is snapshotted up front so the export is
    // unaffected by tuning or edge edits made while it runs.
    private fun buildRequest(state: UiState, jsonPath: String, durationSeconds: Int): ExportRequest {
        val engine = controller.engine
        val liveGraph = engine.getGraphState()
        val deletedPairs = liveGraph?.allEdges
            ?.asSequence()
            ?.filter { it.deleted }
            ?.map { it.src.which to it.dest.which }
            ?.toSet()
            .orEmpty()
        val anchorPair = engine.getUserAnchorEdgeId()
            ?.let { anchorId -> liveGraph?.allEdges?.firstOrNull { it.id == anchorId } }
            ?.let { it.src.which to it.dest.which }
        val trackTitle = state.playback.trackTitle?.takeIf { it.isNotBlank() }
            ?: state.localSelectedFileName
        return ExportRequest(
            jsonPath = jsonPath,
            durationSeconds = durationSeconds,
            config = engine.getConfig(),
            deletedEdgePairs = deletedPairs,
            userAnchorPair = anchorPair,
            sectionStartBeatIndices = engine.getSectionStartBeatIndices(),
            displayName = ExportedAudioStore.buildDisplayName(trackTitle),
            title = trackTitle,
            artist = state.playback.trackArtist
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runExport(request: ExportRequest) {
        val resolver = application.contentResolver
        var pendingUri: Uri? = null
        val tempFile = createTempExportFile()
        try {
            audioLoadHold.hold {
                renderToFile(request, tempFile)
                val uri = ExportedAudioStore.insertPending(
                    resolver = resolver,
                    displayName = request.displayName,
                    title = request.title,
                    artist = request.artist
                ) ?: throw IllegalStateException("Unable to create a Music entry")
                pendingUri = uri
                ExportedAudioStore.publish(resolver, uri, tempFile)
            }
            updateState {
                it.copy(
                    export = ExportUiState(
                        isExporting = false,
                        progressPercent = 100,
                        completedFileName = request.displayName
                    )
                )
            }
        } catch (cancellation: CancellationException) {
            pendingUri?.let { ExportedAudioStore.deletePending(resolver, it) }
            updateState { it.copy(export = ExportUiState()) }
            throw cancellation
        } catch (error: Exception) {
            logError("Audio export failed", error)
            pendingUri?.let { ExportedAudioStore.deletePending(resolver, it) }
            updateState { it.copy(export = ExportUiState(errorMessage = "Audio export failed.")) }
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun renderToFile(request: ExportRequest, tempFile: File) {
        val analysisElement = withContext(Dispatchers.IO) {
            exportJson.parseToJsonElement(File(request.jsonPath).readText())
        }
        val analysis = normalizeAnalysis(analysisElement)
        check(analysis.beats.isNotEmpty()) { "Analysis contains no beats" }
        // A fresh random seed per export driving a seeded RNG: matches the web
        // app's export (each export takes a new path; the path itself is
        // internally deterministic for the chosen seed).
        val generator = JukeboxPathGenerator(
            analysis = analysis,
            config = request.config,
            deletedEdgePairs = request.deletedEdgePairs,
            userAnchorPair = request.userAnchorPair,
            rng = createRng(RandomMode.Seeded, kotlin.random.Random.nextInt())
        )
        val player = controller.player
        val encoder = M4aExportEncoder(
            sampleRate = player.getSampleRate(),
            channelCount = player.getChannelCount(),
            outputFile = tempFile
        )
        try {
            val renderer = OfflineJukeboxRenderer(
                context = application,
                livePlayer = player,
                pathGenerator = generator,
                sectionStartBeatIndices = request.sectionStartBeatIndices
            )
            var lastPercent = -1
            renderer.render(
                targetDurationSeconds = request.durationSeconds.toDouble(),
                onPcmChunk = { buffer, frames -> encoder.writePcm(buffer, frames) },
                onProgress = { rendered, total ->
                    val percent = if (total > 0) {
                        (rendered * 100 / total).toInt().coerceIn(0, 99)
                    } else {
                        0
                    }
                    if (percent > lastPercent) {
                        lastPercent = percent
                        updateState { it.copy(export = it.export.copy(progressPercent = percent)) }
                    }
                }
            )
            encoder.finish()
        } finally {
            encoder.release()
        }
    }

    private fun createTempExportFile(): File {
        val directory = File(application.cacheDir, TEMP_EXPORT_DIR)
        directory.mkdirs()
        return File(directory, "${UUID.randomUUID()}.m4a")
    }

    private fun setError(message: String) {
        updateState { it.copy(export = it.export.copy(errorMessage = message)) }
    }

    private data class ExportRequest(
        val jsonPath: String,
        val durationSeconds: Int,
        val config: JukeboxConfig,
        val deletedEdgePairs: Set<Pair<Int, Int>>,
        val userAnchorPair: Pair<Int, Int>?,
        val sectionStartBeatIndices: List<Int>,
        val displayName: String,
        val title: String?,
        val artist: String?
    )

    private companion object {
        const val TEMP_EXPORT_DIR = "export"
        val exportJson = Json { ignoreUnknownKeys = true }
    }
}
