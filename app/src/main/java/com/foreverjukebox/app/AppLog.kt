package com.foreverjukebox.app

import android.media.MediaCodec
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Logging facade for recoverable, non-fatal failures: writes to logcat for local
 * visibility and reports to Crashlytics for remote diagnostics. [warn] leaves a
 * breadcrumb in the Crashlytics log buffer (attached to any later crash/non-fatal
 * report); [error] additionally records a non-fatal exception. Fatal crashes are
 * captured automatically by the Crashlytics SDK and do not go through here.
 */
object AppLog {
    private const val MAX_CAUSE_CHAIN_DEPTH = 5

    @Volatile
    private var cachedCrashlytics: FirebaseCrashlytics? = null

    // FirebaseCrashlytics.getInstance() throws if FirebaseApp isn't initialized,
    // which is the case on the JVM unit-test classpath. Retry-on-null rather than
    // `lazy` so an improbable pre-init call can't permanently disable remote
    // logging; runCatching also absorbs classloading failures on the test JVM.
    private fun crashlytics(): FirebaseCrashlytics? =
        cachedCrashlytics ?: runCatching { FirebaseCrashlytics.getInstance() }
            .getOrNull()?.also { cachedCrashlytics = it }

    fun warn(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        crashlytics()?.log("W ${format(tag, message, error)}")
    }

    fun error(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        val remote = crashlytics() ?: return
        remote.log("E ${format(tag, message, error)}")
        remote.recordException(error ?: AppLogError(format(tag, message, null)))
    }

    // Wrapper so message-only error() calls still produce a Crashlytics non-fatal.
    // These all share a construction stack inside AppLog, so they group into one
    // Crashlytics issue distinguished by message — acceptable because nearly all
    // error() call sites pass a real Throwable.
    private class AppLogError(message: String) : Exception(message)

    private fun format(tag: String, message: String, error: Throwable?): String =
        if (error == null) "[$tag] $message" else "[$tag] $message: ${formatErrorChain(error)}"

    // The Crashlytics breadcrumb only gets the formatted string (logcat gets the
    // full stack trace via Log.w/e), so the cause chain must be flattened here or
    // it is lost to remote diagnostics entirely.
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
