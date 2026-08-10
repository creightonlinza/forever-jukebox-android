package com.foreverjukebox.app.ui

import com.foreverjukebox.app.engine.DEFAULT_MIN_LONG_BRANCH_PERCENT
import com.foreverjukebox.app.engine.MAX_THRESHOLD
import com.foreverjukebox.app.engine.MIN_THRESHOLD
import com.foreverjukebox.app.engine.parsePinnedThreshold
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal val ALLOWED_BRANCH_LENGTHS = setOf(5, 10, 20, 30)

data class ParsedTuningParams(
    val threshold: Int?,
    val minProbPercent: Int?,
    val maxProbPercent: Int?,
    val rampPercent: Int?,
    val highlightAnchorBranch: Boolean?,
    val justBackwards: Boolean?,
    val minJumpDistancePercent: Int?,
    val removeSequentialBranches: Boolean?,
    val deletedEdgeIds: List<Int>,
    val anchorBranchId: Int?,
    val audioMode: JukeboxAudioMode?,
    val audioModeIntensity: Int = AudioModeIntensity.DEFAULT
)

object TuningParamsCodec {
    private val knownKeys = setOf("jb", "bl", "lg", "sq", "thresh", "bp", "d", "ab", "ah", "am", "ai")

    fun parse(raw: String?): ParsedTuningParams? {
        if (raw.isNullOrBlank()) {
            return null
        }
        val params = parseQuery(raw)
        if (params.keys.none { it in knownKeys }) {
            return null
        }
        val audioMode = JukeboxAudioMode.fromWireValue(params.firstValue("am"))
        if (params.keys.all { it == "am" || it == "ai" } && audioMode == null) {
            return null
        }
        val audioModeIntensity = AudioModeIntensity.parse(params.firstValue("ai"), audioMode)
        // Null is auto, so a written threshold below the control range reads the same as none at all.
        val threshold = parsePinnedThreshold(params.firstValue("thresh"))

        var minProbPercent: Int? = null
        var maxProbPercent: Int? = null
        var rampPercent: Int? = null
        params.firstValue("bp")?.split(",")?.let { parts ->
            if (parts.size == 3) {
                minProbPercent = parts[0].toIntOrNull()?.coerceIn(0, 100)
                maxProbPercent = parts[1].toIntOrNull()?.coerceIn(0, 100)
                rampPercent = parts[2].toIntOrNull()?.coerceIn(0, 100)
            }
        }

        val deletedEdgeIds = params.firstValue("d")
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull()?.takeIf { id -> id >= 0 } }
            ?: emptyList()
        val anchorBranchId = params.firstValue("ab")
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
        val branchLength = params.firstValue("bl")
            ?.toIntOrNull()
            ?.takeIf { it == 0 || it in ALLOWED_BRANCH_LENGTHS }
        val legacyLongBranches = parseStandardBoolean(params.firstValue("lg"))
        val minJumpDistancePercent = when {
            branchLength != null -> branchLength
            legacyLongBranches == true -> DEFAULT_MIN_LONG_BRANCH_PERCENT
            legacyLongBranches == false -> 0
            else -> null
        }

        return ParsedTuningParams(
            threshold = threshold,
            minProbPercent = minProbPercent,
            maxProbPercent = maxProbPercent,
            rampPercent = rampPercent,
            highlightAnchorBranch = parseStandardBoolean(params.firstValue("ah")),
            justBackwards = parseStandardBoolean(params.firstValue("jb")),
            minJumpDistancePercent = minJumpDistancePercent,
            removeSequentialBranches = parseRemoveSequential(params.firstValue("sq")),
            deletedEdgeIds = deletedEdgeIds,
            anchorBranchId = anchorBranchId,
            audioMode = audioMode,
            audioModeIntensity = audioModeIntensity
        )
    }

    fun buildCastLoadPayload(
        raw: String?,
        highlightAnchorBranch: Boolean
    ): String? {
        val params = if (raw.isNullOrBlank()) {
            linkedMapOf()
        } else {
            parseQuery(raw)
        }
        val sanitized = linkedMapOf<String, String>()
        val hasHighlightParam = params.containsKey("ah")
        val branchLength = params.firstValue("bl")
            ?.toIntOrNull()
            ?.takeIf { it == 0 || it in ALLOWED_BRANCH_LENGTHS }
        val legacyLongBranches = parseStandardBoolean(params.firstValue("lg"))
        val resolvedBranchLength = when {
            branchLength != null -> branchLength
            legacyLongBranches == true -> DEFAULT_MIN_LONG_BRANCH_PERCENT
            legacyLongBranches == false -> 0
            else -> null
        }
        var branchLengthHandled = false
        for ((name, values) in params) {
            val value = values.firstOrNull() ?: continue
            when (name) {
                "jb" -> parseStandardBoolean(value)?.let {
                    sanitized[name] = if (it) "1" else "0"
                }
                "bl", "lg" -> {
                    if (!branchLengthHandled) {
                        resolvedBranchLength
                            ?.let { sanitized["bl"] = it.toString() }
                        branchLengthHandled = true
                    }
                }
                "ah" -> if (parseStandardBoolean(value) != null) {
                    sanitized[name] = if (highlightAnchorBranch) "1" else "0"
                }
                "sq" -> parseRemoveSequential(value)?.let {
                    sanitized[name] = if (it) "0" else "1"
                }
                "thresh" -> parsePinnedThreshold(value)
                    ?.let { sanitized[name] = it.toString() }
                "bp" -> sanitizeTriplet(value)?.let {
                    sanitized[name] = it
                }
                "d" -> sanitizeIdList(value)?.let {
                    sanitized[name] = it
                }
                "ab" -> value.toIntOrNull()
                    ?.takeIf { it >= 0 }
                    ?.let { sanitized[name] = it.toString() }
                "am" -> JukeboxAudioMode.fromWireValue(value)?.let {
                    sanitized[name] = it.wireValue
                }
                "ai" -> {
                    val payloadAudioMode = JukeboxAudioMode.fromWireValue(params.firstValue("am"))
                    val intensity = AudioModeIntensity.parse(value, payloadAudioMode)
                    if (intensity != AudioModeIntensity.DEFAULT) {
                        sanitized[name] = intensity.toString()
                    }
                }
            }
        }
        if (highlightAnchorBranch || hasHighlightParam) {
            sanitized["ah"] = if (highlightAnchorBranch) "1" else "0"
        }
        return sanitized.entries.joinToString("&") { (key, value) -> "$key=$value" }.ifBlank { null }
    }

    /**
     * Track-specific tuning to persist (with a favorite, a playlist entry, or a cached local
     * track's auto-saved tuning), built from live [TuningState] instead of the local engine —
     * the source of truth while casting, where the engine is reset on connect and never sees
     * cast tuning edits. Mirrors the engine capture format: only non-default values are
     * emitted and a fully default state yields null. `ah` is never emitted (persisted tuning
     * strips it).
     */
    fun buildSavedTuningParams(
        tuning: TuningState,
        audioModeWireValue: String? = null,
        audioModeIntensity: Int = AudioModeIntensity.DEFAULT
    ): String? {
        val defaults = TuningState()
        val params = mutableListOf<String>()
        if (tuning.justBackwards) {
            params.add("jb=1")
        }
        if (tuning.minJumpDistancePercent > 0) {
            params.add("bl=${tuning.minJumpDistancePercent}")
        }
        if (tuning.removeSequential) {
            params.add("sq=0")
        }
        // Omission is how auto travels, so only a chosen threshold is written.
        tuning.threshold?.let { params.add("thresh=${it.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)}") }
        val branchProbabilityChanged = tuning.minProb != defaults.minProb ||
            tuning.maxProb != defaults.maxProb ||
            tuning.ramp != defaults.ramp
        if (branchProbabilityChanged) {
            params.add(
                "bp=${tuning.minProb.coerceIn(0, 100)}," +
                    "${tuning.maxProb.coerceIn(0, 100)}," +
                    "${tuning.ramp.coerceIn(0, 100)}"
            )
        }
        if (tuning.deletedEdgeIds.isNotEmpty()) {
            params.add("d=${tuning.deletedEdgeIds.joinToString(",")}")
        }
        tuning.anchorBranchId?.takeIf { it >= 0 }?.let { anchorBranchId ->
            params.add("ab=$anchorBranchId")
        }
        val normalizedAudioMode = audioModeWireValue?.trim().orEmpty()
        if (normalizedAudioMode.isNotBlank() && normalizedAudioMode != JukeboxAudioMode.Off.wireValue) {
            params.add("am=$normalizedAudioMode")
            AudioModeIntensity.wireParamOrNull(
                JukeboxAudioMode.fromWireValue(normalizedAudioMode),
                audioModeIntensity
            )?.let { params.add(it) }
        }
        return if (params.isEmpty()) null else params.joinToString("&")
    }

    /**
     * Whether two persisted tuning strings describe the same tuning. Favorites arrive from the web
     * app as well as from here, so key order, legacy spellings (`lg`, `jb=true`, `am=off`), and
     * explicitly written defaults all have to read as a match against a locally built string.
     *
     * Deleted branches (`d`) and the anchor branch (`ab`) are excluded, and `ah` never survives the
     * builder because persisted tuning does not carry it.
     */
    fun savedTuningParamsEquivalent(left: String?, right: String?): Boolean {
        return canonicalSavedTuningParams(left) == canonicalSavedTuningParams(right)
    }

    /**
     * A persisted tuning string rewritten in the exact form [buildSavedTuningParams] emits, so two
     * spellings of the same tuning collapse to one string. Routing the comparison through [parse],
     * [mergeIntoState], and the builder is what keeps it complete: a tuning parameter is compared
     * as soon as it can be parsed and built, with no separate field list here to fall behind.
     */
    private fun canonicalSavedTuningParams(raw: String?): String? {
        val parsed = parse(raw)
        val tuning = mergeIntoState(
            base = TuningState(),
            parsed = parsed
        ).copy(
            // Dropped rather than compared: no on-device control touches them, they enter tuning
            // only when saved params are applied at load, and edge ids are positional in the
            // rebuilt graph, so a restored list can differ numerically from the saved one.
            deletedEdgeIds = emptyList(),
            anchorBranchId = null
        )
        return buildSavedTuningParams(
            tuning = tuning,
            audioModeWireValue = parsed?.audioMode?.wireValue,
            audioModeIntensity = parsed?.audioModeIntensity ?: AudioModeIntensity.DEFAULT
        )
    }

    fun buildHighlightParam(enabled: Boolean): String {
        return "ah=${if (enabled) 1 else 0}"
    }

    fun buildAudioModeParam(audioMode: JukeboxAudioMode): String {
        return "am=${audioMode.wireValue}"
    }

    fun buildAudioModeParam(audioModeWireValue: String): String? {
        val normalized = audioModeWireValue.trim()
        if (normalized.isBlank()) {
            return null
        }
        return "am=$normalized"
    }

    fun stripHighlightAnchorParam(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }
        val sanitized = linkedMapOf<String, String>()
        for ((name, values) in parseQuery(raw)) {
            if (name == "ah") {
                continue
            }
            val value = values.firstOrNull() ?: continue
            sanitized[name] = value
        }
        return encodeQuery(sanitized).ifBlank { null }
    }

    fun mergeIntoState(
        base: TuningState,
        parsed: ParsedTuningParams?
    ): TuningState {
        if (parsed == null) {
            return base
        }
        return base.copy(
            threshold = parsed.threshold ?: base.threshold,
            minProb = parsed.minProbPercent ?: base.minProb,
            maxProb = parsed.maxProbPercent ?: base.maxProb,
            ramp = parsed.rampPercent ?: base.ramp,
            highlightAnchorBranch = parsed.highlightAnchorBranch ?: base.highlightAnchorBranch,
            justBackwards = parsed.justBackwards ?: base.justBackwards,
            minJumpDistancePercent =
                parsed.minJumpDistancePercent ?: base.minJumpDistancePercent,
            removeSequential = parsed.removeSequentialBranches ?: base.removeSequential
        )
    }

    private fun parseStandardBoolean(raw: String?): Boolean? {
        if (raw == null) return null
        return when (raw.lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
    }

    private fun parseRemoveSequential(raw: String?): Boolean? {
        if (raw == null) return null
        return when (raw.lowercase()) {
            "0", "true" -> true
            "1", "false" -> false
            else -> null
        }
    }

    private fun sanitizeTriplet(raw: String): String? {
        val values = raw.split(",")
        if (values.size != 3) {
            return null
        }
        return values
            .map { it.toIntOrNull()?.coerceIn(0, 100) ?: return null }
            .joinToString(",")
    }

    private fun sanitizeIdList(raw: String): String? {
        return raw.split(",")
            .mapNotNull { it.toIntOrNull()?.takeIf { id -> id >= 0 } }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")
    }

    private fun parseQuery(raw: String): LinkedHashMap<String, MutableList<String>> {
        if (raw.isBlank()) {
            return linkedMapOf()
        }
        val parsed = linkedMapOf<String, MutableList<String>>()
        raw.split("&")
            .filter { it.isNotBlank() }
            .forEach { part ->
                val sep = part.indexOf('=')
                val keyRaw = if (sep >= 0) part.substring(0, sep) else part
                val valueRaw = if (sep >= 0) part.substring(sep + 1) else ""
                val key = decode(keyRaw).trim()
                if (key.isBlank()) {
                    return@forEach
                }
                parsed.getOrPut(key) { mutableListOf() }.add(decode(valueRaw))
            }
        return parsed
    }

    private fun Map<String, List<String>>.firstValue(name: String): String? =
        this[name]?.firstOrNull()

    private fun encodeQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.toString())

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
