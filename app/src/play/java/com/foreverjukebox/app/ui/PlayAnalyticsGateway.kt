package com.foreverjukebox.app.ui

import android.content.Context

// The play flavor is built without Firebase, so analytics events are dropped.
@Suppress("UNUSED_PARAMETER")
fun createAnalyticsGateway(context: Context): AnalyticsGateway = PlayAnalyticsGateway

private object PlayAnalyticsGateway : AnalyticsGateway {
    override fun logPlay(mode: String, trackId: String, trackTitle: String?) = Unit
    override fun logSearch(searchTerm: String) = Unit
    override fun logSelectTrack(source: String, trackId: String?, trackTitle: String?) = Unit
    override fun logFavorite(trackId: String, trackTitle: String?) = Unit
    override fun logShare(trackId: String) = Unit
    override fun logUpload(method: String) = Unit
}
