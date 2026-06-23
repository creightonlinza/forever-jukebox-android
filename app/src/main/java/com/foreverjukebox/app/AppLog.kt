package com.foreverjukebox.app

import android.util.Log
import io.sentry.Sentry

/**
 * Logging facade for recoverable, non-fatal failures: writes to logcat for local
 * visibility and emits a Sentry structured log for remote diagnostics. Fatal crashes
 * are captured automatically by the Sentry SDK and do not go through here.
 */
object AppLog {
    fun warn(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        Sentry.logger().warn("%s", format(tag, message, error))
    }

    fun error(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        Sentry.logger().error("%s", format(tag, message, error))
    }

    private fun format(tag: String, message: String, error: Throwable?): String =
        if (error == null) "[$tag] $message" else "[$tag] $message: $error"
}
