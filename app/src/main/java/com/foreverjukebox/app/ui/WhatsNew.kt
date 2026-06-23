package com.foreverjukebox.app.ui

private val currentWhatsNewBullets = listOf(
    "Added minimum branch length Tuning slider",
    "Can now display YouTube thumbnail previews (long press on a YT search result)",
    "New Audio Modes: 8-bit, Underwater, Cathedral & More Cowbell",
    "Favorites search and sorting added, with maximum saved favorites bumped to 150 tracks",
    "Misc fixes and under the hood improvements",
    "Please report any bugs to GitHub/Reddit/Discord"
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
