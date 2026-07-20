package com.maktabah.database

import com.maktabah.models.Annotation
import com.maktabah.models.AnnotationChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableSharedFlow
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

            val sqlDeleted = """
                CREATE TABLE IF NOT EXISTS deleted_records (
                    ckRecordId TEXT PRIMARY KEY
                );
            """
            db.prepare(sqlDeleted)?.use { it.step() }

            var tableCreated = false
            db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='pending_uploads'")?.use { checkStmt ->
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
            db.prepare(sql)?.use { stmt ->
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
                if (annotation.ckRecordId != null) stmt.bindText(15, annotation.ckRecordId) else stmt.bindNull(15)
                val lastMod =
                    if (fromSync && annotation.lastModified != null) {
                        annotation.lastModified
                    } else {
                        System.currentTimeMillis() / 1000
                    }
                stmt.bindLong(16, lastMod)

                if (stmt.step() == SQLiteDB.SQLITE_DONE) {
                    newId = annotation.id ?: db.lastInsertRowId()
                }
            }

            // If ON CONFLICT DO UPDATE happened, lastInsertRowId() might not reflect the updated row.
            if ((newId <= 0 || newId == annotation.id) && annotation.ckRecordId != null) {
                db.prepare("SELECT id FROM annotations_v2 WHERE ckRecordId = ?")?.use { stmt ->
                    stmt.bindText(1, annotation.ckRecordId)
                    if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                        newId = stmt.columnLong(0)
                    }
                }
            }

            if (annotation.ckRecordId != null) {
                if (fromSync) {
                    db.prepare("DELETE FROM pending_uploads WHERE ckRecordId = ?")?.use { stmt ->
                        stmt.bindText(1, annotation.ckRecordId)
                        stmt.step()
                    }
                } else {
                    db.prepare("INSERT OR IGNORE INTO pending_uploads (ckRecordId) VALUES (?)")?.use { stmt ->
                        stmt.bindText(1, annotation.ckRecordId)
                        stmt.step()
                    }
                }
            }
        }
        if (newId > 0L) {
            updates.tryEmit(AnnotationChange.Upsert(annotation.copy(id = newId), fromSync = fromSync))
        }
        return newId
    }

    suspend fun getAllAnnotations(): List<Annotation> {
        val list = mutableListOf<Annotation>()
        SQLiteDB(dbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
            val sql = "SELECT * FROM annotations_v2 ORDER BY createdAt DESC"
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
            db.prepare("SELECT * FROM annotations_v2 WHERE bkId = ? ORDER BY createdAt DESC")?.use { stmt ->
                stmt.bindInt(1, bkId)
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    yield()
                    list.add(mapRowToAnnotation(stmt))
                }
            }
        }
        return list
    }

    private fun mapRowToAnnotation(stmt: SQLiteStmt): Annotation =
        Annotation(
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
                db.prepare("INSERT OR IGNORE INTO deleted_records (ckRecordId) VALUES (?)")?.use { stmt ->
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
                db.prepare("DELETE FROM deleted_records WHERE ckRecordId = ?")?.use { stmt ->
                    for (id in ids) {
                        yield()
                        stmt.bindText(1, id)
                        stmt.step()
                        stmt.reset()
                        stmt.clearBindings()
                    }
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
            db.prepare("UPDATE annotations_v2 SET bkId = ? WHERE bkId = ?")?.use { stmt ->
                stmt.bindInt(1, newId)
                stmt.bindInt(2, oldId)
                stmt.step()
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
                db.prepare("DELETE FROM pending_uploads WHERE ckRecordId = ?")?.use { stmt ->
                    for (id in ckRecordIds) {
                        yield()
                        stmt.bindText(1, id)
                        stmt.step()
                        stmt.reset()
                        stmt.clearBindings()
                    }
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

    fun clearAll() {
        SQLiteDB(dbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READWRITE).use { db ->
            db.prepare("DELETE FROM annotations_v2")?.use { it.step() }
            db.prepare("DELETE FROM deleted_records")?.use { it.step() }
            db.prepare("DELETE FROM pending_uploads")?.use { it.step() }
        }
        updates.tryEmit(AnnotationChange.ReloadAll)
    }
}
