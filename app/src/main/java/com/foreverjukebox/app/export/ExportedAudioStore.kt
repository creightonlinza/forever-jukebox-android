package com.foreverjukebox.app.export

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Publishes exported audio into the shared Music collection. Uses the
 * scoped-storage pending-entry flow, so no storage permissions are required;
 * export is gated to API 29+ where that flow exists.
 */
object ExportedAudioStore {

    /**
     * Filename convention shared with the web app's export:
     * `<sanitized base>_forever.m4a`, falling back to `jukebox`.
     */
    fun buildDisplayName(trackTitle: String?): String {
        val base = trackTitle.orEmpty()
            .replace(ILLEGAL_FILENAME_CHARS, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { DEFAULT_BASE_NAME }
        return "${base}_forever.m4a"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun insertPending(
        resolver: ContentResolver,
        displayName: String,
        title: String?,
        artist: String?
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            title?.takeIf { it.isNotBlank() }?.let { put(MediaStore.Audio.Media.TITLE, it) }
            artist?.takeIf { it.isNotBlank() }?.let { put(MediaStore.Audio.Media.ARTIST, it) }
        }
        return resolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun publish(resolver: ContentResolver, uri: Uri, source: File) {
        val output = resolver.openOutputStream(uri)
            ?: throw IllegalStateException("Unable to open exported file for writing")
        output.use { stream ->
            source.inputStream().use { input -> input.copyTo(stream) }
        }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
        }
        resolver.update(uri, values, null, null)
    }

    fun deletePending(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    private val ILLEGAL_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|]")
    private const val DEFAULT_BASE_NAME = "jukebox"
    private const val MIME_TYPE = "audio/mp4"
    private const val RELATIVE_PATH = "Music/Forever Jukebox"
}
