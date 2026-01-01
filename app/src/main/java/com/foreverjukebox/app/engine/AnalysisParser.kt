package com.foreverjukebox.app.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private fun requireObject(element: JsonElement?, path: String): JsonObject {
    return element as? JsonObject
        ?: throw IllegalArgumentException("Expected object at $path")
}

private fun requireArray(element: JsonElement?, path: String): JsonArray {
    return element as? JsonArray
        ?: throw IllegalArgumentException("Expected array at $path")
}

private fun requireNumber(element: JsonElement?, path: String): Double {
    val primitive = element as? JsonPrimitive
        ?: throw IllegalArgumentException("Expected number at $path")
    val value = primitive.contentOrNull?.toDoubleOrNull()
    return value ?: throw IllegalArgumentException("Expected number at $path")
}

private fun requireNumberArray(element: JsonElement?, path: String, minLength: Int): List<Double> {
    val array = requireArray(element, path)
    if (array.size < minLength) {
        throw IllegalArgumentException("Expected $minLength+ numbers at $path")
    }
    return array.mapIndexed { index, item ->
        requireNumber(item, "$path[$index]")
    }
}

private fun parseQuantumList(element: JsonElement?, path: String): MutableList<QuantumBase> {
    val list = requireArray(element, path)
    return list.mapIndexed { index, item ->
        val obj = requireObject(item, "$path[$index]")
        QuantumBase(
            start = requireNumber(obj["start"], "$path[$index].start"),
            duration = requireNumber(obj["duration"], "$path[$index].duration"),
            confidence = obj["confidence"]?.let { requireNumber(it, "$path[$index].confidence") },
            which = index
        )
    }.toMutableList()
}

private fun parseSegments(element: JsonElement?, path: String): MutableList<Segment> {
    val list = requireArray(element, path)
    return list.mapIndexed { index, item ->
        val obj = requireObject(item, "$path[$index]")
        Segment(
            start = requireNumber(obj["start"], "$path[$index].start"),
            duration = requireNumber(obj["duration"], "$path[$index].duration"),
            confidence = requireNumber(obj["confidence"], "$path[$index].confidence"),
            loudnessStart = requireNumber(obj["loudness_start"], "$path[$index].loudness_start"),
            loudnessMax = requireNumber(obj["loudness_max"], "$path[$index].loudness_max"),
            loudnessMaxTime = requireNumber(obj["loudness_max_time"], "$path[$index].loudness_max_time"),
            pitches = requireNumberArray(obj["pitches"], "$path[$index].pitches", 12),
            timbre = requireNumberArray(obj["timbre"], "$path[$index].timbre", 12),
            which = index
        )
    }.toMutableList()
}

private fun parseTrackMeta(root: JsonObject): TrackMeta? {
    val track = root["track"] as? JsonObject ?: return null
    val duration = track["duration"]?.let { requireNumber(it, "track.duration") }
    val tempo = track["tempo"]?.let { requireNumber(it, "track.tempo") }
    val timeSignature = track["time_signature"]?.let { requireNumber(it, "track.time_signature") }
    return TrackMeta(duration = duration, tempo = tempo, timeSignature = timeSignature)
}

private fun resolveAnalysisRoot(data: JsonObject): JsonObject {
    val analysis = data["analysis"] as? JsonObject
    return if (analysis != null && analysis.containsKey("beats")) {
        analysis
    } else {
        data
    }
}

fun parseAnalysis(input: JsonElement): TrackAnalysis {
    val rootObj = requireObject(input, "analysis")
    val root = resolveAnalysisRoot(rootObj)
    val sections = parseQuantumList(root["sections"], "sections")
    val bars = parseQuantumList(root["bars"], "bars")
    val beats = parseQuantumList(root["beats"], "beats")
    val tatums = parseQuantumList(root["tatums"], "tatums")
    val segments = parseSegments(root["segments"], "segments")
    return TrackAnalysis(
        sections = sections,
        bars = bars,
        beats = beats,
        tatums = tatums,
        segments = segments,
        track = parseTrackMeta(rootObj)
    )
}

private fun linkNeighbors(list: MutableList<QuantumBase>) {
    list.forEachIndexed { index, q ->
        q.which = index
        q.prev = if (index > 0) list[index - 1] else null
        q.next = if (index < list.size - 1) list[index + 1] else null
    }
}

private fun connectQuanta(parentList: MutableList<QuantumBase>, childList: MutableList<QuantumBase>) {
    var last = 0
    for (parent in parentList) {
        parent.children = mutableListOf()
        for (j in last until childList.size) {
            val child = childList[j]
            if (child.start >= parent.start && child.start < parent.start + parent.duration) {
                child.parent = parent
                child.indexInParent = parent.children.size
                parent.children.add(child)
                last = j
            } else if (child.start > parent.start) {
                break
            }
        }
    }
}

private fun connectFirstOverlappingSegment(quanta: MutableList<QuantumBase>, segments: MutableList<Segment>) {
    var last = 0
    for (q in quanta) {
        for (j in last until segments.size) {
            val seg = segments[j]
            if (seg.start >= q.start) {
                q.oseg = seg
                last = j
                break
            }
        }
    }
}

private fun connectAllOverlappingSegments(quanta: MutableList<QuantumBase>, segments: MutableList<Segment>) {
    var last = 0
    for (q in quanta) {
        q.overlappingSegments = mutableListOf()
        for (j in last until segments.size) {
            val seg = segments[j]
            if (seg.start + seg.duration < q.start) {
                continue
            }
            if (seg.start > q.start + q.duration) {
                break
            }
            last = j
            q.overlappingSegments.add(seg)
        }
    }
}

fun normalizeAnalysis(input: JsonElement): TrackAnalysis {
    val analysis = parseAnalysis(input)
    val sections = analysis.sections
    val bars = analysis.bars
    val beats = analysis.beats
    val tatums = analysis.tatums
    val segments = analysis.segments

    linkNeighbors(sections)
    linkNeighbors(bars)
    linkNeighbors(beats)
    linkNeighbors(tatums)

    connectQuanta(sections, bars)
    connectQuanta(bars, beats)
    connectQuanta(beats, tatums)

    connectFirstOverlappingSegment(bars, segments)
    connectFirstOverlappingSegment(beats, segments)
    connectFirstOverlappingSegment(tatums, segments)

    connectAllOverlappingSegments(bars, segments)
    connectAllOverlappingSegments(beats, segments)
    connectAllOverlappingSegments(tatums, segments)

    return analysis
}
