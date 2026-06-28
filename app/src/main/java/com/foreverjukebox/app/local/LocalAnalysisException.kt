package com.foreverjukebox.app.local

class UnsupportedAudioFormatException(message: String) : Exception(message)

/**
 * Raised when a track cannot be analyzed because holding it (and the
 * downstream resampled/analysis buffers) in memory would exceed the app's
 * heap budget. Used both as a preemptive guard before decoding and as the
 * domain wrapper for an [OutOfMemoryError] that escapes mid-pipeline.
 */
class AudioTooLargeException(message: String) : Exception(message)

class NativeLocalAnalysisNotReadyException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
