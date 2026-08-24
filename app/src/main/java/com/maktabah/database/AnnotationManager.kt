package com.maktabah.database

import com.maktabah.models.Annotation
import com.maktabah.models.AnnotationChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.yield
import java.io.File

class AnnotationManager(
    private val dbFile: File,
) {
    companion object {
        val updates = MutableSharedFlow<AnnotationChange>(extraBufferCapacity = 64)
    }

    init {
        setupDatabase()
    }

    private fun setupDatabase() {
        SQLiteDB(
            dbFile.absolutePath,
            SQLiteDB.SQLITE_OPEN_READWRITE or SQLiteDB.SQLITE_OPEN_CREATE,
        ).use { db ->
            db.prepare("PRAGMA journal_mode=WAL;")?.use { it.step() }
            db.prepare("PRAGMA busy_timeout=5000;")?.use { it.step() }
            val sql = """
                CREATE TABLE IF NOT EXISTS annotations_v2 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    bkId INTEGER NOT NULL,
                    contentId INTEGER NOT NULL,
                    color TEXT NOT NULL,
                    note TEXT,
                    type INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    page INTEGER NOT NULL,
                    context TEXT NOT NULL,
                    rangeLocation INTEGER NOT NULL DEFAULT 0,
                    rangeLength INTEGER NOT NULL DEFAULT 0,
                    rangeDiacLocation INTEGER NOT NULL DEFAULT 0,
                    rangeDiacLength INTEGER NOT NULL DEFAULT 0,
                    part INTEGER NOT NULL DEFAULT 0,
                    tags TEXT NOT NULL DEFAULT '',
                    ckRecordId TEXT UNIQUE,
                    lastModified INTEGER
                );
            """
            db.prepare(sql)?.use { it.step() }

            db.prepare("CREATE INDEX IF NOT EXISTS idx_annotations_bk_created ON annotations_v2 (bkId, createdAt DESC);")?.use { it.step() }
            db.prepare("CREATE INDEX IF NOT EXISTS idx_annotations_content ON annotations_v2 (bkId, contentId);")?.use { it.step() }
            db.prepare("CREATE INDEX IF NOT EXISTS idx_annotations_created ON annotations_v2 (createdAt DESC);")?.use { it.step() }

            val sqlDeleted = """
                CREATE TABLE IF NOT EXISTS deleted_records (
                    ckRecordId TEXT PRIMARY KEY
                );
            """
            db.prepare(sqlDeleted)?.use { it.step() }

            var tableCreated = false
            db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='pending_uploads'")
                ?.use { checkStmt ->
                    if (checkStmt.step() != SQLiteDB.SQLITE_ROW) {
                        tableCreated = true
                    }
                }

            if (tableCreated) {
                val sqlPending = """
                    CREATE TABLE IF NOT EXISTS pending_uploads (
                        ckRecordId TEXT PRIMARY KEY
                    );
                """
                db.prepare(sqlPending)?.use { it.step() }

                val sqlBackfill = """
                    INSERT OR IGNORE INTO pending_uploads (ckRecordId)
                    SELECT ckRecordId FROM annotations_v2 WHERE ckRecordId IS NOT NULL;
                """
                db.prepare(sqlBackfill)?.use { it.step() }
            }
        }
    }

    fun deleteByCkRecordId(ckRecordId: String) {
        var deletedId: Long? = null
        SQLiteDB(dbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READWRITE).use { db ->
            db.prepare("SELECT id FROM annotations_v2 WHERE ckRecordId = ?")?.use { stmt ->
                stmt.bindText(1, ckRecordId)
                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    deletedId = stmt.columnLong(0)
                }
            }
            val sql = "DELETE FROM annotations_v2 WHERE ckRecordId = ?"
            db.prepare(sql)?.use { stmt ->
                stmt.bindText(1, ckRecordId)
                stmt.step()
            }
        }
        deletedId?.let {
            updates.tryEmit(AnnotationChange.Delete(it, fromSync = true))
        }
    }

    fun insertOrUpdate(
        annotation: Annotation,
        fromSync: Boolean = false,
    ): Long {
        var newId: Long = -1
        SQLiteDB(dbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READWRITE).use { db ->
            newId = executeInsertOrUpdate(db, annotation, fromSync)
        }
        if (newId > 0L) {
            updates.tryEmit(
                AnnotationChange.Upsert(
                    annotation.copy(id = newId),
                    fromSync = fromSync
                )
            )
        }
        return newId
    }

    private data class AnnotationCompositeKey(
        val bkId: Int,
        val contentId: Int,
        val rangeLocation: Int,
        val rangeLength: Int,
    )

    private fun executeInsertOrUpdate(
        db: SQLiteDB,
        annotation: Annotation,
        fromSync: Boolean = false,
        insertStmt: SQLiteStmt? = null,
        selectIdStmt: SQLiteStmt? = null,
        pendingUploadStmt: SQLiteStmt? = null,
    ): Long {
        var newId: Long = -1
        val sql = """
            INSERT INTO annotations_v2 (
                bkId, contentId, color, note, type, createdAt, page, context,
                rangeLocation, rangeLength, rangeDiacLocation, rangeDiacLength,
                part, tags, ckRecordId, lastModified
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(ckRecordId) DO UPDATE SET
                bkId = excluded.bkId,
                contentId = excluded.contentId,
                color = excluded.color,
                note = excluded.note,
                type = excluded.type,
                createdAt = excluded.createdAt,
                page = excluded.page,
                context = excluded.context,
                rangeLocation = excluded.rangeLocation,
                rangeLength = excluded.rangeLength,
                rangeDiacLocation = excluded.rangeDiacLocation,
                rangeDiacLength = excluded.rangeDiacLength,
                part = excluded.part,
                tags = excluded.tags,
                lastModified = excluded.lastModified;
        """
        val stmt = insertStmt ?: db.prepare(sql)
        try {
            if (stmt != null) {
                stmt.bindInt(1, annotation.bkId)
                stmt.bindInt(2, annotation.contentId)
                stmt.bindText(3, annotation.colorHex)
                if (annotation.note != null) stmt.bindText(4, annotation.note) else stmt.bindNull(4)
                stmt.bindInt(5, annotation.type)
                stmt.bindLong(6, annotation.createdAt)
                stmt.bindInt(7, annotation.page)
                stmt.bindText(8, annotation.context)
                stmt.bindInt(9, annotation.rangeLocation)
                stmt.bindInt(10, annotation.rangeLength)
                stmt.bindInt(11, annotation.rangeDiacLocation)
                stmt.bindInt(12, annotation.rangeDiacLength)
                stmt.bindInt(13, annotation.part)
                stmt.bindText(14, annotation.tags)
                if (annotation.ckRecordId != null) stmt.bindText(
                    15,
                    annotation.ckRecordId
                ) else stmt.bindNull(15)
                val lastMod = if (fromSync && annotation.lastModified != null) {
                    annotation.lastModified
                } else {
                    System.currentTimeMillis() / 1000
                }
                stmt.bindLong(16, lastMod)

                if (stmt.step() == SQLiteDB.SQLITE_DONE) {
                    newId = annotation.id ?: db.lastInsertRowId()
                }
            }
        } finally {
            if (insertStmt != null) {
                stmt?.reset()
                stmt?.clearBindings()
            } else {
                stmt?.close()
            }
        }

        // If ON CONFLICT DO UPDATE happened, lastInsertRowId() might not reflect the updated row.
        if ((newId <= 0 || newId == annotation.id) && annotation.ckRecordId != null) {
            val selStmt = selectIdStmt ?: db.prepare("SELECT id FROM annotations_v2 WHERE ckRecordId = ?")
            try {
                if (selStmt != null) {
                    selStmt.bindText(1, annotation.ckRecordId)
                    if (selStmt.step() == SQLiteDB.SQLITE_ROW) {
                        newId = selStmt.columnLong(0)
                    }
                }
            } finally {
                if (selectIdStmt != null) {
                    selStmt?.reset()
                    selStmt?.clearBindings()
                } else {
                    selStmt?.close()
                }
            }
        }

        if (annotation.ckRecordId != null) {
            if (fromSync) {
                db.prepare("DELETE FROM pending_uploads WHERE ckRecordId = ?")?.use { stmtPending ->
                    stmtPending.bindText(1, annotation.ckRecordId)
                    stmtPending.step()
                }
            } else {
                val pendStmt = pendingUploadStmt ?: db.prepare("INSERT OR IGNORE INTO pending_uploads (ckRecordId) VALUES (?)")
                try {
                    if (pendStmt != null) {
                        pendStmt.bindText(1, annotation.ckRecordId)
                        pendStmt.step()
                    }
                } finally {
                    if (pendingUploadStmt != null) {
                        pendStmt?.reset()
                        pendStmt?.clearBindings()
                    } else {
                        pendStmt?.close()
                    }
                }
            }
        }

        return newId
    }

    suspend fun getAllAnnotations(): List<Annotation> {
        return getAnnotationsWithLimit(-1)
    }

    suspend fun getLatestAnnotations(limit: Int): List<Annotation> {
        return getAnnotationsWithLimit(limit)
    }

    private suspend fun getAnnotationsWithLimit(limit: Int): List<Annotation> {
        val list = mutableListOf<Annotation>()
        SQLiteDB(dbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
            val sql = if (limit > 0) {
                "SELECT * FROM annotations_v2 ORDER BY createdAt DESC LIMIT $limit"
            } else {
                "SELECT * FROM annotations_v2 ORDER BY createdAt DESC"
            }
            db.prepare(sql)?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    yield()
                    list.add(mapRowToAnnotation(stmt))
                }
            }
        }
        return list
    }

    suspend fun getAnnotationsForBook(bkId: Int): List<Annotation> {
        val list = mutableListOf<Annotation>()
        SQLiteDB(dbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
            db.prepare("SELECT * FROM annotations_v2 WHERE bkId = ? ORDER BY createdAt DESC")
                ?.use { stmt ->
                    stmt.bindInt(1, bkId)
                    while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                        yield()
                        list.add(mapRowToAnnotation(stmt))
                    }
                }
        }
        return list
    }

    private fun mapRowToAnnotation(stmt: SQLiteStmt): Annotation = Annotation(
        id = stmt.columnLong(0),
        bkId = stmt.columnInt(1),
        contentId = stmt.columnInt(2),
        colorHex = stmt.columnText(3) ?: "",
        note = stmt.columnText(4),
        type = stmt.columnInt(5),
        createdAt = stmt.columnLong(6),
        page = stmt.columnInt(7),
        context = stmt.columnText(8) ?: "",
        rangeLocation = stmt.columnInt(9),
        rangeLength = stmt.columnInt(10),
        rangeDiacLocation = stmt.columnInt(11),
        rangeDiacLength = stmt.columnInt(12),
        part = stmt.columnInt(13),
        tags = stmt.columnText(14) ?: "",
        ckRecordId = stmt.columnText(15),
        lastModified = if (stmt.columnType(16) != SQLiteDB.SQLITE_NULL) stmt.columnLong(16) else null,
    )

    fun deleteAnnotation(
        id: Long,
        ckRecordId: String?,
    ) {
        SQLiteDB(dbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READWRITE).use { db ->
            db.prepare("DELETE FROM annotations_v2 WHERE id = ?")?.use { stmt ->
                stmt.bindLong(1, id)
                stmt.step()
            }
            if (ckRecordId != null) {
                db.prepare("INSERT OR IGNORE INTO deleted_records (ckRecordId) VALUES (?)")
                    ?.use { stmt ->
                        stmt.bindText(1, ckRecordId)
                        stmt.step()
                    }
                db.prepare("DELETE FROM pending_uploads WHERE ckRecordId = ?")?.use { stmt ->
                    stmt.bindText(1, ckRecordId)
                    stmt.step()
                }
            }
        }
        updates.tryEmit(AnnotationChange.Delete(id, fromSync = false))
    }

    suspend fun getDeletedRecordIds(): List<String> {
        val list = mutableListOf<String>()
        SQLiteDB(dbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
            db.prepare("SELECT ckRecordId FROM deleted_records")?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    yield()
                    list.add(stmt.columnText(0) ?: "")
                }
            }
        }
        return list
    }

    suspend fun clearDeletedRecordIds(ids: List<String>) {
        if (ids.isEmpty()) return
        SQLiteDB(dbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READWRITE).use { db ->
            db.prepare("BEGIN TRANSACTION")?.use { it.step() }
            try {
                ids.chunked(900).forEach { chunk ->
                    val placeholders = chunk.joinToString(",") { "?" }
                    db.prepare("DELETE FROM deleted_records WHERE ckRecordId IN ($placeholders)")?.use { stmt ->
                        chunk.forEachIndexed { index, id ->
                            stmt.bindText(index + 1, id)
                        }
                        stmt.step()
                    }
                    yield()
                }
                db.prepare("COMMIT")?.use { it.step() }
            } catch (e: CancellationException) {
                db.prepare("ROLLBACK")?.use { it.step() }
                throw e
            } catch (e: Exception) {
                db.prepare("ROLLBACK")?.use { it.step() }
                throw e
            }
        }
    }

    fun migrateBookId(
        oldId: Int,
        newId: Int,
    ) {
        SQLiteDB(dbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READWRITE).use { db ->
            val ckIds = mutableListOf<String>()
            db.prepare("SELECT ckRecordId FROM annotations_v2 WHERE bkId = ? AND ckRecordId IS NOT NULL")
                ?.use { stmt ->
                    stmt.bindInt(1, oldId)
                    while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                        stmt.columnText(0)?.let { ckIds.add(it) }
                    }
                }

            db.prepare("UPDATE annotations_v2 SET bkId = ?, lastModified = ? WHERE bkId = ?")
                ?.use { stmt ->
                    stmt.bindInt(1, newId)
                    stmt.bindLong(2, System.currentTimeMillis() / 1000L)
                    stmt.bindInt(3, oldId)
                    stmt.step()
                }

            if (ckIds.isNotEmpty()) {
                db.prepare("BEGIN TRANSACTION;")?.use { it.step() }
                try {
                    for (chunk in ckIds.chunked(300)) {
                        val placeholders = chunk.joinToString(",") { "(?)" }
                        db.prepare("INSERT OR IGNORE INTO pending_uploads (ckRecordId) VALUES $placeholders;")?.use { stmt ->
                            chunk.forEachIndexed { index, ckId ->
                                stmt.bindText(index + 1, ckId)
                            }
                            stmt.step()
                        }
                    }
                    db.prepare("COMMIT;")?.use { it.step() }
                } catch (e: Exception) {
                    db.prepare("ROLLBACK;")?.use { it.step() }
                    e.printStackTrace()
                }
            }
        }
        updates.tryEmit(AnnotationChange.ReloadAll)
    }

    suspend fun getUnsyncedAnnotations(): List<Annotation> = getPendingUploads()

    suspend fun getPendingUploads(): List<Annotation> {
        val list = mutableListOf<Annotation>()
        SQLiteDB(dbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
            val sql = """
                SELECT a.* FROM annotations_v2 a
                INNER JOIN pending_uploads p ON a.ckRecordId = p.ckRecordId
            """
            db.prepare(sql)?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    yield()
                    list.add(mapRowToAnnotation(stmt))
                }
            }
        }
        return list
    }

    suspend fun clearPendingUploads(ckRecordIds: List<String>) {
        if (ckRecordIds.isEmpty()) return
        SQLiteDB(dbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READWRITE).use { db ->
            db.prepare("BEGIN TRANSACTION")?.use { it.step() }
            try {
                ckRecordIds.chunked(900).forEach { chunk ->
                    val placeholders = chunk.joinToString(",") { "?" }
                    db.prepare("DELETE FROM pending_uploads WHERE ckRecordId IN ($placeholders)")?.use { stmt ->
                        chunk.forEachIndexed { index, id ->
                            stmt.bindText(index + 1, id)
                        }
                        stmt.step()
                    }
                    yield()
                }
                db.prepare("COMMIT")?.use { it.step() }
            } catch (e: CancellationException) {
                db.prepare("ROLLBACK")?.use { it.step() }
                throw e
            } catch (e: Exception) {
                db.prepare("ROLLBACK")?.use { it.step() }
                throw e
            }
        }
    }

    private fun existsAnnotation(db: SQLiteDB, ann: Annotation): Boolean {
        if (ann.ckRecordId != null) {
            db.prepare("SELECT 1 FROM annotations_v2 WHERE ckRecordId = ?")?.use { stmt ->
                stmt.bindText(1, ann.ckRecordId)
                if (stmt.step() == SQLiteDB.SQLITE_ROW) return true
            }
        }
        val sql =
            "SELECT 1 FROM annotations_v2 WHERE bkId = ? AND contentId = ? AND rangeLocation = ? AND rangeLength = ?"
        db.prepare(sql)?.use { stmt ->
            stmt.bindInt(1, ann.bkId)
            stmt.bindInt(2, ann.contentId)
            stmt.bindInt(3, ann.rangeLocation)
            stmt.bindInt(4, ann.rangeLength)
            if (stmt.step() == SQLiteDB.SQLITE_ROW) return true
        }
        return false
    }

    fun importAnnotations(
        annotations: List<Annotation>,
        overwrite: Boolean = true,
    ): Int {
        var count = 0
        synchronized(this) {
            SQLiteDB(dbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READWRITE).use { db ->
                val existingCkRecordIds = HashSet<String>()
                val existingCompositeKeys = HashSet<AnnotationCompositeKey>()

                if (!overwrite && annotations.isNotEmpty()) {
                    val ckRecordIds = annotations.mapNotNull { it.ckRecordId }.distinct()
                    for (chunk in ckRecordIds.chunked(900)) {
                        val placeholders = chunk.joinToString(",") { "?" }
                        val sql = "SELECT ckRecordId FROM annotations_v2 WHERE ckRecordId IN ($placeholders)"
                        db.prepare(sql)?.use { stmt ->
                            chunk.forEachIndexed { index, id ->
                                stmt.bindText(index + 1, id)
                            }
                            while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                                stmt.columnText(0)?.let { existingCkRecordIds.add(it) }
                            }
                        }
                    }

                    val bkIds = annotations.map { it.bkId }.distinct()
                    for (chunk in bkIds.chunked(900)) {
                        val placeholders = chunk.joinToString(",") { "?" }
                        val sql = "SELECT bkId, contentId, rangeLocation, rangeLength FROM annotations_v2 WHERE bkId IN ($placeholders)"
                        db.prepare(sql)?.use { stmt ->
                            chunk.forEachIndexed { index, bkId ->
                                stmt.bindInt(index + 1, bkId)
                            }
                            while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                                existingCompositeKeys.add(
                                    AnnotationCompositeKey(
                                        bkId = stmt.columnInt(0),
                                        contentId = stmt.columnInt(1),
                                        rangeLocation = stmt.columnInt(2),
                                        rangeLength = stmt.columnInt(3),
                                    )
                                )
                            }
                        }
                    }
                }

                db.prepare("BEGIN TRANSACTION")?.use { it.step() }
                try {
                    val insertSql = """
                        INSERT INTO annotations_v2 (
                            bkId, contentId, color, note, type, createdAt, page, context,
                            rangeLocation, rangeLength, rangeDiacLocation, rangeDiacLength,
                            part, tags, ckRecordId, lastModified
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(ckRecordId) DO UPDATE SET
                            bkId = excluded.bkId,
                            contentId = excluded.contentId,
                            color = excluded.color,
                            note = excluded.note,
                            type = excluded.type,
                            createdAt = excluded.createdAt,
                            page = excluded.page,
                            context = excluded.context,
                            rangeLocation = excluded.rangeLocation,
                            rangeLength = excluded.rangeLength,
                            rangeDiacLocation = excluded.rangeDiacLocation,
                            rangeDiacLength = excluded.rangeDiacLength,
                            part = excluded.part,
                            tags = excluded.tags,
                            lastModified = excluded.lastModified;
                    """
                    val selectIdSql = "SELECT id FROM annotations_v2 WHERE ckRecordId = ?"
                    val pendingUploadSql = "INSERT OR IGNORE INTO pending_uploads (ckRecordId) VALUES (?)"

                    val insertStmt = db.prepare(insertSql)
                    val selectIdStmt = db.prepare(selectIdSql)
                    val pendingUploadStmt = db.prepare(pendingUploadSql)

                    try {
                        for (ann in annotations) {
                            if (!overwrite) {
                                val exists = (ann.ckRecordId != null && existingCkRecordIds.contains(ann.ckRecordId)) ||
                                        existingCompositeKeys.contains(
                                            AnnotationCompositeKey(
                                                bkId = ann.bkId,
                                                contentId = ann.contentId,
                                                rangeLocation = ann.rangeLocation,
                                                rangeLength = ann.rangeLength,
                                            )
                                        )
                                if (exists) {
                                    continue
                                }
                            }
                            val recordId = ann.ckRecordId ?: java.util.UUID.randomUUID().toString()
                            val annToSave = ann.copy(ckRecordId = recordId)
                            val newId = executeInsertOrUpdate(
                                db,
                                annToSave,
                                fromSync = false,
                                insertStmt = insertStmt,
                                selectIdStmt = selectIdStmt,
                                pendingUploadStmt = pendingUploadStmt,
                            )
                            if (newId > 0L) {
                                count++
                                if (!overwrite) {
                                    existingCkRecordIds.add(recordId)
                                    existingCompositeKeys.add(
                                        AnnotationCompositeKey(
                                            bkId = annToSave.bkId,
                                            contentId = annToSave.contentId,
                                            rangeLocation = annToSave.rangeLocation,
                                            rangeLength = annToSave.rangeLength,
                                        )
                                    )
                                }
                            }
                        }
                    } finally {
                        insertStmt?.close()
                        selectIdStmt?.close()
                        pendingUploadStmt?.close()
                    }

                    db.prepare("COMMIT")?.use { it.step() }
                } catch (e: Exception) {
                    db.prepare("ROLLBACK")?.use { it.step() }
                    throw e
                }
            }
        }
        if (count > 0) {
            updates.tryEmit(AnnotationChange.ReloadAll)
        }
        return count
    }

    fun clearAll() {
        SQLiteDB(dbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READWRITE).use { db ->
            db.prepare("DELETE FROM annotations_v2")?.use { it.step() }
            db.prepare("DELETE FROM deleted_records")?.use { it.step() }
            db.prepare("DELETE FROM pending_uploads")?.use { it.step() }
        }
        updates.tryEmit(AnnotationChange.ReloadAll)
    }
}
