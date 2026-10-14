package com.foreverjukebox.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Logs, once per process, why this app's previous process ended. The activity and the
 * playback service both call it, so the breadcrumb leads the crash-report log whether the
 * process started for the UI or for playback.
 */
object ProcessExitDiagnostics {
    private const val TAG = "ProcessExit"
    private val logged = AtomicBoolean(false)

    fun logPreviousExitOnce(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!logged.compareAndSet(false, true)) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val exit = runCatching {
            manager.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
        }.getOrNull()
        if (exit == null) {
            AppLog.info(TAG, "Previous process exit: none recorded")
            return
        }
        val agoMs = System.currentTimeMillis() - exit.timestamp
        val description = exit.description?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        AppLog.info(
            TAG,
            "Previous process exit: ${exitReasonName(exit.reason)}$description " +
                "importance=${exit.importance} ${agoMs}ms ago"
        )
    }
}

internal fun exitReasonName(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
    ApplicationExitInfo.REASON_SIGNALED -> "signaled"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
    ApplicationExitInfo.REASON_CRASH -> "crash"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
    ApplicationExitInfo.REASON_ANR -> "anr"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
    ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
    ApplicationExitInfo.REASON_OTHER -> "other"
    ApplicationExitInfo.REASON_FREEZER -> "freezer"
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_state_change"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated"
    else -> "unknown($reason)"
}
