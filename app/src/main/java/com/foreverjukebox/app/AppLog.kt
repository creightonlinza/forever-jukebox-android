package com.foreverjukebox.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * File-backed logger for non-fatal diagnostics that should survive past a logcat
 * session — e.g. the cause behind a user-visible "Loading failed." state.
 *
 * Every call also forwards to [android.util.Log], so logcat behaviour is
 * unchanged. Writes are serialized on a single background thread and never throw.
 * The log is size-capped with one rotated generation, so it cannot grow without
 * bound. This is distinct from [CrashLogger], which writes one synchronous file
 * per fatal crash; here we want a rolling append log for frequent, recoverable
 * events.
 *
 * Intended to back a future "submit logs" action — [logFiles] exposes the files.
 */
object AppLog {
    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "app.log"
    private const val ROTATED_FILE_NAME = "app.log.1"
    private const val MAX_LOG_BYTES = 512 * 1024L

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AppLog").apply { isDaemon = true }
    }
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Volatile
    private var logDirectory: File? = null

    fun init(context: Context) {
        if (logDirectory != null) return
        logDirectory = File(context.applicationContext.filesDir, LOG_DIR_NAME).also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    /** Current log files (newest content in [LOG_FILE_NAME]), for retrieval/submission. */
    fun logFiles(): List<File> {
        val dir = logDirectory ?: return emptyList()
        return listOf(File(dir, LOG_FILE_NAME), File(dir, ROTATED_FILE_NAME)).filter { it.exists() }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        write("E", tag, message, throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
        write("W", tag, message, throwable)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        write("I", tag, message, null)
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val dir = logDirectory ?: return
        // Capture caller-thread context now; format the (potentially large) stack
        // trace on the writer thread to keep the call site cheap.
        val timestamp = timestampFormat.get()!!.format(Date())
        val threadName = Thread.currentThread().name
        writer.execute {
            try {
                val file = File(dir, LOG_FILE_NAME)
                rotateIfNeeded(dir, file)
                val entry = buildString {
                    append(timestamp).append("  ").append(level).append('/').append(tag)
                    append(" [").append(threadName).append("]  ").append(message).append('\n')
                    if (throwable != null) {
                        val stack = StringWriter()
                        throwable.printStackTrace(PrintWriter(stack))
                        append(stack.toString())
                    }
                }
                file.appendText(entry)
            } catch (_: Throwable) {
                // Logging must never crash the app.
            }
        }
    }

    private fun rotateIfNeeded(dir: File, file: File) {
        if (file.length() < MAX_LOG_BYTES) {
            return
        }
        val rotated = File(dir, ROTATED_FILE_NAME)
        if (rotated.exists()) {
            rotated.delete()
        }
        file.renameTo(rotated)
    }
}
