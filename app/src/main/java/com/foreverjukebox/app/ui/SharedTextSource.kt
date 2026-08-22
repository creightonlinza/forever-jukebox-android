package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode

/**
 * Rules for tracks handed to the app through the Android share sheet. Sharing apps send free-form
 * text — YouTube sends "Video Title\nhttps://youtu.be/ID?si=TOKEN", others wrap the link in prose —
 * so the URL has to be lifted out before [normalizeSupportedSourceUrl] can validate it.
 */

/** Characters sharing apps leave attached to a URL embedded in a sentence. */
private const val URL_LEADING_TRIM = "([{<\"'"
private const val URL_TRAILING_TRIM = ".,;:!?)]}>\"'"

/** Upper bound on returned candidates; text carrying more links than this is not a track share. */
private const val MAX_SHARED_CANDIDATES = 5

private val SHARED_TEXT_TOKENS = Regex("\\s+")

/**
 * Ordered source candidates from a share intent, best first: the whole trimmed text, then each
 * http(s) token of the text, then each token of the subject. Callers validate each in turn.
 *
 * Only tokens carrying an explicit http(s) scheme are lifted out of prose. The bare YouTube id form
 * that [normalizeSupportedSourceUrl] accepts is therefore reachable only when the id is the entire
 * shared text, keeping an ordinary eleven-character word in a title from reading as a video id.
 * The subject is searched last so it can supply a link only when the body carries none.
 */
fun sharedSourceCandidates(sharedText: String?, sharedSubject: String? = null): List<String> {
    val candidates = LinkedHashSet<String>()
    val trimmedText = sharedText?.trim().orEmpty()
    if (trimmedText.isNotEmpty()) {
        candidates.add(trimmedText)
    }
    collectUrlTokens(sharedText, candidates)
    collectUrlTokens(sharedSubject, candidates)
    return candidates.take(MAX_SHARED_CANDIDATES)
}

/**
 * True when shared text carries a link, which is what decides who owns a share that arrives with
 * both text and a stream attachment. A link share attaches preview artwork; a file share captions
 * itself. Only an explicit http(s) token counts, so a caption naming the file reads as a caption.
 */
fun sharedTextCarriesLink(sharedText: String?, sharedSubject: String? = null): Boolean {
    val tokens = LinkedHashSet<String>()
    collectUrlTokens(sharedText, tokens)
    collectUrlTokens(sharedSubject, tokens)
    return tokens.isNotEmpty()
}

private fun collectUrlTokens(value: String?, into: MutableSet<String>) {
    if (value.isNullOrBlank()) return
    value.split(SHARED_TEXT_TOKENS)
        .asSequence()
        .map { token ->
            token.trimStart { it in URL_LEADING_TRIM }.trimEnd { it in URL_TRAILING_TRIM }
        }
        .filter {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true)
        }
        .forEach { into.add(it) }
}

/** Outcome of the readiness check a shared track has to pass before it can be acted on. */
enum class ShareReadiness {
    /** Startup state is still settling, or the mode gate is on screen. Hold the share. */
    Wait,
    NotServerMode,
    NoServer,
    Ready
}

/**
 * Whether a pending share can be acted on yet. A share can arrive before the preference flows have
 * emitted, when an empty base URL and an absent server config are indistinguishable from a user who
 * never configured either — hence the explicit settled flags rather than value checks alone.
 */
fun resolveShareReadiness(
    showAppModeGate: Boolean,
    appMode: AppMode?,
    baseUrlLoaded: Boolean,
    baseUrl: String,
    serverConfigPending: Boolean
): ShareReadiness = when {
    // Covers both the unread preference and the gate dialog: answering it re-runs this check.
    showAppModeGate -> ShareReadiness.Wait
    appMode != AppMode.Server -> ShareReadiness.NotServerMode
    !baseUrlLoaded -> ShareReadiness.Wait
    baseUrl.isBlank() -> ShareReadiness.NoServer
    serverConfigPending -> ShareReadiness.Wait
    else -> ShareReadiness.Ready
}
