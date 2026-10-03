package com.foreverjukebox.app.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Decodes the audio track of whatever [configureDataSource] points the extractor at into
 * interleaved 16-bit PCM. [isAborted] is polled between codec buffers so an abandoned decode
 * stops early. Blocks for the whole decode, so call it off the main thread.
 */
internal fun decodePcm(
    onProgress: ((Int) -> Unit)?,
    configureDataSource: (MediaExtractor) -> Unit,
    isAborted: () -> Boolean = { false }
): DecodedPcm {
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
    return DecodedPcm(output.backingArray, totalBytes, sampleRate, channels, durationSeconds)
}

data class DecodedPcm(
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
internal class PcmBuffer(initialCapacity: Int = DEFAULT_CAPACITY) {
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
