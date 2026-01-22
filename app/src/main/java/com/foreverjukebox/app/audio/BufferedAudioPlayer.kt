package com.foreverjukebox.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import com.foreverjukebox.app.engine.JukeboxPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class BufferedAudioPlayer(private val context: Context) : JukeboxPlayer {
    private var sourceFile: File? = null
    private var audioTrack: AudioTrack? = null
    private var pcmData: ByteArray? = null
    private var sampleRate = 44100
    private var channelCount = 2
    private var bytesPerFrame = 4
    private var baseFrame = 0
    private var baseOffsetSeconds = 0.0
    private val jumpThread = HandlerThread("fj-audio-jump").apply { start() }
    private val jumpHandler = Handler(jumpThread.looper)
    private var pendingJumpToken = 0

    suspend fun loadFile(
        file: File,
        onProgress: ((Int) -> Unit)? = null
    ) {
        pcmData = null
        releaseAudioTrack()
        cancelScheduledJump()
        withContext(Dispatchers.IO) {
            sourceFile = file
        }
        val decoded = decodeToPcm(file, onProgress)
        pcmData = decoded.data
        sampleRate = decoded.sampleRate
        channelCount = decoded.channelCount
        bytesPerFrame = 2 * channelCount
        audioTrack = createAudioTrack(decoded)
        baseFrame = 0
        baseOffsetSeconds = 0.0
    }

    fun release() {
        cancelScheduledJump()
        releaseAudioTrack()
        jumpThread.quitSafely()
    }

    fun clear() {
        cancelScheduledJump()
        releaseAudioTrack()
        pcmData = null
        sourceFile = null
        baseFrame = 0
        baseOffsetSeconds = 0.0
    }

    override fun play() {
        audioTrack?.play()
    }

    override fun pause() {
        audioTrack?.pause()
    }

    override fun stop() {
        val track = audioTrack ?: return
        cancelScheduledJump()
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            track.pause()
        }
        try {
            track.stop()
        } catch (_: IllegalStateException) {
            // Ignore if already stopped or not initialized.
        }
        track.flush()
        baseFrame = 0
        baseOffsetSeconds = 0.0
    }

    override fun seek(time: Double) {
        val track = audioTrack ?: return
        val frame = (time * sampleRate).toInt().coerceAtLeast(0)
        cancelScheduledJump()
        val wasPlaying = track.playState == AudioTrack.PLAYSTATE_PLAYING
        if (wasPlaying) {
            track.pause()
        }
        try {
            track.flush()
        } catch (_: IllegalStateException) {
            // Ignore if the track is already stopped.
        }
        track.playbackHeadPosition = frame
        baseFrame = track.playbackHeadPosition
        baseOffsetSeconds = frame.toDouble() / sampleRate.toDouble()
        if (wasPlaying) {
            track.play()
        }
    }

    override fun scheduleJump(targetTime: Double, transitionTime: Double) {
        val track = audioTrack ?: return
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            return
        }
        val currentTime = getCurrentTime()
        val delayMs = ((transitionTime - currentTime).coerceAtLeast(0.0) * 1000.0).toLong()
        val token = ++pendingJumpToken
        jumpHandler.postDelayed({
            if (token != pendingJumpToken) return@postDelayed
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                seek(targetTime)
            }
        }, delayMs)
    }

    override fun getCurrentTime(): Double {
        val position = audioTrack?.playbackHeadPosition ?: 0
        val deltaFrames = (position - baseFrame).coerceAtLeast(0)
        return baseOffsetSeconds + deltaFrames.toDouble() / sampleRate.toDouble()
    }

    override fun isPlaying(): Boolean {
        return audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
    }

    fun getDurationSeconds(): Double? {
        val data = pcmData ?: return null
        val totalFrames = data.size / bytesPerFrame
        return totalFrames.toDouble() / sampleRate.toDouble()
    }

    private fun releaseAudioTrack() {
        audioTrack?.release()
        audioTrack = null
    }

    private fun cancelScheduledJump() {
        pendingJumpToken += 1
        jumpHandler.removeCallbacksAndMessages(null)
    }

    private fun createAudioTrack(decoded: DecodedAudio): AudioTrack {
        val channelConfig = when (decoded.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> AudioFormat.CHANNEL_OUT_DEFAULT
        }
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(decoded.sampleRate)
            .setChannelMask(channelConfig)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(audioFormat)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(decoded.data.size)
            .build()
        track.write(decoded.data, 0, decoded.data.size)
        track.playbackHeadPosition = 0
        return track
    }

    private fun decodeToPcm(
        file: File,
        onProgress: ((Int) -> Unit)?
    ): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
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
            decoder.stop()
            decoder.release()
            extractor.release()
            output.flush()
            output.close()
        }
        onProgress?.invoke(100)
        return DecodedAudio(output.toByteArray(), sampleRate, channels)
    }

    private data class DecodedAudio(
        val data: ByteArray,
        val sampleRate: Int,
        val channelCount: Int
    )
}
