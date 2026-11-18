package com.oeditor.data

import android.content.Context
import com.oeditor.core.media.MediaProbe
import com.oeditor.core.model.Clip
import com.oeditor.core.model.MediaAsset
import com.oeditor.core.model.ProjectDocument
import com.oeditor.core.model.ProjectSettings
import com.oeditor.core.model.Timeline
import com.oeditor.core.model.Track
import com.oeditor.core.model.TrackType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Project persistence.
 *
 * The document lives as structured JSON inside Room, so a save is one atomic row write
 * and the timeline stays queryable. Every save also writes a recovery point and a file
 * backup, and the reader falls back to those if the primary row is unreadable.
 */
class ProjectRepository(private val context: Context) {

    private val store = ProjectStore(context)
    private val saveMutex = Mutex()

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
        // A single NaN/Infinity (from a degenerate slider or a bad bezier solve) would
        // otherwise make encoding throw and take down the autosave coroutine.
        allowSpecialFloatingPointValues = true
    }

    private val backupDir: File
        get() = File(context.filesDir, "project_backups").apply { mkdirs() }

    fun observeSummaries(): Flow<List<ProjectSummary>> = store.observeSummaries()

    fun observeDocument(id: String): Flow<ProjectDocument?> =
        store.observeSummaries().map { load(id) }

    suspend fun createProject(
        name: String,
        settings: ProjectSettings = ProjectSettings(),
    ): ProjectDocument = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val doc = ProjectDocument(
            id = "proj_" + java.lang.Long.toHexString(System.nanoTime()),
            name = name.ifBlank { "Untitled project" },
            createdAt = now,
            updatedAt = now,
            settings = settings,
            timeline = defaultTimeline(),
        )
        save(doc)
        doc
    }

    private fun defaultTimeline(): Timeline {
        val now = System.nanoTime()
        return Timeline(
            tracks = listOf(
                Track("track_v1_$now", "Video 1", TrackType.VIDEO, 3),
                Track("track_v2_$now", "Overlay 1", TrackType.OVERLAY, 2),
                Track("track_t1_$now", "Text 1", TrackType.TEXT, 1),
                Track("track_a1_$now", "Audio 1", TrackType.AUDIO, 0),
            )
        )
    }

    suspend fun load(id: String): ProjectDocument? = withContext(Dispatchers.IO) {
        val row = runCatching { store.find(id) }.getOrNull()
        if (row != null) {
            val decoded = decode(row.documentJson)
            if (decoded != null) return@withContext decoded
        }
        runCatching { store.recovery(id) }.getOrNull()?.let { recovery ->
            decode(recovery.documentJson)?.let { return@withContext it }
        }
        val backup = File(backupDir, "$id.json")
        if (backup.exists()) {
            runCatching { decode(backup.readText()) }.getOrNull()?.let { return@withContext it }
        }
        null
    }

    fun hasRecovery(id: String): Boolean = File(backupDir, "$id.json").exists()

    suspend fun recoverySnapshot(id: String): ProjectDocument? = withContext(Dispatchers.IO) {
        store.recovery(id)?.let { decode(it.documentJson) }
            ?: File(backupDir, "$id.json").takeIf { it.exists() }?.let { decode(it.readText()) }
    }

    suspend fun save(document: ProjectDocument): Boolean = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            val stamped = document.copy(updatedAt = System.currentTimeMillis())
            val payload = runCatching {
                json.encodeToString(ProjectDocument.serializer(), stamped)
            }.getOrNull() ?: return@withLock false
            val row = ProjectRow(
                id = stamped.id,
                name = stamped.name,
                createdAt = stamped.createdAt,
                updatedAt = stamped.updatedAt,
                durationUs = stamped.timeline.durationUs(),
                schemaVersion = ProjectDocument.SCHEMA_VERSION,
                width = stamped.settings.width,
                height = stamped.settings.height,
                fpsNum = stamped.settings.fpsNum,
                fpsDen = stamped.settings.fpsDen,
                clipCount = stamped.timeline.tracks.sumOf { it.clips.size },
                documentJson = payload,
            )
            try {
                store.save(row, RecoveryRow(stamped.id, stamped.updatedAt, payload))
                writeBackup(stamped.id, payload)
                true
            } catch (_: Exception) {
                runCatching { writeBackup(stamped.id, payload) }
                false
            }
        }
    }

    /** Atomic file write: temp file + rename, so a killed process cannot truncate a backup. */
    private fun writeBackup(id: String, payload: String) {
        val target = File(backupDir, "$id.json")
        val temp = File(backupDir, "$id.json.tmp")
        temp.writeText(payload)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        val doc = load(id) ?: return@withContext
        save(doc.copy(name = name))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        store.delete(id)
        File(backupDir, "$id.json").delete()
    }

    suspend fun duplicate(id: String): ProjectDocument? = withContext(Dispatchers.IO) {
        val source = load(id) ?: return@withContext null
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = "proj_" + java.lang.Long.toHexString(System.nanoTime()),
            name = source.name + " copy",
            createdAt = now,
            updatedAt = now,
        )
        save(copy)
        copy
    }

    suspend fun clearRecovery(id: String) = withContext(Dispatchers.IO) { store.clearRecovery(id) }

    /** Flags assets whose source file no longer resolves, so the UI can offer relinking. */
    fun detectMissingMedia(doc: ProjectDocument): ProjectDocument {
        if (doc.assets.isEmpty()) return doc
        val updated = doc.assets.map { asset ->
            asset.copy(missing = !MediaProbe.exists(context, asset.uri))
        }
        return if (updated == doc.assets) doc else doc.copy(assets = updated)
    }

    fun relink(doc: ProjectDocument, assetId: String, newUri: String): ProjectDocument {
        val updated = doc.assets.map {
            if (it.id == assetId) it.copy(uri = newUri, missing = !MediaProbe.exists(context, newUri)) else it
        }
        return doc.copy(assets = updated)
    }

    fun importAsset(doc: ProjectDocument, asset: MediaAsset): ProjectDocument =
        doc.copy(assets = doc.assets + asset)

    fun dropAsset(doc: ProjectDocument, assetId: String): ProjectDocument = doc.copy(
        assets = doc.assets.filterNot { it.id == assetId },
        timeline = doc.timeline.copy(
            tracks = doc.timeline.tracks.map { track ->
                track.copy(clips = track.clips.filterNot { it.assetId == assetId })
            }
        ),
    )

    fun clipsUsing(doc: ProjectDocument, assetId: String): List<Clip> =
        doc.timeline.tracks.flatMap { it.clips }.filter { it.assetId == assetId }

    private fun decode(payload: String): ProjectDocument? = try {
        json.decodeFromString(ProjectDocument.serializer(), payload)
    } catch (_: Exception) {
        null
    }

    fun encode(doc: ProjectDocument): String = json.encodeToString(ProjectDocument.serializer(), doc)
}
