package com.foreverjukebox.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

enum class FavoriteToggleResult {
    Added,
    Removed,
    LimitReached,
    BlockedInFlight,
    NoTrack
}

internal fun shouldPulseListenTab(state: UiState): Boolean {
    return state.playback.isRunning && state.activeTab != TabId.Play
}

internal fun pulsingListenContainerColor(
    titleAccent: Color,
    onBackground: Color,
    pulseAmount: Float
): Color {
    val normalized = pulseAmount.coerceIn(0f, 1f)
    val midBackground = onBackground.copy(alpha = 0.12f)
    return lerp(titleAccent, midBackground, normalized)
}

internal fun pulsingListenContentColor(onBackground: Color, pulseAmount: Float): Color {
    val normalized = pulseAmount.coerceIn(0f, 1f)
    val inverse = Color(
        red = 1f - onBackground.red,
        green = 1f - onBackground.green,
        blue = 1f - onBackground.blue,
        alpha = onBackground.alpha
    )
    return lerp(inverse, onBackground, normalized)
}

internal fun hasRealFavoritesSyncPath(state: UiState): Boolean {
    return state.allowFavoritesSync && !state.favoritesSyncCode.isNullOrBlank()
}

internal fun shouldShowListenFavoriteSpinner(state: UiState): Boolean {
    return hasRealFavoritesSyncPath(state) && state.listenFavoriteToggleInFlight
}

internal fun shouldBlockListenFavoriteToggle(state: UiState): Boolean {
    return shouldShowListenFavoriteSpinner(state)
}

internal fun playbackTransportContentDescription(playback: PlaybackState): String {
    return when {
        playback.isRunning -> "Pause"
        playback.isPaused -> "Resume"
        else -> "Play"
    }
}
