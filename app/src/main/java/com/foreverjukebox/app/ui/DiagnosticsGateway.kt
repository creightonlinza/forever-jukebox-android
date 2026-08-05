package com.foreverjukebox.app.ui

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Android-only lifecycle diagnostics. Distinct from [AnalyticsGateway], whose
 * event set is a GA4 web-parity contract — these events must never be added to
 * the web GA4 dictionary. They exist primarily as crash context: Firebase
 * Analytics events are automatically attached to Crashlytics reports as
 * breadcrumbs, and [setCrashKey] pins current app state onto any future report.
 */
interface DiagnosticsGateway {
    /** source: "file" | "cached" | "server" */
    fun logAnalysisStarted(source: String)

    fun logAnalysisCompleted(source: String)

    fun logAnalysisFailed(source: String, reason: String)

    /** Playlist selection/advance, which loads tracks without a select_track event. */
    fun logPlaylistTrackSelected(index: Int, trackId: String?, title: String?)

    /** mode: "jukebox" | "autocanonizer" */
    fun logCastConnected(mode: String)

    fun logCastDisconnected()

    fun setCrashKey(key: String, value: String)
}

fun createDiagnosticsGateway(context: Context): DiagnosticsGateway =
    FirebaseDiagnosticsGateway(context)

private class FirebaseDiagnosticsGateway(context: Context) : DiagnosticsGateway {
    // Both getInstance() calls throw if FirebaseApp isn't initialized (true on the
    // JVM unit-test classpath), so diagnostics degrade to no-ops there.
    private val firebase: FirebaseAnalytics? =
        runCatching { FirebaseAnalytics.getInstance(context.applicationContext) }.getOrNull()
    private val crashlytics: FirebaseCrashlytics? =
        runCatching { FirebaseCrashlytics.getInstance() }.getOrNull()

    override fun logAnalysisStarted(source: String) {
        log("analysis_started", "source" to source)
    }

    override fun logAnalysisCompleted(source: String) {
        log("analysis_completed", "source" to source)
    }

    override fun logAnalysisFailed(source: String, reason: String) {
        log("analysis_failed", "source" to source, "reason" to reason)
    }

    override fun logPlaylistTrackSelected(index: Int, trackId: String?, title: String?) {
        log(
            "playlist_track_selected",
            "index" to index.toString(),
            "track_id" to trackId,
            "track_title" to title
        )
    }

    override fun logCastConnected(mode: String) {
        log("cast_connected", "mode" to mode)
    }

    override fun logCastDisconnected() {
        log("cast_disconnected")
    }

    override fun setCrashKey(key: String, value: String) {
        crashlytics?.setCustomKey(key, value)
    }

    private fun log(event: String, vararg params: Pair<String, String?>) {
        val analytics = firebase ?: return
        val bundle = Bundle()
        for ((key, value) in params) {
            if (!value.isNullOrBlank()) {
                bundle.putString(key, value)
            }
        }
        analytics.logEvent(event, bundle)
    }
}
