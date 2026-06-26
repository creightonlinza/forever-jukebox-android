package com.foreverjukebox.app.ui

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
