package com.oeditor.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Project store.
 *
 * The schema is deliberately the same shape the Room entities describe — one row per
 * project holding metadata plus the serialised document, and a recovery row per project.
 * It is implemented directly on SQLiteOpenHelper so the persistence layer has no
 * annotation-processing dependency and builds anywhere the Kotlin compiler runs.
 *
 * Writes are wrapped in transactions; reads never see a half-written document.
 */
data class ProjectRow(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val durationUs: Long,
    val schemaVersion: Int,
    val width: Int,
    val height: Int,
    val fpsNum: Int,
    val fpsDen: Int,
    val clipCount: Int,
    val documentJson: String,
)

/** Row without the document payload, for the project browser. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val fpsNum: Int,
    val fpsDen: Int,
    val clipCount: Int,
)

data class RecoveryRow(
    val projectId: String,
    val savedAt: Long,
    val documentJson: String,
)

class ProjectStore(context: Context) {

    private val helper = object : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE projects (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    duration_us INTEGER NOT NULL,
                    schema_version INTEGER NOT NULL,
                    width INTEGER NOT NULL,
                    height INTEGER NOT NULL,
                    fps_num INTEGER NOT NULL,
                    fps_den INTEGER NOT NULL,
                    clip_count INTEGER NOT NULL,
                    document_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE recovery_points (
                    project_id TEXT NOT NULL PRIMARY KEY,
                    saved_at INTEGER NOT NULL,
                    document_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_projects_updated ON projects(updated_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Version 1 is the first shipped schema. Additive migrations go here.
        }
    }

    /** Bumped whenever a write happens, so observers can re-query. */
    private val revision = MutableStateFlow(0L)

    fun notifyChanged() {
        revision.value = revision.value + 1
    }

    // ---------------------------------------------------------------- queries

    fun summaries(): List<ProjectSummary> {
        val out = ArrayList<ProjectSummary>()
        helper.readableDatabase.rawQuery(
            """
            SELECT id, name, created_at, updated_at, duration_us, width, height, fps_num, fps_den, clip_count
            FROM projects ORDER BY updated_at DESC
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    ProjectSummary(
                        id = cursor.getString(0),
                        name = cursor.getString(1),
                        createdAt = cursor.getLong(2),
                        updatedAt = cursor.getLong(3),
                        durationUs = cursor.getLong(4),
                        width = cursor.getInt(5),
                        height = cursor.getInt(6),
                        fpsNum = cursor.getInt(7),
                        fpsDen = cursor.getInt(8),
                        clipCount = cursor.getInt(9),
                    )
                )
            }
        }
        return out
    }

    fun observeSummaries(): Flow<List<ProjectSummary>> = flow {
        emitAll(revision.map { summaries() })
    }.flowOn(Dispatchers.IO) // never query SQLite on the main thread

    fun find(id: String): ProjectRow? {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM projects WHERE id = ?", arrayOf(id)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return ProjectRow(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                durationUs = cursor.getLong(cursor.getColumnIndexOrThrow("duration_us")),
                schemaVersion = cursor.getInt(cursor.getColumnIndexOrThrow("schema_version")),
                width = cursor.getInt(cursor.getColumnIndexOrThrow("width")),
                height = cursor.getInt(cursor.getColumnIndexOrThrow("height")),
                fpsNum = cursor.getInt(cursor.getColumnIndexOrThrow("fps_num")),
                fpsDen = cursor.getInt(cursor.getColumnIndexOrThrow("fps_den")),
                clipCount = cursor.getInt(cursor.getColumnIndexOrThrow("clip_count")),
                documentJson = cursor.getString(cursor.getColumnIndexOrThrow("document_json")),
            )
        }
    }

    fun count(): Int {
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM projects", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun recovery(projectId: String): RecoveryRow? {
        helper.readableDatabase.rawQuery(
            "SELECT project_id, saved_at, document_json FROM recovery_points WHERE project_id = ?",
            arrayOf(projectId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return RecoveryRow(cursor.getString(0), cursor.getLong(1), cursor.getString(2))
        }
    }

    // ---------------------------------------------------------------- writes

    /** One transaction: the project row and its recovery point move together. */
    fun save(row: ProjectRow, recovery: RecoveryRow) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("projects", null, row.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            db.insertWithOnConflict("recovery_points", null, recovery.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    fun rename(id: String, name: String, updatedAt: Long) {
        val values = ContentValues().apply {
            put("name", name)
            put("updated_at", updatedAt)
        }
        helper.writableDatabase.update("projects", values, "id = ?", arrayOf(id))
        notifyChanged()
    }

    fun delete(id: String) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("projects", "id = ?", arrayOf(id))
            db.delete("recovery_points", "project_id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    fun clearRecovery(projectId: String) {
        helper.writableDatabase.delete("recovery_points", "project_id = ?", arrayOf(projectId))
    }

    fun close() {
        helper.close()
    }

    private fun ProjectRow.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("name", name)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("duration_us", durationUs)
        put("schema_version", schemaVersion)
        put("width", width)
        put("height", height)
        put("fps_num", fpsNum)
        put("fps_den", fpsDen)
        put("clip_count", clipCount)
        put("document_json", documentJson)
    }

    private fun RecoveryRow.toValues(): ContentValues = ContentValues().apply {
        put("project_id", projectId)
        put("saved_at", savedAt)
        put("document_json", documentJson)
    }

    private companion object {
        const val DB_NAME = "oeditor.db"
        const val DB_VERSION = 1
    }
}

private inline fun <T> Cursor.use(block: (Cursor) -> T): T = try {
    block(this)
} finally {
    close()
}
