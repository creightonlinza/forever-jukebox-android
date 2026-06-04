package com.foreverjukebox.app.ui

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ParsedTuningParams(
    val threshold: Int?,
    val minProbPercent: Int?,
    val maxProbPercent: Int?,
    val rampPercent: Int?,
    val highlightAnchorBranch: Boolean?,
    val justBackwards: Boolean?,
    val justLongBranches: Boolean?,
    val removeSequentialBranches: Boolean?,
    val deletedEdgeIds: List<Int>,
    val anchorBeat: Int?,
    val audioMode: JukeboxAudioMode?
)

object TuningParamsCodec {
    private val knownKeys = setOf("jb", "lg", "sq", "thresh", "bp", "d", "ab", "ah", "am")
    fun parse(raw: String?, minThreshold: Int = 0): ParsedTuningParams? {
        if (raw.isNullOrBlank()) {
            return null
        }
        val params = parseQuery(raw)
        if (params.keys.none { it in knownKeys }) {
            return null
        }
        val audioMode = JukeboxAudioMode.fromWireValue(params.firstValue("am"))
        if (params.keys.all { it == "am" } && audioMode == null) {
            return null
        }
        val threshold = params.firstValue("thresh")
            ?.toIntOrNull()
            ?.takeIf { it >= minThreshold }

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
        val anchorBeat = params.firstValue("ab")
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }

        return ParsedTuningParams(
            threshold = threshold,
            minProbPercent = minProbPercent,
            maxProbPercent = maxProbPercent,
            rampPercent = rampPercent,
            highlightAnchorBranch = parseStandardBoolean(params.firstValue("ah")),
            justBackwards = parseStandardBoolean(params.firstValue("jb")),
            justLongBranches = parseStandardBoolean(params.firstValue("lg")),
            removeSequentialBranches = parseRemoveSequential(params.firstValue("sq")),
            deletedEdgeIds = deletedEdgeIds,
            anchorBeat = anchorBeat,
            audioMode = audioMode
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
        for ((name, values) in params) {
            val value = values.firstOrNull() ?: continue
            when (name) {
                "jb", "lg" -> parseStandardBoolean(value)?.let {
                    sanitized[name] = if (it) "1" else "0"
                }
                "ah" -> if (parseStandardBoolean(value) != null) {
                    sanitized[name] = if (highlightAnchorBranch) "1" else "0"
                }
                "sq" -> parseRemoveSequential(value)?.let {
                    sanitized[name] = if (it) "0" else "1"
                }
                "thresh" -> value.toIntOrNull()
                    ?.takeIf { it >= 2 }
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
            }
        }
        if (highlightAnchorBranch || hasHighlightParam) {
            sanitized["ah"] = if (highlightAnchorBranch) "1" else "0"
        }
        return sanitized.entries.joinToString("&") { (key, value) -> "$key=$value" }.ifBlank { null }
    }

    fun buildFromTuningState(
        tuning: TuningState,
        audioMode: JukeboxAudioMode = JukeboxAudioMode.Off,
        includeOffAudioMode: Boolean = false
    ): String {
        val params = mutableListOf(
            "jb=${if (tuning.justBackwards) 1 else 0}",
            "lg=${if (tuning.justLong) 1 else 0}",
            "sq=${if (tuning.removeSequential) 0 else 1}",
            "thresh=${tuning.threshold.coerceAtLeast(2)}",
            "bp=${tuning.minProb.coerceIn(0, 100)},${tuning.maxProb.coerceIn(0, 100)},${tuning.ramp.coerceIn(0, 100)}",
            "ah=${if (tuning.highlightAnchorBranch) 1 else 0}"
        )
        if (audioMode != JukeboxAudioMode.Off || includeOffAudioMode) {
            params.add("am=${audioMode.wireValue}")
        }
        return params.joinToString("&")
    }

    fun buildHighlightParam(enabled: Boolean): String {
        return "ah=${if (enabled) 1 else 0}"
    }

    fun buildAudioModeParam(audioMode: JukeboxAudioMode): String {
        return "am=${audioMode.wireValue}"
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
            justLong = parsed.justLongBranches ?: base.justLong,
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
