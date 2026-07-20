package com.foreverjukebox.app

import android.media.MediaCodec
import android.util.Log
import io.sentry.Sentry

/**
 * Logging facade for recoverable, non-fatal failures: writes to logcat for local
 * visibility and emits a Sentry structured log for remote diagnostics. Fatal crashes
 * are captured automatically by the Sentry SDK and do not go through here.
 */
object AppLog {
    private const val MAX_CAUSE_CHAIN_DEPTH = 5

    fun warn(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        Sentry.logger().warn("%s", format(tag, message, error))
    }

    fun error(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        Sentry.logger().error("%s", format(tag, message, error))
    }

    private fun format(tag: String, message: String, error: Throwable?): String =
        if (error == null) "[$tag] $message" else "[$tag] $message: ${formatErrorChain(error)}"

    // The Sentry log line only gets the formatted string (logcat gets the full stack
    // trace via Log.w/e), so the cause chain must be flattened here or it is lost to
    // remote diagnostics entirely.
    internal fun formatErrorChain(error: Throwable): String = buildString {
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null) {
            if (!seen.add(current)) break
            if (seen.size > MAX_CAUSE_CHAIN_DEPTH) {
                append(" <- ...")
                break
            }
            if (seen.size > 1) append(" <- ")
            append(current)
            codecDiagnostics(current)?.let { append(" [").append(it).append("]") }
            current = current.cause
        }
    }

    // CodecException.toString() omits diagnosticInfo and the transient/recoverable
    // flags, which are the fields that distinguish decoder resource pressure from a
    // genuinely bad bitstream.
    private fun codecDiagnostics(error: Throwable): String? {
        if (error !is MediaCodec.CodecException) return null
        return runCatching {
            "diagnosticInfo=${error.diagnosticInfo}, " +
                "transient=${error.isTransient}, " +
                "recoverable=${error.isRecoverable}"
        }.getOrNull()
    }
}
