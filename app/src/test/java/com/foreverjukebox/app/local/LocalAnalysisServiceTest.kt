package com.foreverjukebox.app.local

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files

class LocalAnalysisServiceTest {
    @Test
    fun emitsExpectedStageNamesAndCompletes() = runTest {
        val cacheDir = Files.createTempDirectory("fj-local-analysis-test").toFile()
        val service = LocalAnalysisService(
            decoder = FakeDecoder(),
            resampler = PassThroughResampler(),
            analyzer = FakeAnalyzer(),
            modelExtractor = NoopModelProvider(),
            cacheDir = cacheDir
        )

        val updates = service.analyze("file:///tmp/test.mp3", "Fixture Track").toList()
        val stageOrder = updates
            .filterIsInstance<LocalAnalysisUpdate.Progress>()
            .map { it.status }
            .distinct()

        assertTrue(stageOrder.contains("Processing audio"))
        assertTrue(stageOrder.contains("Processing features"))
        assertTrue(stageOrder.contains("Processing beats"))
        assertTrue(stageOrder.contains("Wrapping up"))
        assertTrue(updates.any { it is LocalAnalysisUpdate.Completed })
    }

    @Test
    fun writesAnalysisJsonArtifactToCache() = runTest {
        val cacheDir = Files.createTempDirectory("fj-local-analysis-test").toFile()
        val service = LocalAnalysisService(
            decoder = FakeDecoder(),
            resampler = PassThroughResampler(),
            analyzer = FakeAnalyzer(),
            modelExtractor = NoopModelProvider(),
            cacheDir = cacheDir
        )

        val updates = service.analyze("file:///tmp/test.mp3", "Fixture Track").toList()
        val completed = updates.filterIsInstance<LocalAnalysisUpdate.Completed>().first()

        assertTrue(completed.artifact.analysisJsonFile.exists())
        assertTrue(completed.artifact.analysisJsonFile.readText().contains("\"engine_version\""))
        assertTrue(completed.artifact.sourceUri.startsWith("file:///"))
    }

    @Test
    fun usesCachedAnalysisWithoutReRunningDecoderOrAnalyzer() = runTest {
        val cacheDir = Files.createTempDirectory("fj-local-analysis-test").toFile()
        val uri = "file:///tmp/already-analyzed.mp3"
        val cacheKey = analysisCacheKey(uri)
        val cachedFile = File(cacheDir, "$cacheKey.analysis.json")
        cachedFile.writeText(
            """
            {
              "engine_version": 1,
              "engine_origin": "forever-jukebox-android",
              "track": {
                "title": "Cached Track",
                "artist": "Cached Artist",
                "duration": 1.0,
                "tempo": 120,
                "time_signature": 4
              },
              "sections": [],
              "bars": [],
              "beats": [],
              "tatums": [],
              "segments": []
            }
            """.trimIndent()
        )

        val decoder = CountingDecoder()
        val resampler = CountingResampler()
        val analyzer = CountingAnalyzer()
        val service = LocalAnalysisService(
            decoder = decoder,
            resampler = resampler,
            analyzer = analyzer,
            modelExtractor = NoopModelProvider(),
            cacheDir = cacheDir
        )

        val updates = service.analyze(uri, "Fallback Track").toList()
        val progressEvents = updates.filterIsInstance<LocalAnalysisUpdate.Progress>()
        val completed = updates.filterIsInstance<LocalAnalysisUpdate.Completed>().single()

        assertEquals(0, decoder.calls)
        assertEquals(0, resampler.calls)
        assertEquals(0, analyzer.calls)
        assertFalse(progressEvents.isEmpty())
        assertTrue(progressEvents.all { it.status == "Wrapping up" })
        assertEquals("Cached Track", completed.artifact.title)
        assertEquals("Cached Artist", completed.artifact.artist)
        assertEquals(cachedFile.absolutePath, completed.artifact.analysisJsonFile.absolutePath)
    }

    @Test
    fun reusesCachedAnalysisAcrossDifferentUrisWithSameCacheFingerprint() = runTest {
        val cacheDir = Files.createTempDirectory("fj-local-analysis-test").toFile()
        val sharedKey = "shared-local-file"
        val decoder = CountingSuccessfulDecoder()
        val resampler = CountingResampler()
        val analyzer = CountingSuccessfulAnalyzer()
        val service = LocalAnalysisService(
            decoder = decoder,
            resampler = resampler,
            analyzer = analyzer,
            modelExtractor = NoopModelProvider(),
            cacheDir = cacheDir,
            cacheKeyResolver = { sharedKey }
        )

        service.analyze("content://provider/audio/123", "Fixture Track").toList()
        service.analyze("content://provider/document/audio/xyz", "Fixture Track").toList()

        assertEquals(1, decoder.calls)
        assertEquals(2, resampler.calls)
        assertEquals(1, analyzer.calls)
        assertTrue(File(cacheDir, "$sharedKey.analysis.json").exists())
    }

    @Test
    fun propagatesUnsupportedAudioFormatError() = runTest {
        val cacheDir = Files.createTempDirectory("fj-local-analysis-test").toFile()
        val service = LocalAnalysisService(
            decoder = ThrowingDecoder(UnsupportedAudioFormatException("Unsupported audio format")),
            resampler = PassThroughResampler(),
            analyzer = FakeAnalyzer(),
            modelExtractor = NoopModelProvider(),
            cacheDir = cacheDir
        )

        val error = runCatching {
            service.analyze("file:///tmp/unsupported.xyz", "Fixture Track").toList()
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is UnsupportedAudioFormatException)
        assertEquals("Unsupported audio format", error?.message)
    }

    @Test
    fun propagatesGenericDecoderFailure() = runTest {
        val cacheDir = Files.createTempDirectory("fj-local-analysis-test").toFile()
        val service = LocalAnalysisService(
            decoder = ThrowingDecoder(IllegalStateException("decoder blew up")),
            resampler = PassThroughResampler(),
            analyzer = FakeAnalyzer(),
            modelExtractor = NoopModelProvider(),
            cacheDir = cacheDir
        )

        val error = runCatching {
            service.analyze("file:///tmp/bad.mp3", "Fixture Track").toList()
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is IllegalStateException)
        assertEquals("decoder blew up", error?.message)
    }

    @Test
    fun listsCachedAnalysesWithSourcePointerMetadata() = runTest {
        val cacheDir = Files.createTempDirectory("fj-local-analysis-test").toFile()
        val uri = "file:///tmp/listed-track.mp3"
        val cacheKey = analysisCacheKey(uri)
        val service = LocalAnalysisService(
            decoder = FakeDecoder(),
            resampler = PassThroughResampler(),
            analyzer = FakeAnalyzer(),
            modelExtractor = NoopModelProvider(),
            cacheDir = cacheDir
        )

        service.analyze(uri, "Fixture Track").toList()
        val entries = service.listCachedAnalyses()

        assertEquals(1, entries.size)
        assertEquals("local-$cacheKey", entries[0].localId)
        assertEquals("Fixture Track", entries[0].title)
        assertEquals(uri, entries[0].sourceUri)
        assertEquals(1.0, entries[0].durationSeconds)
        assertTrue(File(cacheDir, "$cacheKey.meta.json").exists())
    }

    @Test
    fun deleteCachedAnalysisRemovesOnlyAnalysisArtifacts() = runTest {
        val cacheDir = Files.createTempDirectory("fj-local-analysis-test").toFile()
        val uri = "file:///tmp/delete-cached-track.mp3"
        val cacheKey = analysisCacheKey(uri)
        val service = LocalAnalysisService(
            decoder = FakeDecoder(),
            resampler = PassThroughResampler(),
            analyzer = FakeAnalyzer(),
            modelExtractor = NoopModelProvider(),
            cacheDir = cacheDir
        )

        service.analyze(uri, "Fixture Track").toList()
        service.saveTuning("local-$cacheKey", "thresh=10")
        val retainedAudio = File(cacheDir, "$cacheKey.audio").apply { writeText("audio-bytes") }

        val deleted = service.deleteCachedAnalysis("local-$cacheKey")

        assertTrue(deleted)
        assertFalse(File(cacheDir, "$cacheKey.analysis.json").exists())
        assertFalse(File(cacheDir, "$cacheKey.meta.json").exists())
        assertFalse(File(cacheDir, "$cacheKey.tuning").exists())
        assertTrue(retainedAudio.exists())
    }

    @Test
    fun savedTuningRoundTripsForLocalId() = runTest {
        val cacheDir = Files.createTempDirectory("fj-local-analysis-test").toFile()
        val service = newTuningService(cacheDir)
        val localId = "local-abc123"

        service.saveTuning(localId, "thresh=12&bp=20,55,8")

        assertEquals("thresh=12&bp=20,55,8", service.readSavedTuning(localId))
        assertTrue(File(cacheDir, "abc123.tuning").exists())
    }

    @Test
    fun saveTuningWithNullOrBlankClearsExistingTuning() = runTest {
        val cacheDir = Files.createTempDirectory("fj-local-analysis-test").toFile()
        val service = newTuningService(cacheDir)
        val localId = "local-abc123"
        service.saveTuning(localId, "thresh=12")

        service.saveTuning(localId, null)
        assertNull(service.readSavedTuning(localId))
        assertFalse(File(cacheDir, "abc123.tuning").exists())

        service.saveTuning(localId, "thresh=12")
        service.saveTuning(localId, "   ")
        assertNull(service.readSavedTuning(localId))
        assertFalse(File(cacheDir, "abc123.tuning").exists())
    }

    @Test
    fun clearSavedTuningRemovesTuningFile() = runTest {
        val cacheDir = Files.createTempDirectory("fj-local-analysis-test").toFile()
        val service = newTuningService(cacheDir)
        val localId = "local-abc123"
        service.saveTuning(localId, "thresh=12")

        service.clearSavedTuning(localId)

        assertNull(service.readSavedTuning(localId))
        assertFalse(File(cacheDir, "abc123.tuning").exists())
    }

    @Test
    fun readSavedTuningReturnsNullForUnknownOrInvalidLocalId() = runTest {
        val cacheDir = Files.createTempDirectory("fj-local-analysis-test").toFile()
        val service = newTuningService(cacheDir)

        assertNull(service.readSavedTuning("local-missing"))
        assertNull(service.readSavedTuning("not-a-local-id"))
        assertNull(service.readSavedTuning("local-"))
    }

    private fun newTuningService(cacheDir: File): LocalAnalysisService = LocalAnalysisService(
        decoder = FakeDecoder(),
        resampler = PassThroughResampler(),
        analyzer = FakeAnalyzer(),
        modelExtractor = NoopModelProvider(),
        cacheDir = cacheDir
    )

    @Test
    fun usesEmbeddedTagTitleAndArtistForFreshAnalysis() = runTest {
        val cacheDir = Files.createTempDirectory("fj-local-analysis-test").toFile()
        val uri = "file:///tmp/tagged.mp3"
        val cacheKey = analysisCacheKey(uri)
        val service = LocalAnalysisService(
            decoder = TaggedDecoder(tagTitle = "Tag Title", tagArtist = "Tag Artist"),
            resampler = PassThroughResampler(),
            analyzer = FakeAnalyzer(),
            modelExtractor = NoopModelProvider(),
            cacheDir = cacheDir
        )

        val updates = service.analyze(uri, "Fixture Track").toList()
        val completed = updates.filterIsInstance<LocalAnalysisUpdate.Completed>().single()

        assertEquals("Tag Title", completed.artifact.title)
        assertEquals("Tag Artist", completed.artifact.artist)

        val entries = service.listCachedAnalyses()
        assertEquals("Tag Title", entries.single().title)
        assertEquals("Tag Artist", entries.single().artist)
        assertTrue(File(cacheDir, "$cacheKey.meta.json").exists())
    }

    @Test
    fun fallsBackToDisplayNameWhenNoTagTitle() = runTest {
        val cacheDir = Files.createTempDirectory("fj-local-analysis-test").toFile()
        val service = LocalAnalysisService(
            decoder = TaggedDecoder(tagTitle = null, tagArtist = null, displayName = "File Name.mp3"),
            resampler = PassThroughResampler(),
            analyzer = FakeAnalyzer(),
            modelExtractor = NoopModelProvider(),
            cacheDir = cacheDir
        )

        val completed = service.analyze("file:///tmp/untagged.mp3", "Fixture Track")
            .toList()
            .filterIsInstance<LocalAnalysisUpdate.Completed>()
            .single()

        assertEquals("File Name.mp3", completed.artifact.title)
        assertEquals(null, completed.artifact.artist)
    }

    private fun analysisCacheKey(uriString: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(uriString.toByteArray(Charsets.UTF_8))
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }
}

private class FakeDecoder : LocalAudioDecoderPort {
    override suspend fun decodeToMono(
        uriString: String,
        onDecodeProgress: (Int) -> Unit
    ): DecodedLocalAudio {
        onDecodeProgress(10)
        onDecodeProgress(60)
        onDecodeProgress(100)
        return DecodedLocalAudio(
            monoSamples = FloatArray(22_050) { 0f },
            sampleRate = 22_050,
            durationSeconds = 1.0,
            sourceUri = uriString,
            displayName = "Fixture Track"
        )
    }
}

private class TaggedDecoder(
    private val tagTitle: String?,
    private val tagArtist: String?,
    private val displayName: String? = "Fixture Track"
) : LocalAudioDecoderPort {
    override suspend fun decodeToMono(
        uriString: String,
        onDecodeProgress: (Int) -> Unit
    ): DecodedLocalAudio {
        onDecodeProgress(100)
        return DecodedLocalAudio(
            monoSamples = FloatArray(22_050) { 0f },
            sampleRate = 22_050,
            durationSeconds = 1.0,
            sourceUri = uriString,
            displayName = displayName,
            tagTitle = tagTitle,
            tagArtist = tagArtist
        )
    }
}

private class PassThroughResampler : AudioResampler {
    override fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray = samples
}

private class CountingResampler : AudioResampler {
    var calls: Int = 0

    override fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        calls += 1
        return samples
    }
}

private class FakeAnalyzer : LocalAnalyzer {
    override suspend fun analyze(
        essentiaSamples: FloatArray,
        essentiaSampleRate: Int,
        madmomSamples: FloatArray,
        madmomSampleRate: Int,
        essentiaProfile: String?,
        durationSeconds: Double,
        title: String?,
        artist: String?,
        madmomBeatsPortModels: List<File>,
        onStageProgress: (stage: String, percent: Int) -> Unit
    ) = buildJsonObject {
        onStageProgress("madmom beats", 50)
        onStageProgress("madmom beats", 100)
        put("engine_version", JsonPrimitive(1))
        put("engine_origin", JsonPrimitive("forever-jukebox-android"))
        put(
            "sections",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("start", JsonPrimitive(0))
                        put("duration", JsonPrimitive(1))
                        put("confidence", JsonPrimitive(1))
                    }
                )
            }
        )
        put("bars", buildJsonArray { })
        put("beats", buildJsonArray { })
        put("tatums", buildJsonArray { })
        put("segments", buildJsonArray { })
        put(
            "track",
            buildJsonObject {
                put("duration", JsonPrimitive(1.0))
                put("tempo", JsonPrimitive(120))
                put("time_signature", JsonPrimitive(4))
            }
        )
    }
}

private class CountingAnalyzer : LocalAnalyzer {
    var calls: Int = 0

    override suspend fun analyze(
        essentiaSamples: FloatArray,
        essentiaSampleRate: Int,
        madmomSamples: FloatArray,
        madmomSampleRate: Int,
        essentiaProfile: String?,
        durationSeconds: Double,
        title: String?,
        artist: String?,
        madmomBeatsPortModels: List<File>,
        onStageProgress: (stage: String, percent: Int) -> Unit
    ) = run {
        calls += 1
        throw AssertionError("Analyzer should not be called for cached analysis")
    }
}

private class CountingDecoder : LocalAudioDecoderPort {
    var calls: Int = 0

    override suspend fun decodeToMono(
        uriString: String,
        onDecodeProgress: (Int) -> Unit
    ): DecodedLocalAudio {
        calls += 1
        throw AssertionError("Decoder should not be called for cached analysis")
    }
}

private class CountingSuccessfulDecoder : LocalAudioDecoderPort {
    var calls: Int = 0

    override suspend fun decodeToMono(
        uriString: String,
        onDecodeProgress: (Int) -> Unit
    ): DecodedLocalAudio {
        calls += 1
        onDecodeProgress(100)
        return DecodedLocalAudio(
            monoSamples = FloatArray(22_050) { 0f },
            sampleRate = 22_050,
            durationSeconds = 1.0,
            sourceUri = uriString,
            displayName = "Fixture Track"
        )
    }
}

private class ThrowingDecoder(private val error: Exception) : LocalAudioDecoderPort {
    override suspend fun decodeToMono(
        uriString: String,
        onDecodeProgress: (Int) -> Unit
    ): DecodedLocalAudio {
        throw error
    }
}

private class CountingSuccessfulAnalyzer : LocalAnalyzer {
    var calls: Int = 0

    override suspend fun analyze(
        essentiaSamples: FloatArray,
        essentiaSampleRate: Int,
        madmomSamples: FloatArray,
        madmomSampleRate: Int,
        essentiaProfile: String?,
        durationSeconds: Double,
        title: String?,
        artist: String?,
        madmomBeatsPortModels: List<File>,
        onStageProgress: (stage: String, percent: Int) -> Unit
    ) = buildJsonObject {
        calls += 1
        onStageProgress("madmom beats", 100)
        put("engine_version", JsonPrimitive(1))
        put("engine_origin", JsonPrimitive("forever-jukebox-android"))
        put("sections", buildJsonArray { })
        put("bars", buildJsonArray { })
        put("beats", buildJsonArray { })
        put("tatums", buildJsonArray { })
        put("segments", buildJsonArray { })
        put(
            "track",
            buildJsonObject {
                put("title", JsonPrimitive(title ?: "Fixture Track"))
                put("duration", JsonPrimitive(durationSeconds))
                put("tempo", JsonPrimitive(120))
                put("time_signature", JsonPrimitive(4))
            }
        )
    }
}

private class NoopModelProvider : MadmomBeatsPortModelProvider {
    override fun ensureExtracted(): List<File> = emptyList()
}
