package com.foreverjukebox.app.ui

private val currentWhatsNewBullets = listOf(
    "A new Playlists feature was added to queue up multiple tracks.",
    "First load a track, then long-press another track to add it to the playlist. A short tap continues to swap the track in and start loading it.",
    "Tap the playlist icon on the Listen screen to pick, remove, or clear tracks. Previous and next controls skip through the playlist, and playlists are automatically saved.",
    "Added a 'Play when ready' toggle on loading screen to automatically start the track once finished loading.",
    "Implemented audio ducking for notifications."
)

internal fun normalizedDisplayVersion(versionName: String): String {
    return versionName
        .removePrefix("v")
        .removePrefix("V")
        .ifBlank { "Current Version" }
}

internal fun buildWhatsNewPrompt(
    versionCode: Int,
    versionName: String,
    bullets: List<String> = currentWhatsNewBullets
): WhatsNewPrompt {
    val normalizedVersionName = normalizedDisplayVersion(versionName)
    val cleanedBullets = bullets
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { currentWhatsNewBullets }
    return WhatsNewPrompt(
        versionCode = versionCode,
        title = "What's New in v$normalizedVersionName",
        bullets = cleanedBullets
    )
}

internal fun shouldShowAutomaticWhatsNew(
    showAppModeGate: Boolean,
    whatsNewVersionCodeLoaded: Boolean,
    lastShownVersionCode: Int?,
    currentVersionCode: Int,
    currentPrompt: WhatsNewPrompt?
): Boolean {
    if (showAppModeGate) return false
    if (!whatsNewVersionCodeLoaded) return false
    if (currentPrompt != null) return false
    return lastShownVersionCode == null || currentVersionCode > lastShownVersionCode
}

internal fun stateAfterWhatsNewDismissed(
    state: UiState,
    dismissedVersionCode: Int
): UiState {
    return state.copy(
        lastShownWhatsNewVersionCode = dismissedVersionCode,
        whatsNewPrompt = null
    )
}
