package com.foreverjukebox.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.foreverjukebox.app.engine.JumpEvent
import com.foreverjukebox.app.engine.JukeboxPlayer
import com.foreverjukebox.app.ui.AudioModeIntensity
import com.foreverjukebox.app.ui.JukeboxAudioMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline instances never open an audio stream; they are pumped through
 * [renderOffline] to produce PCM for export while sharing the same native
 * DSP pipeline as live playback.
 */
class BufferedAudioPlayer(private val offline: Boolean = false) : JukeboxPlayer {
    private var sampleRate = 44100
    private var channelCount = 2
    // Guards the native handle across release and clone: cloneAudioFrom reads
    // another player's handle from a worker thread, so a concurrent
    // load/clear/release must not delete that native player mid-copy.
    private val nativeHandleLock = Any()
    private var nativeHandle: Long = 0
    private var durationSeconds: Double? = null
    private var jukeboxAudioMode = JukeboxAudioMode.Off
    private var jukeboxAudioModeIntensity = AudioModeIntensity.DEFAULT
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
                configureDataSource = { extractor -> extractor.setDataSource(file.absolutePath) },
                isAborted = { !isActive }
            )
        }
        sampleRate = decoded.sampleRate
        channelCount = decoded.channelCount
        durationSeconds = decoded.durationSeconds
        ensureNativePlayer()
        nativeLoadPcm(nativeHandle, decoded.data, decoded.dataLength)
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
                },
                isAborted = { !isActive }
            )
        }
        sampleRate = decoded.sampleRate
        channelCount = decoded.channelCount
        durationSeconds = decoded.durationSeconds
        ensureNativePlayer()
        nativeLoadPcm(nativeHandle, decoded.data, decoded.dataLength)
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

    fun setJukeboxAudioMode(
        mode: JukeboxAudioMode,
        intensity: Int = AudioModeIntensity.DEFAULT
    ) {
        jukeboxAudioMode = mode
        jukeboxAudioModeIntensity =
            if (mode.supportsIntensity) AudioModeIntensity.clamp(intensity)
            else AudioModeIntensity.DEFAULT
        if (nativeHandle == 0L) return
        nativeSetJukeboxAudioMode(nativeHandle, mode.nativeModeCode, jukeboxAudioModeIntensity)
    }

    fun getJukeboxAudioMode(): JukeboxAudioMode = jukeboxAudioMode

    override fun getPlaybackRate(): Double {
        if (nativeHandle == 0L) {
            return AudioModeIntensity.scaleRate(
                jukeboxAudioMode.playbackRate,
                jukeboxAudioModeIntensity
            )
        }
        return nativeGetPlaybackRate(nativeHandle)
    }

    fun cloneAudioFrom(other: BufferedAudioPlayer): Boolean {
        // Holding the source's handle lock keeps its native player alive for
        // the whole copy; a concurrent load/clear on the source blocks in
        // releaseNativePlayer until the clone completes.
        synchronized(other.nativeHandleLock) {
            if (!other.hasAudio()) return false
            sampleRate = other.sampleRate
            channelCount = other.channelCount
            durationSeconds = other.durationSeconds
            jukeboxAudioMode = other.jukeboxAudioMode
            jukeboxAudioModeIntensity = other.jukeboxAudioModeIntensity
            duckingActive = other.duckingActive
            releaseNativePlayer()
            ensureNativePlayer()
            return nativeCloneAudioFrom(nativeHandle, other.nativeHandle)
        }
    }

    override fun play() {
        if (offline) return
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

    override fun consumeJumpEvent(): JumpEvent? {
        if (nativeHandle == 0L) return null
        val event = DoubleArray(JUMP_EVENT_FIELD_COUNT)
        return if (nativeConsumeJumpEvent(nativeHandle, event)) {
            JumpEvent(sourceStartTime = event[0], targetTime = event[1])
        } else {
            null
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

    /** Reason the most recent [play] left the stream stopped, or null if it started. */
    fun describeLastStartFailure(): String? {
        if (nativeHandle == 0L) return null
        return nativeGetLastStartFailure(nativeHandle)?.takeIf { it.isNotBlank() }
    }

    fun hasAudio(): Boolean {
        return nativeHandle != 0L && nativeHasAudio(nativeHandle) && durationSeconds != null
    }

    fun getSampleRate(): Int = sampleRate

    fun getChannelCount(): Int = channelCount

    /**
     * Renders up to [frames] frames of interleaved 16-bit PCM into [buffer],
     * returning the frame count actually rendered. Offline instances only.
     */
    fun renderOffline(buffer: ShortArray, frames: Int): Int {
        check(offline) { "renderOffline requires an offline player" }
        if (nativeHandle == 0L) return 0
        return nativeRenderOffline(nativeHandle, buffer, frames)
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
        nativeHandle = if (offline) {
            nativeCreateOfflinePlayer(sampleRate, channelCount)
        } else {
            nativeCreatePlayer(sampleRate, channelCount)
        }
        if (nativeHandle != 0L) {
            nativeSetJukeboxAudioMode(
                nativeHandle,
                jukeboxAudioMode.nativeModeCode,
                jukeboxAudioModeIntensity
            )
            nativeSetDucking(nativeHandle, duckingActive)
        }
    }

    private fun releaseNativePlayer() {
        synchronized(nativeHandleLock) {
            if (nativeHandle == 0L) return
            nativeRelease(nativeHandle)
            nativeHandle = 0L
            cowbellSamplesLoaded = false
        }
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
        val resolvedData = requireNotNull(data) { "Missing WAV data" }
        return DecodedAudio(
            data = resolvedData,
            dataLength = resolvedData.size,
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
        configureDataSource: (MediaExtractor) -> Unit,
        isAborted: () -> Boolean = { false }
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
        // Pre-size to the duration estimate so the buffer rarely has to grow,
        // keeping a single PCM copy on the heap instead of the buffer + an
        // extra toByteArray() snapshot.
        val output = if (durationUs > 0) {
            val expectedBytes = (durationUs * sampleRate.toLong() * channels.toLong() * 2L) / 1_000_000L
            PcmBuffer(expectedBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        } else {
            PcmBuffer()
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
                // MediaCodec calls have no cancellation points of their own, so a decode whose
                // coroutine died would otherwise run to completion — burning CPU and contending
                // for the codec with whatever load replaced it. Bail between buffers instead.
                if (isAborted()) {
                    throw CancellationException("Audio decode abandoned")
                }
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
                            output.append(chunkBuffer, 0, info.size)
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
        }
        onProgress?.invoke(100)
        val totalBytes = output.size
        val bytesPerFrame = channels * 2
        val totalFrames = if (bytesPerFrame > 0) totalBytes / bytesPerFrame else 0
        val durationSeconds = if (sampleRate > 0) {
            totalFrames.toDouble() / sampleRate.toDouble()
        } else {
            0.0
        }
        return DecodedAudio(output.backingArray, totalBytes, sampleRate, channels, durationSeconds)
    }

    private data class DecodedAudio(
        val data: ByteArray,
        val dataLength: Int,
        val sampleRate: Int,
        val channelCount: Int,
        val durationSeconds: Double
    )

    // Growable PCM sink that exposes its backing array directly, so the decoded
    // audio is handed to native code without an intermediate full-size copy.
    // Pre-size to the expected byte count to avoid reallocation in the common
    // case where the track duration is known.
    private class PcmBuffer(initialCapacity: Int = DEFAULT_CAPACITY) {
        var backingArray: ByteArray = ByteArray(initialCapacity.coerceAtLeast(DEFAULT_CAPACITY))
            private set
        var size: Int = 0
            private set

        fun append(source: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            ensureCapacity(size + length)
            System.arraycopy(source, offset, backingArray, size, length)
            size += length
        }

        private fun ensureCapacity(required: Int) {
            if (required <= backingArray.size) return
            var newCapacity = backingArray.size
            while (newCapacity in 1 until required) {
                newCapacity = newCapacity shl 1
            }
            if (newCapacity < required) {
                newCapacity = required
            }
            backingArray = backingArray.copyOf(newCapacity)
        }

        private companion object {
            const val DEFAULT_CAPACITY = 64 * 1024
        }
    }

    private external fun nativeCreatePlayer(sampleRate: Int, channelCount: Int): Long
    private external fun nativeCreateOfflinePlayer(sampleRate: Int, channelCount: Int): Long
    private external fun nativeRenderOffline(handle: Long, buffer: ShortArray, frames: Int): Int
    private external fun nativeLoadPcm(handle: Long, data: ByteArray, length: Int)
    private external fun nativePlay(handle: Long)
    private external fun nativePause(handle: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeSeek(handle: Long, timeSeconds: Double)
    private external fun nativeScheduleJump(handle: Long, targetTime: Double, audioStart: Double): Boolean
    private external fun nativeCancelScheduledJump(handle: Long)
    private external fun nativeSetAnchorJump(handle: Long, targetTime: Double, audioStart: Double): Boolean
    private external fun nativeClearAnchorJump(handle: Long)
    private external fun nativeConsumeJumpEvent(handle: Long, event: DoubleArray): Boolean
    private external fun nativeGetCurrentTime(handle: Long): Double
    private external fun nativeGetAudioTime(handle: Long): Double
    private external fun nativeIsPlaying(handle: Long): Boolean
    private external fun nativeGetLastStartFailure(handle: Long): String?
    private external fun nativeHasAudio(handle: Long): Boolean
    private external fun nativeSetGain(handle: Long, gain: Float)
    private external fun nativeSetDucking(handle: Long, active: Boolean)
    private external fun nativeSetJukeboxAudioMode(handle: Long, mode: Int, intensity: Int)
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
        private const val JUMP_EVENT_FIELD_COUNT = 2
        private const val WAV_HEADER_MIN_BYTES = 44
        private const val PCM_WAV_FORMAT = 1
        private const val PCM_WAV_BITS_PER_SAMPLE = 16

        init {
            System.loadLibrary("fj_oboe")
        }
    }
}
