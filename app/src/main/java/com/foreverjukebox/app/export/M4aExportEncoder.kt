package com.foreverjukebox.app.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Streams interleaved 16-bit PCM into the platform AAC-LC encoder, muxed into
 * an MPEG-4 container at [outputFile]. Synchronous MediaCodec usage: callers
 * push PCM with [writePcm], then [finish] drains to end-of-stream, then
 * [release] frees the codec and muxer.
 */
class M4aExportEncoder(
    private val sampleRate: Int,
    private val channelCount: Int,
    outputFile: File
) {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var totalFramesFed = 0L
    private var released = false

    init {
        require(sampleRate in 1..MAX_AAC_SAMPLE_RATE) { "Unsupported sample rate $sampleRate" }
        require(channelCount in 1..MAX_AAC_CHANNELS) { "Unsupported channel count $channelCount" }
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(
                MediaFormat.KEY_BIT_RATE,
                if (channelCount >= 2) STEREO_BIT_RATE else MONO_BIT_RATE
            )
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }
        val newCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var newMuxer: MediaMuxer? = null
        var started = false
        try {
            newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            newMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            newCodec.start()
            started = true
        } finally {
            if (!started) {
                runCatching { newCodec.release() }
                newMuxer?.let { runCatching { it.release() } }
            }
        }
        codec = newCodec
        muxer = checkNotNull(newMuxer)
    }

    /** Feeds [frames] frames of interleaved PCM from [buffer] into the encoder. */
    fun writePcm(buffer: ShortArray, frames: Int) {
        check(!released) { "Encoder released" }
        var offsetShorts = 0
        var remainingShorts = frames * channelCount
        while (remainingShorts > 0) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val input = codec.getInputBuffer(inputIndex)
                if (input == null) {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                    continue
                }
                input.clear()
                val capacityShorts = input.remaining() / Short.SIZE_BYTES
                val toWrite = min(remainingShorts, capacityShorts)
                input.order(ByteOrder.nativeOrder())
                    .asShortBuffer()
                    .put(buffer, offsetShorts, toWrite)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    toWrite * Short.SIZE_BYTES,
                    presentationTimeUs(),
                    0
                )
                totalFramesFed += (toWrite / channelCount).toLong()
                offsetShorts += toWrite
                remainingShorts -= toWrite
            }
            drainOutput(waitForEndOfStream = false)
        }
    }

    /** Signals end-of-stream, drains the remaining output, and finalizes the container. */
    fun finish() {
        check(!released) { "Encoder released" }
        while (true) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    presentationTimeUs(),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                break
            }
            drainOutput(waitForEndOfStream = false)
        }
        drainOutput(waitForEndOfStream = true)
        check(muxerStarted) { "Encoder produced no output" }
        muxer.stop()
    }

    /** Idempotent; safe to call after a failure part-way through encoding. */
    fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer.release() }
    }

    private fun presentationTimeUs(): Long = totalFramesFed * 1_000_000L / sampleRate

    private fun drainOutput(waitForEndOfStream: Boolean) {
        while (true) {
            val timeout = if (waitForEndOfStream) TIMEOUT_US else 0L
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeout)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!waitForEndOfStream) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Encoder format changed after muxer start" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val output = codec.getOutputBuffer(outputIndex)
                    val isCodecConfig =
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (output != null && bufferInfo.size > 0 && !isCodecConfig) {
                        check(muxerStarted) { "Encoder output before format change" }
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, output, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    companion object {
        const val MAX_AAC_SAMPLE_RATE = 96_000
        const val MAX_AAC_CHANNELS = 2
        private const val STEREO_BIT_RATE = 192_000
        private const val MONO_BIT_RATE = 128_000
        private const val MAX_INPUT_BYTES = 64 * 1024
        private const val TIMEOUT_US = 10_000L
    }
}
