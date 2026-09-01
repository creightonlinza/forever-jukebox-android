package com.foreverjukebox.app.ui

import android.app.Application
import android.net.Uri
import androidx.core.net.toUri
import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.data.LOCAL_TRACK_ID_PREFIX
import com.foreverjukebox.app.local.AudioTooLargeException
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalAnalysisCoordinator(
    private val scope: CoroutineScope,
    private val application: Application,
    private val localAnalysisService: LocalAnalysisService,
    private val controller: PlaybackController,
    private val playbackCoordinator: PlaybackCoordinator,
    private val castPlaybackCoordinator: CastPlaybackCoordinator,
    private val getState: () -> UiState,
    private val updateState: ((UiState) -> UiState) -> Unit,
    private val applyActiveTab: (TabId, Boolean) -> Unit,
    private val logError: (String, Throwable) -> Unit,
    private val diagnostics: DiagnosticsGateway,
    private val audioLoadHold: AudioLoadHold
) {
    private var localAnalysisJob: Job? = null

    fun isAnalysisRunning(): Boolean = localAnalysisJob?.isActive == true

    fun startLocalAnalysis(
        uri: Uri,
        displayName: String?,
        initialArtist: String? = null,
        playAfterLoaded: Boolean = false,
        source: String = "file"
    ) {
        val state = getState()
        if (state.appMode != AppMode.Local) return
        diagnostics.logAnalysisStarted(source)
        if (shouldCancelLocalAnalysisOnInputChange(
                mode = state.appMode,
                isLocalAnalysisRunning = isAnalysisRunning()
            )
        ) {
            cancelLocalAnalysisInternal(showCancelledMessage = false)
        }
        val resolvedName = displayName?.takeIf { it.isNotBlank() } ?: "Local Track"
        val resolvedArtist = initialArtist?.trim()?.takeIf { it.isNotBlank() }
        updateState {
            it.copy(
                localSelectedFileName = resolvedName,
                localAnalysisJsonPath = null,
                localCachedTrackErrorMessage = null
            )
        }
        playbackCoordinator.resetForNewTrack(stopPlaybackService = false)
        updateState {
            it.copy(
                playback = it.playback.copy(
                    trackTitle = resolvedName,
                    trackArtist = resolvedArtist,
                    playAfterLoaded = playAfterLoaded
                )
            )
        }
        applyActiveTab(TabId.Play, true)
        playbackCoordinator.setAnalysisQueued(1, "Processing audio")
        localAnalysisJob = scope.launch {
            try {
                // Native analysis plus the decode in applyLocalAnalysisArtifact run under
                // the wakelock; both stall if the CPU suspends with the screen off.
                audioLoadHold.hold {
                    localAnalysisService.analyze(uri.toString(), resolvedName).collect { update ->
                        when (update) {
                            is LocalAnalysisUpdate.Progress -> {
                                playbackCoordinator.setAnalysisProgress(update.percent, update.status)
                            }
                            is LocalAnalysisUpdate.Completed -> {
                                diagnostics.logAnalysisCompleted(source)
                                applyLocalAnalysisArtifact(update.artifact)
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // No-op: user cancelled.
            } catch (_: UnsupportedAudioFormatException) {
                diagnostics.logAnalysisFailed(source, "unsupported_format")
                playbackCoordinator.setAnalysisError("Unsupported audio format", expected = true)
                applyActiveTab(TabId.Input, true)
            } catch (error: AudioTooLargeException) {
                diagnostics.logAnalysisFailed(source, "too_large")
                playbackCoordinator.setAnalysisError(
                    ErrorDisplay.clean(error.message, "This track is too large to analyze on this device."),
                    expected = true
                )
                applyActiveTab(TabId.Input, true)
            } catch (error: NativeLocalAnalysisNotReadyException) {
                diagnostics.logAnalysisFailed(source, "native_not_ready")
                playbackCoordinator.setAnalysisError(
                    ErrorDisplay.clean(error.message, "Native local analysis is unavailable."),
                    cause = error
                )
                applyActiveTab(TabId.Input, true)
            } catch (error: IOException) {
                handleAnalysisFailure(source, error)
            } catch (error: IllegalArgumentException) {
                handleAnalysisFailure(source, error)
            } catch (error: IllegalStateException) {
                handleAnalysisFailure(source, error)
            } catch (error: SecurityException) {
                handleAnalysisFailure(source, error)
            } finally {
                localAnalysisJob = null
            }
        }
    }

    private fun handleAnalysisFailure(source: String, error: Throwable) {
        diagnostics.logAnalysisFailed(source, error.javaClass.simpleName)
        logError("Local analysis failed", error)
        val message = ErrorDisplay.clean(error.message, "Local analysis failed.")
        playbackCoordinator.setAnalysisError(message, cause = error)
        applyActiveTab(TabId.Input, true)
    }

    fun openCachedLocalTrack(
        localId: String,
        playAfterLoaded: Boolean = false
    ) {
        if (getState().appMode != AppMode.Local) return
        val cachedTrack = getState().localCachedTracks.firstOrNull { it.localId == localId } ?: return
        val sourceUri = cachedTrack.sourceUri
        if (sourceUri.isNullOrBlank()) {
            updateState {
                it.copy(
                    localCachedTrackErrorMessage =
                        "This analysis isn't linked to a source file. Use Add Audio to re-link it."
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
                            "The source audio file is no longer available. Use Add Audio to analyze it again."
                    )
                }
                return@launch
            }
            startLocalAnalysis(
                uri = sourceUri.toUri(),
                displayName = cachedTrack.title,
                initialArtist = cachedTrack.artist,
                playAfterLoaded = playAfterLoaded,
                source = "cached"
            )
        }
    }

    fun deleteCachedLocalTrack(localId: String) {
        scope.launch {
            localAnalysisService.deleteCachedAnalysis(localId)
            // The deleted track can be the one currently loaded; drop a path
            // whose file is gone so actions that re-read it (audio export)
            // report a specific error instead of failing generically.
            val analysisPath = getState().localAnalysisJsonPath
            val analysisFileDeleted = analysisPath != null &&
                withContext(Dispatchers.IO) { !File(analysisPath).exists() }
            if (analysisFileDeleted) {
                updateState { it.copy(localAnalysisJsonPath = null) }
            }
            refreshLocalCachedTracks()
            playbackCoordinator.refreshCacheSize()
        }
    }

    fun dismissLocalCachedTrackErrorDialog() {
        updateState { it.copy(localCachedTrackErrorMessage = null) }
    }

    fun cancelLocalAnalysis() {
        cancelLocalAnalysisInternal(showCancelledMessage = false)
        if (getState().playback.isCasting) {
            // The previous track is still playing on the receiver: don't reset playback (that would
            // stop the cast timers and wipe the cast track) or kick to the Input tab. Clearing the
            // provisional metadata lets the next receiver status backfill the playing track.
            updateState { current -> stateAfterCastAnalysisCancel(current) }
            castPlaybackCoordinator.requestCastStatus()
            return
        }
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
                        sourceUri = cached.sourceUri,
                        durationSeconds = cached.durationSeconds
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
        if (castLocalArtifact(artifact)) {
            return
        }
        playbackCoordinator.setAudioLoading(true)
        playbackCoordinator.setAnalysisProgress(0, "Loading audio")
        // Progress posts ride the coordinator scope and the decoder has no cancellation
        // points, so a cancelled analysis would keep reporting decode progress after its
        // loading state was torn down. Dropping posts once this job dies keeps that decode
        // from re-raising the loading overlay and the playback-change lock.
        val analysisJob = currentCoroutineContext()[Job]
        withContext(Dispatchers.Default) {
            controller.player.loadUri(application, artifact.sourceUri.toUri()) { percent ->
                scope.launch(Dispatchers.Main) {
                    if (analysisJob?.isActive == true) {
                        playbackCoordinator.setDecodeProgress(percent)
                    }
                }
            }
            controller.engine.refreshAnchorJump()
        }
        // The decode runs long enough for a Cast session to start partway through it; the
        // decoded track belongs to the device, so it is dropped in favor of the handoff.
        if (castLocalArtifact(artifact)) {
            return
        }
        updateState {
            it.copy(
                playback = it.playback.copy(
                    audioLoaded = true,
                    audioLoading = false,
                    lastJobId = artifact.localId,
                    lastYouTubeId = null,
                    // Marks this as a Local-mode cast candidate for connect-time auto-cast.
                    localSourceUri = artifact.sourceUri,
                    trackTitle = artifact.title,
                    trackArtist = artifact.artist
                )
            )
        }
        val savedTuning = localAnalysisService.readSavedTuning(artifact.localId)
        playbackCoordinator.setPendingTuningParams(savedTuning)
        playbackCoordinator.applyAnalysisResult(
            TrackAnalysisResult(
                status = "complete",
                legacyVideoId = artifact.localId,
                result = artifact.analysisJson
            )
        )
        refreshLocalCachedTracks()
        applyActiveTab(TabId.Play, true)
    }

    /**
     * Sends a finished analysis to the relay instead of loading it into the device player when a
     * Cast device is connected in Local mode. Returns true when the artifact was handed off.
     */
    private suspend fun castLocalArtifact(artifact: LocalAnalysisArtifact): Boolean {
        val current = getState()
        if (current.appMode != AppMode.Local || !current.playback.isCasting) {
            return false
        }
        val cacheKey = artifact.localId.removePrefix(LOCAL_TRACK_ID_PREFIX)
        val savedTuning = localAnalysisService.readSavedTuning(artifact.localId)
        val handedOff = castPlaybackCoordinator.castLocalTrack(
            cacheKey = cacheKey,
            sourceUri = artifact.sourceUri,
            title = artifact.title,
            artist = artifact.artist,
            tuningParams = savedTuning
        )
        if (!handedOff) {
            // The session dropped (or casting became unavailable) between the state
            // check and the handoff; the caller loads the track on the device instead
            // of discarding the finished analysis.
            return false
        }
        // The cast transfer state does not cover the device decode's loading flag, and the
        // playback-change lock reads it.
        playbackCoordinator.setAudioLoading(false)
        refreshLocalCachedTracks()
        applyActiveTab(TabId.Play, true)
        return true
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
