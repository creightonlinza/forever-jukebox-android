package com.foreverjukebox.app.ui

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

fun createAnalyticsGateway(context: Context): AnalyticsGateway = FullAnalyticsGateway(context)

private class FullAnalyticsGateway(context: Context) : AnalyticsGateway {
    private val firebase = FirebaseAnalytics.getInstance(context.applicationContext)

    override fun logPlay(mode: String, trackId: String, trackTitle: String?) {
        log("play", "mode" to mode, "track_id" to trackId, "track_title" to trackTitle)
    }

    override fun logSearch(searchTerm: String) {
        log("search", "search_term" to searchTerm)
    }

    override fun logSelectTrack(source: String, trackId: String?, trackTitle: String?) {
        log("select_track", "source" to source, "track_id" to trackId, "track_title" to trackTitle)
    }

    override fun logFavorite(trackId: String, trackTitle: String?) {
        log("favorite", "track_id" to trackId, "track_title" to trackTitle)
    }

    override fun logShare(trackId: String) {
        log("share", "track_id" to trackId)
    }

    override fun logUpload(method: String) {
        log("upload", "method" to method)
    }

    private fun log(event: String, vararg params: Pair<String, String?>) {
        val bundle = Bundle()
        for ((key, value) in params) {
            if (!value.isNullOrBlank()) {
                bundle.putString(key, value)
            }
        }
        firebase.logEvent(event, bundle)
    }
}
