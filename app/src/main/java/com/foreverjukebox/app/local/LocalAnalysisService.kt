package com.foreverjukebox.app.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

fun interface LocalAnalysisCacheKeyResolver {
    suspend fun resolve(uriString: String): String
}

private fun shortSha256(raw: String): String {
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
    return hash.take(8).joinToString("") { "%02x".format(it) }
}

private object UriStringCacheKeyResolver : LocalAnalysisCacheKeyResolver {
    override suspend fun resolve(uriString: String): String = shortSha256(uriString)
}

private data class AnalyzerInputs(
    val essentiaSamples: FloatArray,
    val essentiaSampleRate: Int,
    val madmomSamples: FloatArray,
    val madmomSampleRate: Int,
    val essentiaProfile: String?
)

class LocalAnalysisService(
    private val decoder: LocalAudioDecoderPort,
    private val resampler: AudioResampler,
    private val analyzer: LocalAnalyzer,
    private val modelExtractor: MadmomBeatsPortModelProvider,
    private val cacheDir: File,
    private val cacheKeyResolver: LocalAnalysisCacheKeyResolver = UriStringCacheKeyResolver
) {
    @Serializable
    private data class CachedLocalAnalysisMetadata(
        val sourceUri: String,
        val title: String? = null,
        val artist: String? = null,
        val updatedAtEpochMs: Long? = null
    )

    private val cancelled = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }

    fun cancel() {
        cancelled.set(true)
        NativeAnalysisBridge.cancel()
    }

    suspend fun listCachedAnalyses(): List<CachedLocalAnalysis> = withContext(Dispatchers.IO) {
        if (!cacheDir.exists()) {
            return@withContext emptyList()
        }
        cacheDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(ANALYSIS_FILE_SUFFIX) }
            ?.mapNotNull { analysisFile ->
                val cacheKey = analysisFile.name.removeSuffix(ANALYSIS_FILE_SUFFIX)
                val analysisJson = runCatching {
                    json.parseToJsonElement(analysisFile.readText())
                }.getOrNull() ?: return@mapNotNull null
                if (!isLocalAnalysisPayload(analysisJson)) {
                    return@mapNotNull null
                }
                val metadata = readMetadata(cacheKey)
                CachedLocalAnalysis(
                    localId = "local-$cacheKey",
                    title = metadata?.title?.takeIf { it.isNotBlank() }
                        ?: extractTrackField(analysisJson, "title")
                        ?: "Local Track",
                    artist = metadata?.artist ?: extractTrackField(analysisJson, "artist"),
                    sourceUri = metadata?.sourceUri,
                    lastUpdatedEpochMs = analysisFile.lastModified()
                )
            }
            ?.sortedByDescending { it.lastUpdatedEpochMs }
            ?.toList()
            ?: emptyList()
    }

    suspend fun deleteCachedAnalysis(localId: String): Boolean = withContext(Dispatchers.IO) {
        val cacheKey = parseCacheKeyFromLocalId(localId) ?: return@withContext false
        val analysisDeleted = runCatching {
            val file = analysisFile(cacheKey)
            if (file.exists()) file.delete() else false
        }.getOrDefault(false)
        val metadataDeleted = runCatching {
            val file = metadataFile(cacheKey)
            if (file.exists()) file.delete() else false
        }.getOrDefault(false)
        analysisDeleted || metadataDeleted
    }

    fun analyze(
        uriString: String,
        fallbackTitle: String?
    ): Flow<LocalAnalysisUpdate> = callbackFlow {
        cancelled.set(false)
        NativeAnalysisBridge.resetCancellationState()
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val cacheKey = runCatching {
            cacheKeyResolver.resolve(uriString)
        }.getOrElse {
            shortSha256(uriString)
        }
        val localId = "local-$cacheKey"
        val analysisFile = cacheDir.resolve("$cacheKey.analysis.json")
        logInfo("Local analysis start: localId=$localId")
        fun emitProgress(percent: Int, status: String) {
            trySend(
                LocalAnalysisUpdate.Progress(
                    percent = percent.coerceIn(0, 100),
                    status = status
                )
            )
        }

        try {
            withContext(Dispatchers.Default) {
                if (analysisFile.exists()) {
                    emitProgress(95, "Wrapping up")
                    val cachedText = analysisFile.readText()
                    val cachedJson = json.parseToJsonElement(cachedText)
                    val cachedTitle = extractTrackField(cachedJson, "title") ?: fallbackTitle
                    val cachedArtist = extractTrackField(cachedJson, "artist")
                    writeMetadata(
                        cacheKey = cacheKey,
                        metadata = CachedLocalAnalysisMetadata(
                            sourceUri = uriString,
                            title = cachedTitle,
                            artist = cachedArtist,
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                    emitProgress(100, "Wrapping up")
                    trySend(
                        LocalAnalysisUpdate.Completed(
                            LocalAnalysisArtifact(
                                localId = localId,
                                analysisJson = cachedJson,
                                analysisJsonFile = analysisFile,
                                sourceUri = uriString,
                                title = cachedTitle,
                                artist = cachedArtist
                            )
                        )
                    )
                    close()
                    return@withContext
                }

                emitProgress(1, "Processing audio")
                logInfo("Stage Decoding start")
                val decoded = decoder.decodeToMono(uriString) { decodePercent ->
                    val mapped = 1 + (decodePercent * 0.19).toInt()
                    emitProgress(mapped, "Processing audio")
                }
                logInfo(
                    "Stage Decoding complete: samples=${decoded.monoSamples.size}, sampleRate=${decoded.sampleRate}, heap=${heapSummary()}"
                )
                ensureNotCancelled()

                emitProgress(22, "Processing audio")
                val monoSource = decoded.monoSamples
                ensureNotCancelled()

                emitProgress(30, "Processing audio")
                logInfo("Stage Resample 22050 start: sourceSamples=${monoSource.size}, heap=${heapSummary()}")
                val mono22050 = resampler.resample(monoSource, decoded.sampleRate, 22_050)
                logInfo("Stage Resample 22050 complete: samples=${mono22050.size}, heap=${heapSummary()}")
                ensureNotCancelled()

                emitProgress(45, "Processing features")
                ensureNotCancelled()

                emitProgress(62, "Processing audio")
                logInfo("Stage Upsample 44100 start: sourceSamples=${mono22050.size}, heap=${heapSummary()}")
                val mono44100From22050 = resampler.resample(mono22050, 22_050, 44_100)
                logInfo("Stage Upsample 44100 complete: samples=${mono44100From22050.size}, heap=${heapSummary()}")
                ensureNotCancelled()

                decoded.monoSamples = FloatArray(0)
                ensureNotCancelled()

                val (essentiaSamples, essentiaSampleRate, madmomSamples, madmomSampleRate, essentiaProfile) =
                    AnalyzerInputs(mono22050, 22_050, mono44100From22050, 44_100, "backend_defaults")

                val madmomBeatsPortModels = modelExtractor.ensureExtracted()
                logInfo("madmom_beats_port models ready: count=${madmomBeatsPortModels.size}")

                val analysisJson = analyzer.analyze(
                    essentiaSamples = essentiaSamples,
                    essentiaSampleRate = essentiaSampleRate,
                    madmomSamples = madmomSamples,
                    madmomSampleRate = madmomSampleRate,
                    essentiaProfile = essentiaProfile,
                    durationSeconds = decoded.durationSeconds,
                    title = decoded.displayName ?: fallbackTitle,
                    artist = null,
                    madmomBeatsPortModels = madmomBeatsPortModels
                ) { stage, stagePercent ->
                    val mapped = when (stage) {
                        "Essentia features" -> 45 + (stagePercent * 0.24).toInt()
                        "madmom beats" -> 70 + (stagePercent * 0.20).toInt()
                        else -> null
                    }
                    if (mapped != null) {
                        val status = when (stage) {
                            "Essentia features" -> "Processing features"
                            "madmom beats" -> "Processing beats"
                            else -> "Processing audio"
                        }
                        emitProgress(mapped, status)
                    }
                }
                logInfo("Stage Analyzer complete: heap=${heapSummary()}")
                ensureNotCancelled()

                emitProgress(95, "Wrapping up")
                analysisFile.writeText(analysisJson.toString())
                val resolvedTitle = decoded.displayName ?: fallbackTitle
                writeMetadata(
                    cacheKey = cacheKey,
                    metadata = CachedLocalAnalysisMetadata(
                        sourceUri = decoded.sourceUri,
                        title = resolvedTitle,
                        artist = null,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )

                emitProgress(100, "Wrapping up")
                trySend(
                    LocalAnalysisUpdate.Completed(
                        LocalAnalysisArtifact(
                            localId = localId,
                            analysisJson = analysisJson,
                            analysisJsonFile = analysisFile,
                            sourceUri = decoded.sourceUri,
                            title = resolvedTitle,
                            artist = null
                        )
                    )
                )
                close()
            }
        } catch (cancelledError: CancellationException) {
            logInfo("Local analysis cancelled: localId=$localId")
            close(cancelledError)
        } catch (unsupported: UnsupportedAudioFormatException) {
            logError("Local analysis unsupported format: ${unsupported.message}", unsupported)
            close(unsupported)
        } catch (error: Throwable) {
            logError("Local analysis failed: ${error.message}", error)
            close(error)
        }

        awaitClose {}
    }

    private suspend fun ensureNotCancelled() {
        currentCoroutineContext().ensureActive()
        if (cancelled.get()) {
            throw CancellationException("Local analysis cancelled")
        }
    }

    private fun analysisFile(cacheKey: String): File =
        cacheDir.resolve("$cacheKey$ANALYSIS_FILE_SUFFIX")

    private fun metadataFile(cacheKey: String): File =
        cacheDir.resolve("$cacheKey$METADATA_FILE_SUFFIX")

    private fun readMetadata(cacheKey: String): CachedLocalAnalysisMetadata? {
        val file = metadataFile(cacheKey)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(CachedLocalAnalysisMetadata.serializer(), file.readText())
        }.getOrNull()
    }

    private fun writeMetadata(cacheKey: String, metadata: CachedLocalAnalysisMetadata) {
        runCatching {
            metadataFile(cacheKey).writeText(
                json.encodeToString(CachedLocalAnalysisMetadata.serializer(), metadata)
            )
        }
    }

    private fun parseCacheKeyFromLocalId(localId: String): String? {
        if (!localId.startsWith(LOCAL_ID_PREFIX)) return null
        val value = localId.removePrefix(LOCAL_ID_PREFIX)
        return value.takeIf { it.isNotBlank() && it.none { ch -> ch == '/' || ch == '\\' } }
    }

    private fun isLocalAnalysisPayload(payload: JsonElement): Boolean {
        val root = payload.jsonObject
        if (root.containsKey("status") || root.containsKey("result")) {
            return false
        }
        return root.containsKey("track") &&
            root.containsKey("beats") &&
            root.containsKey("segments")
    }

    companion object {
        private const val TAG = "LocalAnalysisService"
        private const val ANALYSIS_CACHE_KEY_VERSION = "v2"
        private const val LOCAL_ID_PREFIX = "local-"
        private const val ANALYSIS_FILE_SUFFIX = ".analysis.json"
        private const val METADATA_FILE_SUFFIX = ".meta.json"

        private fun heapSummary(): String {
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val maxMb = runtime.maxMemory() / (1024 * 1024)
            return "${usedMb}MB/${maxMb}MB"
        }

        private fun logInfo(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        private fun logError(message: String, error: Throwable? = null) {
            runCatching { Log.e(TAG, message, error) }
        }

        private fun extractTrackField(analysisJson: JsonElement, field: String): String? {
            val track = analysisJson.jsonObject["track"]?.jsonObject ?: return null
            return (track[field] as? JsonPrimitive)?.contentOrNull
        }

        fun create(context: Context): LocalAnalysisService {
            return LocalAnalysisService(
                decoder = LocalAudioDecoder(context),
                resampler = NativeSpeexAudioResampler(),
                analyzer = NativeLocalAnalyzer(),
                modelExtractor = MadmomBeatsPortModelExtractor(context),
                cacheDir = File(context.cacheDir, "jukebox-cache"),
                cacheKeyResolver = AndroidLocalAnalysisCacheKeyResolver(context)
            )
        }

        private class AndroidLocalAnalysisCacheKeyResolver(
            private val context: Context
        ) : LocalAnalysisCacheKeyResolver {
            override suspend fun resolve(uriString: String): String = withContext(Dispatchers.IO) {
                val uri = runCatching { uriString.toUri() }.getOrNull()
                    ?: return@withContext shortSha256(uriString)

                val metadata = if (uri.scheme.equals("file", ignoreCase = true)) {
                    OpenableMetadata(null, null, null)
                } else {
                    queryOpenableMetadata(uri)
                }
                val hasMetadata = !metadata.displayName.isNullOrBlank() ||
                    metadata.size != null ||
                    metadata.lastModified != null

                val fingerprint = buildString {
                    append(ANALYSIS_CACHE_KEY_VERSION)
                    append("|scheme=").append(uri.scheme.orEmpty())
                    append("|authority=").append(uri.authority.orEmpty())

                    val documentId = runCatching {
                        if (DocumentsContract.isDocumentUri(context, uri)) {
                            DocumentsContract.getDocumentId(uri)
                        } else {
                            null
                        }
                    }.getOrNull()

                    if (!documentId.isNullOrBlank()) {
                        append("|docId=").append(documentId)
                    }

                    if (uri.scheme.equals("file", ignoreCase = true)) {
                        val path = uri.path.orEmpty()
                        val file = File(path)
                        append("|path=").append(path)
                        if (file.exists()) {
                            append("|size=").append(file.length())
                            append("|modified=").append(file.lastModified())
                        }
                    } else {
                        metadata.displayName?.let { append("|name=").append(it) }
                        metadata.size?.let { append("|size=").append(it) }
                        metadata.lastModified?.let { append("|modified=").append(it) }
                    }

                    if (
                        documentId.isNullOrBlank() &&
                        !uri.scheme.equals("file", ignoreCase = true) &&
                        !hasMetadata
                    ) {
                        append("|uri=").append(uriString)
                    }
                }

                return@withContext shortSha256(fingerprint)
            }

            private data class OpenableMetadata(
                val displayName: String?,
                val size: Long?,
                val lastModified: Long?
            )

            private fun queryOpenableMetadata(uri: Uri): OpenableMetadata {
                return runCatching {
                    val projection = arrayOf(
                        OpenableColumns.DISPLAY_NAME,
                        OpenableColumns.SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    )
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) {
                            return@use OpenableMetadata(null, null, null)
                        }
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        val modifiedIndex =
                            cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        OpenableMetadata(
                            displayName = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                                cursor.getString(nameIndex)
                            } else {
                                null
                            },
                            size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                                cursor.getLong(sizeIndex)
                            } else {
                                null
                            },
                            lastModified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                                cursor.getLong(modifiedIndex)
                            } else {
                                null
                            }
                        )
                    } ?: OpenableMetadata(null, null, null)
                }.getOrDefault(OpenableMetadata(null, null, null))
            }
        }
    }
}
