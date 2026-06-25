package com.foreverjukebox.app.ui

import kotlinx.coroutines.CoroutineScope

fun createRemoteSearchController(
    scope: CoroutineScope,
    serverGateway: ServerGateway,
    getState: () -> UiState,
    updateSearchState: ((SearchState) -> SearchState) -> Unit,
    setSearchQuery: (String) -> Unit,
    logError: (String, Throwable) -> Unit
): RemoteSearchController {
    return SearchCoordinator(
        scope = scope,
        serverGateway = serverGateway,
        getState = getState,
        updateSearchState = updateSearchState,
        setSearchQuery = setSearchQuery,
        logError = logError
    )
}
