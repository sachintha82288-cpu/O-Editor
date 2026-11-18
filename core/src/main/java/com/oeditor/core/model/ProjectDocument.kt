package com.oeditor.core.model

import kotlinx.serialization.Serializable

/**
 * O Editor persistent project document.
 *
 * The document is the single source of truth for an edit. It contains no media
 * payload, only references (SAF content URIs) plus the structured edit state, so it
 * stays small enough to snapshot, back up and diff.
 */
@Serializable
data class ProjectDocument(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String = "",
    val name: String = "Untitled project",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val settings: ProjectSettings = ProjectSettings(),
    val assets: List<MediaAsset> = emptyList(),
    val timeline: Timeline = Timeline(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class ProjectSettings(
    val width: Int = 1080,
    val height: Int = 1920,
    val fpsNum: Int = 30,
    val fpsDen: Int = 1,
    val sampleRate: Int = 48_000,
    val channelCount: Int = 2,
    val backgroundArgb: Int = 0xFF000000.toInt(),
) {
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()
    val frameDurationUs: Long get() = 1_000_000L * fpsDen / fpsNum

    fun isValid(): Boolean = width > 0 && height > 0 && fpsNum > 0 && fpsDen > 0
}

@Serializable
enum class MediaKind { VIDEO, AUDIO, IMAGE }

@Serializable
data class MediaAsset(
    val id: String,
    val uri: String,
    val displayName: String,
    val kind: MediaKind,
    val durationUs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDeg: Int = 0,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val mimeType: String = "",
    val fileSizeBytes: Long = 0L,
    val frameRateNum: Int = 0,
    val frameRateDen: Int = 1,
    val missing: Boolean = false,
    val importedAt: Long = 0L,
) {
    val hasVideo: Boolean get() = kind == MediaKind.VIDEO || kind == MediaKind.IMAGE
    val hasAudio: Boolean get() = kind == MediaKind.AUDIO || (kind == MediaKind.VIDEO && sampleRate > 0)
}

@Serializable
enum class TrackType { VIDEO, AUDIO, TEXT, OVERLAY, EFFECT }

@Serializable
data class Track(
    val id: String,
    val name: String,
    val type: TrackType,
    val order: Int,
    val locked: Boolean = false,
    val muted: Boolean = false,
    val hidden: Boolean = false,
    val volume: Float = 1f,
    val clips: List<Clip> = emptyList(),
)

@Serializable
data class Timeline(
    val tracks: List<Track> = emptyList(),
    val markers: List<Marker> = emptyList(),
) {
    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }

    fun clip(clipId: String): Pair<Track, Clip>? {
        for (t in tracks) {
            val c = t.clips.firstOrNull { it.id == clipId }
            if (c != null) return t to c
        }
        return null
    }

    fun allClips(): List<Pair<Track, Clip>> = tracks.flatMap { t -> t.clips.map { t to it } }

    fun durationUs(): Long = tracks.flatMap { it.clips }
        .maxOfOrNull { it.startUs + it.durationUs } ?: 0L

    /** Tracks ordered back-to-front as they must be composited (first paints first). */
    fun compositingOrder(): List<Track> = tracks
        .filter { it.type != TrackType.AUDIO && !it.hidden }
        .sortedByDescending { it.order }
}

@Serializable
data class Marker(
    val id: String,
    val timeUs: Long,
    val label: String = "",
    val colorArgb: Int = 0xFFFFC857.toInt(),
)
