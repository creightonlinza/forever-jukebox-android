package com.foreverjukebox.app.local

class UnsupportedAudioFormatException(message: String) : Exception(message)

class NativeLocalAnalysisNotReadyException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
