package com.foreverjukebox.app.ui

internal suspend fun loadRemoteExplicitJobInitialResponse(
    fetchJob: suspend () -> TrackAnalysisResult,
    retryJob: suspend () -> TrackAnalysisResult
): TrackAnalysisResult {
    val initialResponse = fetchJob()
    return if (initialResponse.status == "failed") {
        retryJob()
    } else {
        initialResponse
    }
}
