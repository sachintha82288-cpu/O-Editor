package com.oeditor.core.timeline

import com.oeditor.core.keyframe.KeyframeEngine
import com.oeditor.core.model.Clip
import com.oeditor.core.model.Marker
import com.oeditor.core.model.ProjectDocument
import com.oeditor.core.model.Timeline
import com.oeditor.core.model.Track
import com.oeditor.core.model.TrackType

/** How a new clip interacts with material already on the track. */
enum class EditMode { OVERWRITE, INSERT }

enum class TrimEdge { START, END }

/**
 * Deterministic timeline operations.
 *
 * Every function is pure: it takes a document and returns a new one. Nothing mutates
 * in place, which is what makes undo/redo, autosave and export-from-a-snapshot safe.
 */
object TimelineEngine {

    const val MIN_CLIP_DURATION_US = 10_000L

    // ---------------------------------------------------------------- plumbing

    fun withTimeline(doc: ProjectDocument, timeline: Timeline): ProjectDocument =
        doc.copy(timeline = timeline, updatedAt = System.currentTimeMillis())

    fun updateTrack(doc: ProjectDocument, trackId: String, block: (Track) -> Track): ProjectDocument {
        val tracks = doc.timeline.tracks.map { if (it.id == trackId) block(it) else it }
        return withTimeline(doc, doc.timeline.copy(tracks = tracks))
    }

    fun updateClip(doc: ProjectDocument, clipId: String, block: (Clip) -> Clip): ProjectDocument {
        val tracks = doc.timeline.tracks.map { track ->
            if (track.clips.none { it.id == clipId }) track
            else track.copy(clips = track.clips.map { if (it.id == clipId) block(it) else it })
        }
        return withTimeline(doc, doc.timeline.copy(tracks = tracks))
    }

    fun removeClip(doc: ProjectDocument, clipId: String): ProjectDocument {
        val tracks = doc.timeline.tracks.map { t -> t.copy(clips = t.clips.filterNot { it.id == clipId }) }
        return withTimeline(doc, doc.timeline.copy(tracks = tracks))
    }

    // ---------------------------------------------------------------- placement

    /**
     * Places [clip] on [trackId]. OVERWRITE trims or splits whatever it lands on;
     * INSERT pushes later material to the right so nothing is lost.
     */
    fun placeClip(
        doc: ProjectDocument,
        trackId: String,
        clip: Clip,
        mode: EditMode = EditMode.OVERWRITE,
    ): ProjectDocument {
        val track = doc.timeline.track(trackId) ?: return doc
        if (track.locked) return doc
        val start = clip.startUs.coerceAtLeast(0L)
        val duration = clip.durationUs.coerceAtLeast(MIN_CLIP_DURATION_US)
        val placed = clip.copy(startUs = start, durationUs = duration)

        val result = when (mode) {
            EditMode.OVERWRITE -> track.copy(clips = subtract(track.clips, start, start + duration) + placed)
            EditMode.INSERT -> {
                val shifted = track.clips.map {
                    if (it.startUs >= start) it.copy(startUs = it.startUs + duration) else it
                }
                track.copy(clips = shifted + placed)
            }
        }
        return updateTrack(doc, trackId, { result }).sortedTrack(trackId)
    }

    private fun ProjectDocument.sortedTrack(trackId: String): ProjectDocument =
        updateTrack(this, trackId) { t -> t.copy(clips = t.clips.sortedBy { it.startUs }) }

    /**
     * Removes the [start, end) window from a track's clips, trimming or splitting the
     * clips that straddle it. Returns the clips that survive the window.
     */
    fun subtract(clips: List<Clip>, start: Long, end: Long): List<Clip> {
        if (end <= start) return clips
        val out = ArrayList<Clip>(clips.size + 1)
        for (c in clips) {
            val cEnd = c.endUs
            when {
                cEnd <= start || c.startUs >= end -> out.add(c)
                c.startUs >= start && cEnd <= end -> Unit // fully covered, dropped
                c.startUs < start && cEnd > end -> {
                    // clip covers the whole window: keep head and tail
                    trimTo(c, TrimEdge.END, start)?.let { out.add(it) }
                    splitTail(c, end)?.let { out.add(it) }
                }
                c.startUs < start -> trimTo(c, TrimEdge.END, start)?.let { out.add(it) }
                else -> splitTail(c, end)?.let { out.add(it) }
            }
        }
        return out.sortedBy { it.startUs }
    }

    /** Trims a clip so that its start becomes [newStart]. */
    private fun splitTail(c: Clip, newStart: Long): Clip? {
        val delta = newStart - c.startUs
        if (delta <= 0 || delta >= c.durationUs) return null
        val sourceAdvance = (delta * c.speed).toLong()
        return c.copy(
            startUs = newStart,
            durationUs = c.durationUs - delta,
            sourceInUs = c.sourceInUs + sourceAdvance,
            keyframes = KeyframeEngine.shift(c.keyframes, delta),
        )
    }

    private fun trimTo(c: Clip, edge: TrimEdge, newTimeUs: Long): Clip? = when (edge) {
        TrimEdge.START -> splitTail(c, newTimeUs)
        TrimEdge.END -> {
            val newDuration = newTimeUs - c.startUs
            if (newDuration < MIN_CLIP_DURATION_US) null else c.copy(durationUs = newDuration)
        }
    }

    // ---------------------------------------------------------------- editing

    fun split(doc: ProjectDocument, clipId: String, atUs: Long): ProjectDocument {
        val (track, clip) = doc.timeline.clip(clipId) ?: return doc
        if (track.locked || clip.locked) return doc
        if (atUs <= clip.startUs + MIN_CLIP_DURATION_US) return doc
        if (atUs >= clip.endUs - MIN_CLIP_DURATION_US) return doc

        val delta = atUs - clip.startUs
        val head = clip.copy(durationUs = delta)
        val tailSourceAdvance = (delta * clip.speed).toLong()
        val tail = clip.copy(
            id = newId("clip"),
            startUs = atUs,
            durationUs = clip.durationUs - delta,
            sourceInUs = clip.sourceInUs + tailSourceAdvance,
            keyframes = KeyframeEngine.shift(clip.keyframes, delta),
            transitionIn = null,
            label = clip.label,
        )
        val clips = track.clips.filterNot { it.id == clipId } + head + tail
        return updateTrack(doc, track.id) { it.copy(clips = clips.sortedBy { c -> c.startUs }) }
    }

    /** Splits every unlocked clip crossing [atUs] — the classic razor-all operation. */
    fun splitAllAt(doc: ProjectDocument, atUs: Long): ProjectDocument {
        var result = doc
        val targets = doc.timeline.allClips()
            .filter { (track, clip) -> !track.locked && !clip.locked && clip.contains(atUs) }
            .map { it.second.id }
        for (id in targets) result = split(result, id, atUs)
        return result
    }

    fun trim(doc: ProjectDocument, clipId: String, edge: TrimEdge, newTimeUs: Long): ProjectDocument {
        val (track, clip) = doc.timeline.clip(clipId) ?: return doc
        if (track.locked || clip.locked) return doc
        return when (edge) {
            TrimEdge.START -> {
                val maxStart = clip.endUs - MIN_CLIP_DURATION_US
                val clamped = newTimeUs.coerceIn(0L, maxStart)
                val delta = clamped - clip.startUs
                if (delta == 0L) return doc
                val sourceIn = (clip.sourceInUs + (delta * clip.speed).toLong()).coerceAtLeast(0L)
                val updated = clip.copy(
                    startUs = clamped,
                    durationUs = clip.durationUs - delta,
                    sourceInUs = sourceIn,
                    keyframes = KeyframeEngine.shift(clip.keyframes, delta),
                )
                updateTrack(doc, track.id) { t ->
                    t.copy(clips = t.clips.map { if (it.id == clipId) updated else it })
                }
            }
            TrimEdge.END -> {
                val minEnd = clip.startUs + MIN_CLIP_DURATION_US
                val clamped = newTimeUs.coerceAtLeast(minEnd)
                val updated = clip.copy(durationUs = clamped - clip.startUs)
                updateTrack(doc, track.id) { t ->
                    t.copy(clips = t.clips.map { if (it.id == clipId) updated else it })
                }
            }
        }
    }

    fun moveClip(
        doc: ProjectDocument,
        clipId: String,
        targetTrackId: String,
        newStartUs: Long,
        mode: EditMode = EditMode.OVERWRITE,
    ): ProjectDocument {
        val (sourceTrack, clip) = doc.timeline.clip(clipId) ?: return doc
        if (sourceTrack.locked || clip.locked) return doc
        val targetTrack = doc.timeline.track(targetTrackId) ?: return doc
        if (targetTrack.locked) return doc
        if (!accepts(targetTrack, clip)) return doc

        val start = newStartUs.coerceAtLeast(0L)
        val moved = clip.copy(startUs = start)
        val cleared = sourceTrack.copy(clips = sourceTrack.clips.filterNot { it.id == clipId })

        val replaced = when (mode) {
            EditMode.OVERWRITE -> cleared.copy(
                clips = subtract(cleared.clips, start, start + moved.durationUs) + moved
            )
            EditMode.INSERT -> {
                val shifted = cleared.clips.map {
                    if (it.startUs >= start) it.copy(startUs = it.startUs + moved.durationUs) else it
                }
                cleared.copy(clips = shifted + moved)
            }
        }

        var result = updateTrack(doc, sourceTrack.id) { if (it.id == sourceTrack.id) cleared else it }
        result = updateTrack(result, targetTrack.id) { replaced }
        return result.sortedTrack(targetTrack.id)
    }

    /** True when the track type can host the clip's content. */
    fun accepts(track: Track, clip: Clip): Boolean = when (track.type) {
        TrackType.VIDEO -> clip.text == null && clip.shape == null
        TrackType.OVERLAY -> true
        TrackType.TEXT -> clip.text != null
        TrackType.EFFECT -> true
        TrackType.AUDIO -> clip.assetId != null
    }

    fun delete(doc: ProjectDocument, clipId: String): ProjectDocument = removeClip(doc, clipId)

    /** Deletes a clip and closes the gap it leaves on its own track. */
    fun rippleDelete(doc: ProjectDocument, clipId: String): ProjectDocument {
        val (track, clip) = doc.timeline.clip(clipId) ?: return doc
        if (track.locked || clip.locked) return doc
        val clips = track.clips.filterNot { it.id == clipId }.map {
            if (it.startUs >= clip.endUs) it.copy(startUs = it.startUs - clip.durationUs) else it
        }
        return updateTrack(doc, track.id) { it.copy(clips = clips) }
    }

    fun rippleTrim(doc: ProjectDocument, clipId: String, edge: TrimEdge, newTimeUs: Long): ProjectDocument {
        val (track, clip) = doc.timeline.clip(clipId) ?: return doc
        val before = clip.durationUs
        val trimmed = trim(doc, clipId, edge, newTimeUs)
        val (_, after) = trimmed.timeline.clip(clipId) ?: return trimmed
        val delta = after.durationUs - before
        if (delta == 0L) return trimmed
        val clips = (trimmed.timeline.track(track.id)?.clips ?: return trimmed).map {
            if (it.id != clipId && it.startUs >= clip.endUs) it.copy(startUs = it.startUs + delta) else it
        }
        return updateTrack(trimmed, track.id) { it.copy(clips = clips) }
    }

    fun duplicate(doc: ProjectDocument, clipId: String, offsetUs: Long = 0L): ProjectDocument {
        val (track, clip) = doc.timeline.clip(clipId) ?: return doc
        val copy = clip.copy(
            id = newId("clip"),
            startUs = (clip.startUs + clip.durationUs + offsetUs).coerceAtLeast(0L),
            transitionIn = null,
        )
        return placeClip(doc, track.id, copy, EditMode.OVERWRITE)
    }

    /** Speed change keeps the visible source range and rescales the timeline duration. */
    fun setSpeed(doc: ProjectDocument, clipId: String, speed: Float): ProjectDocument {
        val (_, clip) = doc.timeline.clip(clipId) ?: return doc
        val newSpeed = speed.coerceIn(0.05f, 10f)
        if (newSpeed == clip.speed) return doc
        val newDuration = (clip.durationUs * clip.speed / newSpeed).toLong()
            .coerceAtLeast(MIN_CLIP_DURATION_US)
        return updateClip(doc, clipId) { it.copy(speed = newSpeed, durationUs = newDuration) }
    }

    fun freezeFrame(doc: ProjectDocument, clipId: String, atUs: Long, holdUs: Long): ProjectDocument {
        val (track, clip) = doc.timeline.clip(clipId) ?: return doc
        val at = atUs.coerceIn(clip.startUs, clip.endUs - 1)
        val sourceTime = clip.sourceTimeAt(at)
        val frozen = clip.copy(
            id = newId("clip"),
            startUs = at,
            durationUs = holdUs.coerceAtLeast(MIN_CLIP_DURATION_US),
            sourceInUs = sourceTime,
            speed = 1f,
            frozen = true,
            keyframes = emptyList(),
        )
        return placeClip(doc, track.id, frozen, EditMode.INSERT)
    }

    // ---------------------------------------------------------------- tracks

    fun addTrack(doc: ProjectDocument, type: TrackType, name: String = ""): ProjectDocument {
        val order = (doc.timeline.tracks.maxOfOrNull { it.order } ?: 0) + 1
        val track = Track(
            id = newId("track"),
            name = name.ifBlank { defaultTrackName(type, order) },
            type = type,
            order = order,
        )
        return withTimeline(doc, doc.timeline.copy(tracks = doc.timeline.tracks + track))
    }

    fun removeTrack(doc: ProjectDocument, trackId: String): ProjectDocument =
        withTimeline(doc, doc.timeline.copy(tracks = doc.timeline.tracks.filterNot { it.id == trackId }))

    fun moveTrack(doc: ProjectDocument, trackId: String, newOrder: Int): ProjectDocument {
        val tracks = doc.timeline.tracks.toMutableList()
        val index = tracks.indexOfFirst { it.id == trackId }
        if (index < 0) return doc
        val track = tracks.removeAt(index).copy(order = newOrder)
        tracks.add(index, track)
        val renumbered = tracks.sortedBy { it.order }.mapIndexed { i, t -> t.copy(order = i + 1) }
        return withTimeline(doc, doc.timeline.copy(tracks = renumbered))
    }

    private fun defaultTrackName(type: TrackType, order: Int): String = when (type) {
        TrackType.VIDEO -> "Video $order"
        TrackType.AUDIO -> "Audio $order"
        TrackType.TEXT -> "Text $order"
        TrackType.OVERLAY -> "Overlay $order"
        TrackType.EFFECT -> "Effect $order"
    }

    // ---------------------------------------------------------------- markers

    fun addMarker(doc: ProjectDocument, timeUs: Long, label: String = ""): ProjectDocument {
        val marker = Marker(id = newId("marker"), timeUs = timeUs, label = label)
        return withTimeline(doc, doc.timeline.copy(markers = (doc.timeline.markers + marker).sortedBy { it.timeUs }))
    }

    fun removeMarker(doc: ProjectDocument, markerId: String): ProjectDocument =
        withTimeline(doc, doc.timeline.copy(markers = doc.timeline.markers.filterNot { it.id == markerId }))

    // ---------------------------------------------------------------- snapping

    /** Snaps [timeUs] to the nearest clip edge, marker, playhead or zero inside [thresholdUs]. */
    fun snapTime(
        doc: ProjectDocument,
        timeUs: Long,
        thresholdUs: Long,
        excludeClipId: String? = null,
        playheadUs: Long? = null,
    ): Long {
        if (thresholdUs <= 0) return timeUs
        var best = timeUs
        var bestDistance = thresholdUs
        fun consider(candidate: Long) {
            val d = kotlin.math.abs(candidate - timeUs)
            if (d < bestDistance) {
                bestDistance = d
                best = candidate
            }
        }
        consider(0L)
        playheadUs?.let { consider(it) }
        for ((_, clip) in doc.timeline.allClips()) {
            if (clip.id == excludeClipId) continue
            consider(clip.startUs)
            consider(clip.endUs)
        }
        for (marker in doc.timeline.markers) consider(marker.timeUs)
        return best
    }

    // ---------------------------------------------------------------- queries

    fun clipAt(doc: ProjectDocument, trackId: String, timeUs: Long): Clip? =
        doc.timeline.track(trackId)?.clips?.firstOrNull { it.contains(timeUs) }

    fun durationUs(doc: ProjectDocument): Long = doc.timeline.durationUs()

    fun clipsOf(doc: ProjectDocument, assetId: String): List<Pair<Track, Clip>> =
        doc.timeline.allClips().filter { it.second.assetId == assetId }

    fun newId(prefix: String): String =
        prefix + "_" + java.lang.Long.toHexString(System.nanoTime()) + "_" +
            (0..999).random().toString().padStart(3, '0')
}
