package com.foreverjukebox.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists uncaught throwables from any thread to a file before the process dies,
 * so crashes can be inspected after the fact without a logcat session attached.
 *
 * The handler chains to the previously installed default handler, so the OS still
 * performs its normal crash flow (process termination, ANR/crash dialog). It never
 * throws — if writing the log fails it falls through to the default handler.
 */
object CrashLogger {
    private const val CRASH_DIR_NAME = "crash-logs"
    private const val FILE_PREFIX = "crash-"
    private const val FILE_SUFFIX = ".txt"
    private const val MAX_LOG_FILES = 20

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(appContext, thread, throwable)
            } catch (_: Throwable) {
                // A crash handler must never throw; let the default handler run.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Directory holding the persisted crash logs. */
    fun crashLogDir(context: Context): File {
        val dir = File(context.filesDir, CRASH_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /** Crash log files, newest first. */
    fun crashLogs(context: Context): List<File> {
        val files = crashLogDir(context)
            .listFiles { file -> file.isFile && file.name.startsWith(FILE_PREFIX) }
            ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashLogDir(context)
        val now = Date()
        val fileTimestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(now)
        val file = File(dir, "$FILE_PREFIX$fileTimestamp$FILE_SUFFIX")

        val stackTrace = StringWriter().also { writer ->
            throwable.printStackTrace(PrintWriter(writer))
        }.toString()

        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"

        val header = buildString {
            appendLine("Time:        ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(now)}")
            appendLine("App version: $versionName")
            appendLine("Android:     ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device:      ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread:      ${thread.name} (id=${thread.id})")
            appendLine("Throwable:   ${throwable.javaClass.name}: ${throwable.message}")
            appendLine("----- stack trace -----")
        }

        file.writeText(header + stackTrace)
        pruneOldLogs(dir)
    }

    private fun pruneOldLogs(dir: File) {
        val files = dir.listFiles { file -> file.isFile && file.name.startsWith(FILE_PREFIX) }
            ?: return
        if (files.size <= MAX_LOG_FILES) {
            return
        }
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_LOG_FILES)
            .forEach { runCatching { it.delete() } }
    }
}
