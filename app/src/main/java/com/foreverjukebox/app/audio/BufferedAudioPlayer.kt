package com.foreverjukebox.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.foreverjukebox.app.engine.JukeboxPlayer
import com.foreverjukebox.app.ui.JukeboxAudioMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BufferedAudioPlayer : JukeboxPlayer {
    private var sampleRate = 44100
    private var channelCount = 2
    private var nativeHandle: Long = 0
    private var durationSeconds: Double? = null
    private var jukeboxAudioMode = JukeboxAudioMode.Off
    private var duckingActive = false
    private var cowbellSamplesLoaded = false
    private val cowbellSampleIndexByName = NativeCowbellOverlayController.requiredSampleNames()
        .withIndex()
        .associate { (index, sampleName) -> sampleName to index }

    suspend fun loadFile(
        file: File,
        onProgress: ((Int) -> Unit)? = null
    ) {
        durationSeconds = null
        releaseNativePlayer()
        val decoded = withContext(Dispatchers.IO) {
            decodeToPcm(
                onProgress = onProgress,
                configureDataSource = { extractor -> extractor.setDataSource(file.absolutePath) }
            )
        }
        sampleRate = decoded.sampleRate
        channelCount = decoded.channelCount
        durationSeconds = decoded.durationSeconds
        ensureNativePlayer()
        nativeLoadPcm(nativeHandle, decoded.data)
    }

    suspend fun loadUri(
        context: Context,
        uri: Uri,
        onProgress: ((Int) -> Unit)? = null
    ) {
        durationSeconds = null
        releaseNativePlayer()
        val decoded = withContext(Dispatchers.IO) {
            decodeToPcm(
                onProgress = onProgress,
                configureDataSource = { extractor ->
                    extractor.setDataSource(context, uri, emptyMap())
                }
            )
        }
        sampleRate = decoded.sampleRate
        channelCount = decoded.channelCount
        durationSeconds = decoded.durationSeconds
        ensureNativePlayer()
        nativeLoadPcm(nativeHandle, decoded.data)
    }

    fun release() {
        releaseNativePlayer()
    }

    fun clear() {
        releaseNativePlayer()
        durationSeconds = null
    }

    fun setGain(gain: Double) {
        if (nativeHandle == 0L) return
        nativeSetGain(nativeHandle, gain.coerceIn(0.0, 1.0).toFloat())
    }

    fun setDucking(active: Boolean) {
        duckingActive = active
        if (nativeHandle == 0L) return
        nativeSetDucking(nativeHandle, active)
    }

    fun setJukeboxAudioMode(mode: JukeboxAudioMode) {
        jukeboxAudioMode = mode
        if (nativeHandle == 0L) return
        nativeSetJukeboxAudioMode(nativeHandle, mode.nativeModeCode)
    }

    fun getJukeboxAudioMode(): JukeboxAudioMode = jukeboxAudioMode

    override fun getPlaybackRate(): Double {
        if (nativeHandle == 0L) return jukeboxAudioMode.playbackRate
        return nativeGetPlaybackRate(nativeHandle)
    }

    fun cloneAudioFrom(other: BufferedAudioPlayer): Boolean {
        if (!other.hasAudio()) return false
        sampleRate = other.sampleRate
        channelCount = other.channelCount
        durationSeconds = other.durationSeconds
        jukeboxAudioMode = other.jukeboxAudioMode
        duckingActive = other.duckingActive
        releaseNativePlayer()
        ensureNativePlayer()
        return nativeCloneAudioFrom(nativeHandle, other.nativeHandle)
    }

    override fun play() {
        if (nativeHandle != 0L) {
            nativePlay(nativeHandle)
        }
    }

    override fun pause() {
        if (nativeHandle != 0L) {
            nativePause(nativeHandle)
        }
    }

    override fun stop() {
        if (nativeHandle != 0L) {
            nativeStop(nativeHandle)
        }
    }

    override fun seek(time: Double) {
        if (nativeHandle != 0L) {
            nativeSeek(nativeHandle, time)
        }
    }

    override fun scheduleJump(targetTime: Double, sourceStartTime: Double): Boolean {
        if (nativeHandle == 0L) return false
        return nativeScheduleJump(nativeHandle, targetTime, sourceStartTime)
    }

    override fun cancelScheduledJump() {
        if (nativeHandle != 0L) {
            nativeCancelScheduledJump(nativeHandle)
        }
    }

    override fun setAnchorJump(targetTime: Double, sourceStartTime: Double): Boolean {
        if (nativeHandle == 0L) return false
        return nativeSetAnchorJump(nativeHandle, targetTime, sourceStartTime)
    }

    override fun clearAnchorJump() {
        if (nativeHandle != 0L) {
            nativeClearAnchorJump(nativeHandle)
        }
    }

    override fun getCurrentTime(): Double {
        if (nativeHandle == 0L) return 0.0
        return nativeGetCurrentTime(nativeHandle)
    }

    override fun getAudioTime(): Double {
        if (nativeHandle == 0L) return 0.0
        return nativeGetAudioTime(nativeHandle)
    }

    override fun isPlaying(): Boolean {
        return nativeHandle != 0L && nativeIsPlaying(nativeHandle)
    }

    fun hasAudio(): Boolean {
        return nativeHandle != 0L && nativeHasAudio(nativeHandle) && durationSeconds != null
    }

    fun getDurationSeconds(): Double? {
        return durationSeconds
    }

    fun preloadCowbellSamples(context: Context, sampleNames: List<String>) {
        if (cowbellSamplesLoaded) return
        if (durationSeconds == null) return
        ensureNativePlayer()
        if (nativeHandle == 0L) return
        var loadedCount = 0
        for (sampleName in sampleNames) {
            val sampleIndex = cowbellSampleIndexByName[sampleName] ?: continue
            val wav = runCatching {
                context.assets.open("cowbell/sounds/$sampleName").use { input ->
                    parsePcmWav(input.readBytes())
                }
            }.getOrNull() ?: continue
            nativeLoadCowbellSample(
                nativeHandle,
                sampleIndex,
                wav.data,
                wav.sampleRate,
                wav.channelCount
            )
            loadedCount += 1
        }
        cowbellSamplesLoaded = loadedCount == sampleNames.size
    }

    fun scheduleCowbellHit(
        sampleName: String,
        targetTimeSeconds: Double,
        leftVolume: Float,
        rightVolume: Float
    ) {
        if (nativeHandle == 0L || !cowbellSamplesLoaded) return
        val sampleIndex = cowbellSampleIndexByName[sampleName] ?: return
        nativeScheduleCowbellHit(
            nativeHandle,
            sampleIndex,
            targetTimeSeconds,
            leftVolume.coerceAtLeast(0.0f),
            rightVolume.coerceAtLeast(0.0f)
        )
    }

    fun cancelCowbellHits() {
        if (nativeHandle != 0L) {
            nativeCancelCowbellHits(nativeHandle)
        }
    }

    fun cancelPendingCowbellHits() {
        if (nativeHandle != 0L) {
            nativeCancelPendingCowbellHits(nativeHandle)
        }
    }

    private fun ensureNativePlayer() {
        if (nativeHandle != 0L) return
        nativeHandle = nativeCreatePlayer(sampleRate, channelCount)
        if (nativeHandle != 0L) {
            nativeSetJukeboxAudioMode(nativeHandle, jukeboxAudioMode.nativeModeCode)
            nativeSetDucking(nativeHandle, duckingActive)
        }
    }

    private fun releaseNativePlayer() {
        if (nativeHandle == 0L) return
        nativeRelease(nativeHandle)
        nativeHandle = 0L
        cowbellSamplesLoaded = false
    }

    private fun parsePcmWav(bytes: ByteArray): DecodedAudio {
        require(bytes.size >= WAV_HEADER_MIN_BYTES) { "Invalid WAV" }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "Invalid WAV RIFF header" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Invalid WAV WAVE header" }
        var offset = 12
        var sampleRate: Int? = null
        var channelCount: Int? = null
        var data: ByteArray? = null
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = littleEndianInt(bytes, offset + 4)
            val chunkDataOffset = offset + 8
            if (chunkDataOffset + chunkSize > bytes.size) break
            when (chunkId) {
                "fmt " -> {
                    val audioFormat = littleEndianShort(bytes, chunkDataOffset).toInt()
                    val channels = littleEndianShort(bytes, chunkDataOffset + 2).toInt()
                    val rate = littleEndianInt(bytes, chunkDataOffset + 4)
                    val bitsPerSample = littleEndianShort(bytes, chunkDataOffset + 14).toInt()
                    require(audioFormat == PCM_WAV_FORMAT) { "Unsupported WAV format" }
                    require(bitsPerSample == PCM_WAV_BITS_PER_SAMPLE) { "Unsupported WAV depth" }
                    sampleRate = rate
                    channelCount = channels
                }
                "data" -> {
                    data = bytes.copyOfRange(chunkDataOffset, chunkDataOffset + chunkSize)
                }
            }
            offset = chunkDataOffset + chunkSize + (chunkSize % 2)
        }
        return DecodedAudio(
            data = requireNotNull(data) { "Missing WAV data" },
            sampleRate = requireNotNull(sampleRate) { "Missing WAV format" },
            channelCount = requireNotNull(channelCount) { "Missing WAV channels" },
            durationSeconds = 0.0
        )
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Short {
        return ByteBuffer.wrap(bytes, offset, Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
    }

    private fun decodeToPcm(
        onProgress: ((Int) -> Unit)?,
        configureDataSource: (MediaExtractor) -> Unit
    ): DecodedAudio {
        val extractor = MediaExtractor()
        configureDataSource(extractor)
        var audioTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                format = trackFormat
                break
            }
        }
        if (audioTrackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalStateException("No audio track found")
        }
        extractor.selectTrack(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("Missing MIME")
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            -1L
        }
        val output = if (durationUs > 0) {
            val expectedBytes = (durationUs * sampleRate.toLong() * channels.toLong() * 2L) / 1_000_000L
            ByteArrayOutputStream(expectedBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        } else {
            ByteArrayOutputStream()
        }
        var expectedPcmBytes = if (durationUs > 0) {
            (durationUs * sampleRate.toLong() * channels.toLong() * 2L) / 1_000_000L
        } else {
            -1L
        }
        var outputBytesWritten = 0L
        var lastProgress = -1
        var chunkBuffer = ByteArray(8192)

        fun reportProgress(sampleTimeUs: Long) {
            val ratio = if (expectedPcmBytes > 0) {
                outputBytesWritten.toDouble() / expectedPcmBytes.toDouble()
            } else if (durationUs > 0) {
                sampleTimeUs.toDouble() / durationUs.toDouble()
            } else {
                return
            }
            val percent = (ratio * 100.0).toInt().coerceIn(0, 99)
            if (percent > lastProgress) {
                lastProgress = percent
                onProgress?.invoke(percent)
            }
        }

        onProgress?.invoke(0)
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex) ?: ByteBuffer.allocate(0)
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                            reportProgress(presentationTimeUs)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val outBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outBuffer != null && info.size > 0) {
                            if (info.size > chunkBuffer.size) {
                                var nextSize = chunkBuffer.size
                                while (nextSize < info.size) {
                                    nextSize *= 2
                                }
                                chunkBuffer = ByteArray(nextSize)
                            }
                            outBuffer.get(chunkBuffer, 0, info.size)
                            outBuffer.clear()
                            output.write(chunkBuffer, 0, info.size)
                            outputBytesWritten += info.size.toLong().coerceAtLeast(0L)
                            reportProgress(info.presentationTimeUs)
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = decoder.outputFormat
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (durationUs > 0) {
                            expectedPcmBytes = (durationUs * sampleRate.toLong() * channels.toLong() * 2L) / 1_000_000L
                        }
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            extractor.release()
            output.flush()
            output.close()
        }
        onProgress?.invoke(100)
        val data = output.toByteArray()
        val bytesPerFrame = channels * 2
        val totalFrames = if (bytesPerFrame > 0) data.size / bytesPerFrame else 0
        val durationSeconds = if (sampleRate > 0) {
            totalFrames.toDouble() / sampleRate.toDouble()
        } else {
            0.0
        }
        return DecodedAudio(data, sampleRate, channels, durationSeconds)
    }

    private data class DecodedAudio(
        val data: ByteArray,
        val sampleRate: Int,
        val channelCount: Int,
        val durationSeconds: Double
    )

    private external fun nativeCreatePlayer(sampleRate: Int, channelCount: Int): Long
    private external fun nativeLoadPcm(handle: Long, data: ByteArray)
    private external fun nativePlay(handle: Long)
    private external fun nativePause(handle: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeSeek(handle: Long, timeSeconds: Double)
    private external fun nativeScheduleJump(handle: Long, targetTime: Double, audioStart: Double): Boolean
    private external fun nativeCancelScheduledJump(handle: Long)
    private external fun nativeSetAnchorJump(handle: Long, targetTime: Double, audioStart: Double): Boolean
    private external fun nativeClearAnchorJump(handle: Long)
    private external fun nativeGetCurrentTime(handle: Long): Double
    private external fun nativeGetAudioTime(handle: Long): Double
    private external fun nativeIsPlaying(handle: Long): Boolean
    private external fun nativeHasAudio(handle: Long): Boolean
    private external fun nativeSetGain(handle: Long, gain: Float)
    private external fun nativeSetDucking(handle: Long, active: Boolean)
    private external fun nativeSetJukeboxAudioMode(handle: Long, mode: Int)
    private external fun nativeGetPlaybackRate(handle: Long): Double
    private external fun nativeCloneAudioFrom(handle: Long, sourceHandle: Long): Boolean
    private external fun nativeLoadCowbellSample(
        handle: Long,
        sampleIndex: Int,
        data: ByteArray,
        sampleRate: Int,
        channelCount: Int
    )
    private external fun nativeScheduleCowbellHit(
        handle: Long,
        sampleIndex: Int,
        targetTimeSeconds: Double,
        leftVolume: Float,
        rightVolume: Float
    )
    private external fun nativeCancelCowbellHits(handle: Long)
    private external fun nativeCancelPendingCowbellHits(handle: Long)
    private external fun nativeRelease(handle: Long)

    companion object {
        private const val WAV_HEADER_MIN_BYTES = 44
        private const val PCM_WAV_FORMAT = 1
        private const val PCM_WAV_BITS_PER_SAMPLE = 16

        init {
            System.loadLibrary("fj_oboe")
        }
    }
}
