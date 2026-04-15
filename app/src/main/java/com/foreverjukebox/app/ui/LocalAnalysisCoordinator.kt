package com.foreverjukebox.app.ui

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import com.foreverjukebox.app.data.AnalysisResponse
import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.data.buildJobStableTrackId
import com.foreverjukebox.app.local.LocalAnalysisArtifact
import com.foreverjukebox.app.local.LocalAnalysisService
import com.foreverjukebox.app.local.LocalAnalysisUpdate
import com.foreverjukebox.app.local.NativeLocalAnalysisNotReadyException
import com.foreverjukebox.app.local.UnsupportedAudioFormatException
import com.foreverjukebox.app.playback.PlaybackController
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalAnalysisCoordinator(
    private val scope: CoroutineScope,
    private val application: Application,
    private val localAnalysisService: LocalAnalysisService,
    private val controller: PlaybackController,
    private val playbackCoordinator: PlaybackCoordinator,
    private val getState: () -> UiState,
    private val updateState: ((UiState) -> UiState) -> Unit,
    private val applyActiveTab: (TabId, Boolean) -> Unit,
    private val logError: (String, Throwable) -> Unit
) {
    private var localAnalysisJob: Job? = null

    fun isAnalysisRunning(): Boolean = localAnalysisJob?.isActive == true

    fun startLocalAnalysis(uri: Uri, displayName: String?) {
        val state = getState()
        if (state.appMode != AppMode.Local) return
        if (shouldCancelLocalAnalysisOnInputChange(
                mode = state.appMode,
                isLocalAnalysisRunning = isAnalysisRunning()
            )
        ) {
            cancelLocalAnalysisInternal(showCancelledMessage = false)
        }
        val resolvedName = displayName?.takeIf { it.isNotBlank() } ?: "Local Track"
        updateState {
            it.copy(
                localSelectedFileName = resolvedName,
                localAnalysisJsonPath = null,
                localCachedTrackErrorMessage = null
            )
        }
        playbackCoordinator.resetForNewTrack()
        applyActiveTab(TabId.Play, true)
        playbackCoordinator.setAnalysisQueued(1, "Processing audio")
        localAnalysisJob = scope.launch {
            try {
                localAnalysisService.analyze(uri.toString(), resolvedName).collect { update ->
                    when (update) {
                        is LocalAnalysisUpdate.Progress -> {
                            playbackCoordinator.setAnalysisProgress(update.percent, update.status)
                        }
                        is LocalAnalysisUpdate.Completed -> {
                            applyLocalAnalysisArtifact(update.artifact)
                        }
                    }
                }
            } catch (_: CancellationException) {
                // No-op: user cancelled.
            } catch (_: UnsupportedAudioFormatException) {
                playbackCoordinator.setAnalysisError("Unsupported audio format")
                applyActiveTab(TabId.Input, true)
            } catch (error: NativeLocalAnalysisNotReadyException) {
                playbackCoordinator.setAnalysisError(
                    error.message ?: "Native local analysis is unavailable."
                )
                applyActiveTab(TabId.Input, true)
            } catch (error: IOException) {
                logError("Local analysis failed", error)
                val message = error.message?.takeIf { it.isNotBlank() } ?: "Local analysis failed."
                playbackCoordinator.setAnalysisError(message)
                applyActiveTab(TabId.Input, true)
            } catch (error: IllegalArgumentException) {
                logError("Local analysis failed", error)
                val message = error.message?.takeIf { it.isNotBlank() } ?: "Local analysis failed."
                playbackCoordinator.setAnalysisError(message)
                applyActiveTab(TabId.Input, true)
            } catch (error: IllegalStateException) {
                logError("Local analysis failed", error)
                val message = error.message?.takeIf { it.isNotBlank() } ?: "Local analysis failed."
                playbackCoordinator.setAnalysisError(message)
                applyActiveTab(TabId.Input, true)
            } catch (error: SecurityException) {
                logError("Local analysis failed", error)
                val message = error.message?.takeIf { it.isNotBlank() } ?: "Local analysis failed."
                playbackCoordinator.setAnalysisError(message)
                applyActiveTab(TabId.Input, true)
            } finally {
                localAnalysisJob = null
            }
        }
    }

    fun openCachedLocalTrack(localId: String) {
        if (getState().appMode != AppMode.Local) return
        val cachedTrack = getState().localCachedTracks.firstOrNull { it.localId == localId } ?: return
        val sourceUri = cachedTrack.sourceUri
        if (sourceUri.isNullOrBlank()) {
            updateState {
                it.copy(
                    localCachedTrackErrorMessage =
                        "This cached analysis has no source file pointer. Re-open the audio file to re-link it."
                )
            }
            return
        }
        scope.launch {
            val exists = localAudioSourceExists(sourceUri)
            if (!exists) {
                updateState {
                    it.copy(
                        localCachedTrackErrorMessage =
                            "The source audio file is no longer available. Re-open the file and analyze again."
                    )
                }
                return@launch
            }
            startLocalAnalysis(sourceUri.toUri(), cachedTrack.title)
        }
    }

    fun deleteCachedLocalTrack(localId: String) {
        scope.launch {
            localAnalysisService.deleteCachedAnalysis(localId)
            refreshLocalCachedTracks()
            playbackCoordinator.refreshCacheSize()
        }
    }

    fun dismissLocalCachedTrackErrorDialog() {
        updateState { it.copy(localCachedTrackErrorMessage = null) }
    }

    fun cancelLocalAnalysis() {
        cancelLocalAnalysisInternal(showCancelledMessage = false)
        playbackCoordinator.resetForNewTrack()
        updateState { current -> stateAfterLocalAnalysisCancel(current) }
    }

    fun cancelLocalAnalysisInternal(showCancelledMessage: Boolean) {
        localAnalysisJob?.cancel()
        localAnalysisJob = null
        localAnalysisService.cancel()
        if (showCancelledMessage) {
            playbackCoordinator.setAnalysisError("Analysis cancelled.")
        }
    }

    fun refreshLocalCachedTracks() {
        scope.launch(Dispatchers.IO) {
            val cachedTracks = localAnalysisService.listCachedAnalyses()
                .map { cached ->
                    LocalCachedTrack(
                        localId = cached.localId,
                        title = cached.title,
                        artist = cached.artist,
                        sourceUri = cached.sourceUri
                    )
                }
            updateState { it.copy(localCachedTracks = cachedTracks) }
        }
    }

    private suspend fun applyLocalAnalysisArtifact(artifact: LocalAnalysisArtifact) {
        updateState {
            it.copy(
                localSelectedFileName = artifact.title ?: it.localSelectedFileName,
                localAnalysisJsonPath = artifact.analysisJsonFile.absolutePath
            )
        }
        playbackCoordinator.setAudioLoading(true)
        playbackCoordinator.setAnalysisProgress(0, "Loading audio")
        withContext(Dispatchers.Default) {
            controller.player.loadUri(application, artifact.sourceUri.toUri()) { percent ->
                scope.launch(Dispatchers.Main) {
                    playbackCoordinator.setDecodeProgress(percent)
                }
            }
        }
        controller.syncAutocanonizerAudio()
        updateState {
            it.copy(
                playback = it.playback.copy(
                    audioLoaded = true,
                    audioLoading = false,
                    lastSourceProvider = null,
                    lastSourceId = null,
                    lastStableTrackId = buildJobStableTrackId(artifact.localId),
                    lastYouTubeId = artifact.localId,
                    trackTitle = artifact.title,
                    trackArtist = artifact.artist
                )
            )
        }
        playbackCoordinator.applyAnalysisResult(
            AnalysisResponse(
                status = "complete",
                youtubeId = artifact.localId,
                result = artifact.analysisJson
            )
        )
        refreshLocalCachedTracks()
        applyActiveTab(TabId.Play, true)
    }

    private suspend fun localAudioSourceExists(uriString: String): Boolean = withContext(Dispatchers.IO) {
        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return@withContext false
        when (uri.scheme?.lowercase()) {
            "file" -> {
                val path = uri.path ?: return@withContext false
                val sourceFile = File(path)
                sourceFile.exists() && sourceFile.isFile && sourceFile.canRead()
            }
            else -> {
                runCatching {
                    application.contentResolver
                        .openAssetFileDescriptor(uri, "r")
                        ?.use { true }
                        ?: false
                }.getOrDefault(false)
            }
        }
    }
}
