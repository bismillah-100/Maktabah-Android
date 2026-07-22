package com.maktabah.database

import com.maktabah.models.FolderNode
import com.maktabah.models.SyncFolder
import com.maktabah.models.SyncResult
import java.io.File
import java.util.UUID

/**
 * Port dari iOS ResultsHandler.swift.
 * Database layer untuk SearchResults.sqlite menggunakan JNI SQLiteDB.
 */
class ResultsHandler(private val dbFile: File) {

    init {
        setupDatabase()
    }

    // region Setup & Tables

    private fun setupDatabase() {
        SQLiteDB(
            dbFile.absolutePath,
            SQLiteDB.SQLITE_OPEN_READWRITE or SQLiteDB.SQLITE_OPEN_CREATE or SQLiteDB.SQLITE_OPEN_FULLMUTEX,
        ).use { db ->
            db.prepare("PRAGMA journal_mode = WAL;")?.use { it.step() }

            db.prepare(
                """
                CREATE TABLE IF NOT EXISTS folders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT,
                    parent INTEGER,
                    ckRecordId TEXT,
                    lastModified INTEGER,
                    parentCkRecordId TEXT,
                    UNIQUE(name, parent)
                );
                """,
            )?.use { it.step() }

            db.prepare(
                """
                CREATE TABLE IF NOT EXISTS results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    folder_id INTEGER,
                    name TEXT,
                    query TEXT,
                    archives INTEGER,
                    bkId INTEGER,
                    contentId TEXT,
                    ckRecordId TEXT,
                    lastModified INTEGER,
                    folderCkRecordId TEXT,
                    UNIQUE(folder_id, name, bkId)
                );
                """,
            )?.use { it.step() }

            db.prepare(
                """
                CREATE TABLE IF NOT EXISTS sync_pending (
                    ck_record_id TEXT PRIMARY KEY,
                    operation TEXT NOT NULL CHECK(operation IN ('upload', 'delete')),
                    queued_at INTEGER NOT NULL
                );
                """,
            )?.use { it.step() }

            db.prepare("CREATE INDEX IF NOT EXISTS idx_sync_pending_ck_record_id ON sync_pending (ck_record_id);")
                ?.use { it.step() }
            db.prepare("CREATE INDEX IF NOT EXISTS idx_sync_pending_op_queued ON sync_pending (operation, queued_at);")
                ?.use { it.step() }
            db.prepare("CREATE UNIQUE INDEX IF NOT EXISTS idx_folders_parent_name ON folders (COALESCE(parent, 0), name);")
                ?.use { it.step() }
            db.prepare("CREATE UNIQUE INDEX IF NOT EXISTS idx_results_folder_name_bk ON results (COALESCE(folder_id, 0), name, bkId);")
                ?.use { it.step() }
        }

        resolveOrphanFolders()
        resolveOrphanResults()
    }

    // endregion

    // region Folder CRUD

    fun insertRootFolder(name: String): Long? {
        val ckId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis() / 1000L
        var rowId: Long? = null
        openDb { db ->
            db.prepare("INSERT INTO folders (name, parent, ckRecordId, lastModified) VALUES (?, NULL, ?, ?);")
                ?.use { stmt ->
                    stmt.bindText(1, name)
                    stmt.bindText(2, ckId)
                    stmt.bindLong(3, now)
                    stmt.step()
                }
            rowId = db.lastInsertRowId()
        }
        return rowId
    }

    fun insertSubFolder(parentId: Long, name: String): Long? {
        val ckId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis() / 1000L
        var rowId: Long? = null
        openDb { db ->
            val pCkId = fetchFolderCkRecordId(db, parentId)
            db.prepare("INSERT INTO folders (name, parent, ckRecordId, lastModified, parentCkRecordId) VALUES (?, ?, ?, ?, ?);")
                ?.use { stmt ->
                    stmt.bindText(1, name)
                    stmt.bindLong(2, parentId)
                    stmt.bindText(3, ckId)
                    stmt.bindLong(4, now)
                    if (pCkId != null) stmt.bindText(5, pCkId) else stmt.bindNull(5)
                    stmt.step()
                }
            rowId = db.lastInsertRowId()
        }
        return rowId
    }

    fun fetchFolderTree(): List<FolderNode> {
        val nodes = mutableMapOf<Long, FolderNode>()
        val parentMap = mutableMapOf<Long, Long?>()
        val roots = mutableListOf<FolderNode>()

        openDb { db ->
            db.prepare("SELECT id, name, parent FROM folders")?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    val id = stmt.columnLong(0)
                    val name = stmt.columnText(1) ?: ""
                    val parent = if (stmt.columnType(2) != SQLiteDB.SQLITE_NULL) stmt.columnLong(2) else null
                    nodes[id] = FolderNode(id, name)
                    parentMap[id] = parent
                }
            }
        }

        for ((id, parent) in parentMap) {
            val node = nodes[id] ?: continue
            if (parent != null && nodes.containsKey(parent)) {
                nodes[parent]!!.children.add(node)
            } else {
                roots.add(node)
            }
        }

        return roots
    }

    fun updateFolderName(folderId: Long, newName: String) {
        val now = System.currentTimeMillis() / 1000L
        openDb { db ->
            db.prepare("UPDATE folders SET name = ?, lastModified = ? WHERE id = ?;")?.use { stmt ->
                stmt.bindText(1, newName)
                stmt.bindLong(2, now)
                stmt.bindLong(3, folderId)
                stmt.step()
            }
        }
    }

    fun updateParent(folderId: Long, newParentId: Long?) {
        val now = System.currentTimeMillis() / 1000L
        openDb { db ->
            val pCkId = if (newParentId != null) fetchFolderCkRecordId(db, newParentId) else null
            db.prepare("UPDATE folders SET parent = ?, lastModified = ?, parentCkRecordId = ? WHERE id = ?;")
                ?.use { stmt ->
                    if (newParentId != null) stmt.bindLong(1, newParentId) else stmt.bindNull(1)
                    stmt.bindLong(2, now)
                    if (pCkId != null) stmt.bindText(3, pCkId) else stmt.bindNull(3)
                    stmt.bindLong(4, folderId)
                    stmt.step()
                }
        }
    }

    fun deleteFolder(folderId: Long) {
        val allIds = getAllDescendantIds(folderId)
        val ckIdsToDelete = mutableListOf<String>()

        openDb { db ->
            for (fId in allIds) {
                db.prepare("SELECT ckRecordId FROM folders WHERE id = ? LIMIT 1")?.use { stmt ->
                    stmt.bindLong(1, fId)
                    if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                        stmt.columnText(0)?.let { ckIdsToDelete.add(it) }
                    }
                }
                db.prepare("SELECT ckRecordId FROM results WHERE folder_id = ?")?.use { stmt ->
                    stmt.bindLong(1, fId)
                    while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                        stmt.columnText(0)?.let { ckIdsToDelete.add(it) }
                    }
                }
            }

            db.prepare("BEGIN TRANSACTION;")?.use { it.step() }
            try {
                for (id in allIds) {
                    db.prepare("DELETE FROM results WHERE folder_id = ?;")?.use { stmt ->
                        stmt.bindLong(1, id)
                        stmt.step()
                    }
                }
                for (id in allIds.reversed()) {
                    db.prepare("DELETE FROM folders WHERE id = ?;")?.use { stmt ->
                        stmt.bindLong(1, id)
                        stmt.step()
                    }
                }
                db.prepare("COMMIT;")?.use { it.step() }
            } catch (e: Exception) {
                db.prepare("ROLLBACK;")?.use { it.step() }
                throw e
            }
        }
    }

    fun getAllDescendantIds(folderId: Long): List<Long> {
        val ids = mutableListOf(folderId)
        openDb { db ->
            fun recurse(parentId: Long) {
                db.prepare("SELECT id FROM folders WHERE parent = ?")?.use { stmt ->
                    stmt.bindLong(1, parentId)
                    val children = mutableListOf<Long>()
                    while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                        children.add(stmt.columnLong(0))
                    }
                    for (childId in children) {
                        ids.add(childId)
                        recurse(childId)
                    }
                }
            }
            recurse(folderId)
        }
        return ids
    }

    // endregion

    // region Result CRUD

    fun insertResult(archive: Int, bkId: Int, contentId: String, folderId: Long?, query: String, name: String) {
        val ckId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis() / 1000L
        openDb { db ->
            val fCkId = if (folderId != null) fetchFolderCkRecordId(db, folderId) else null
            db.prepare(
                """
                INSERT INTO results (folder_id, name, query, archives, bkId, contentId, ckRecordId, lastModified, folderCkRecordId)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
                """,
            )?.use { stmt ->
                if (folderId != null) stmt.bindLong(1, folderId) else stmt.bindNull(1)
                stmt.bindText(2, name)
                stmt.bindText(3, query)
                stmt.bindInt(4, archive)
                stmt.bindInt(5, bkId)
                stmt.bindText(6, contentId)
                stmt.bindText(7, ckId)
                stmt.bindLong(8, now)
                if (fCkId != null) stmt.bindText(9, fCkId) else stmt.bindNull(9)
                stmt.step()
            }
        }
    }

    /**
     * Fetches results for a given folder, grouped by result name into ResultNode-like raw data.
     * Returns List of (id, parentId, name, query, archive, bkId, contentId).
     */
    fun fetchResultsRaw(folderId: Long?): List<RawResult> {
        val results = mutableListOf<RawResult>()
        openDb { db ->
            val sql = if (folderId != null) {
                "SELECT id, folder_id, name, query, archives, bkId, contentId FROM results WHERE folder_id = ?"
            } else {
                "SELECT id, folder_id, name, query, archives, bkId, contentId FROM results WHERE folder_id IS NULL"
            }
            db.prepare(sql)?.use { stmt ->
                if (folderId != null) stmt.bindLong(1, folderId)
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    results.add(
                        RawResult(
                            id = stmt.columnLong(0),
                            folderId = if (stmt.columnType(1) != SQLiteDB.SQLITE_NULL) stmt.columnLong(1) else null,
                            name = stmt.columnText(2) ?: "",
                            query = stmt.columnText(3) ?: "",
                            archive = stmt.columnInt(4),
                            bkId = stmt.columnInt(5),
                            contentId = stmt.columnText(6) ?: "",
                        ),
                    )
                }
            }
        }
        return results
    }

    fun updateResultQueryName(folderId: Long?, oldName: String, newName: String) {
        val now = System.currentTimeMillis() / 1000L
        openDb { db ->
            val sql = if (folderId != null) {
                "UPDATE results SET name = ?, lastModified = ? WHERE folder_id = ? AND name = ?;"
            } else {
                "UPDATE results SET name = ?, lastModified = ? WHERE folder_id IS NULL AND name = ?;"
            }
            db.prepare(sql)?.use { stmt ->
                stmt.bindText(1, newName)
                stmt.bindLong(2, now)
                if (folderId != null) {
                    stmt.bindLong(3, folderId)
                    stmt.bindText(4, oldName)
                } else {
                    stmt.bindText(3, oldName)
                }
                stmt.step()
            }
        }
    }

    fun updateResultParent(newParentId: Long?, oldParent: Long?, name: String) {
        val now = System.currentTimeMillis() / 1000L
        openDb { db ->
            val fCkId = if (newParentId != null) fetchFolderCkRecordId(db, newParentId) else null
            val sql = if (oldParent != null) {
                "UPDATE results SET folder_id = ?, lastModified = ?, folderCkRecordId = ? WHERE folder_id = ? AND name = ?;"
            } else {
                "UPDATE results SET folder_id = ?, lastModified = ?, folderCkRecordId = ? WHERE folder_id IS NULL AND name = ?;"
            }
            db.prepare(sql)?.use { stmt ->
                if (newParentId != null) stmt.bindLong(1, newParentId) else stmt.bindNull(1)
                stmt.bindLong(2, now)
                if (fCkId != null) stmt.bindText(3, fCkId) else stmt.bindNull(3)
                if (oldParent != null) {
                    stmt.bindLong(4, oldParent)
                    stmt.bindText(5, name)
                } else {
                    stmt.bindText(4, name)
                }
                stmt.step()
            }
        }
    }

    fun deleteResult(folderId: Long?, name: String) {
        openDb { db ->
            val sql = if (folderId != null) {
                "DELETE FROM results WHERE folder_id = ? AND name = ?;"
            } else {
                "DELETE FROM results WHERE folder_id IS NULL AND name = ?;"
            }
            db.prepare(sql)?.use { stmt ->
                if (folderId != null) {
                    stmt.bindLong(1, folderId)
                    stmt.bindText(2, name)
                } else {
                    stmt.bindText(1, name)
                }
                stmt.step()
            }
        }
    }

    // endregion

    // region Sync helpers

    fun fetchAllSyncFolders(): List<SyncFolder> {
        val list = mutableListOf<SyncFolder>()
        openDb { db ->
            db.prepare("SELECT * FROM folders")?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    list.add(readSyncFolder(stmt))
                }
            }
        }
        return list
    }

    fun fetchAllSyncResults(): List<SyncResult> {
        val list = mutableListOf<SyncResult>()
        openDb { db ->
            db.prepare("SELECT * FROM results")?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    list.add(readSyncResult(stmt))
                }
            }
        }
        return list
    }

    fun fetchFolderSyncData(folderId: Long): SyncFolder? {
        var result: SyncFolder? = null
        openDb { db ->
            db.prepare("SELECT * FROM folders WHERE id = ? LIMIT 1")?.use { stmt ->
                stmt.bindLong(1, folderId)
                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    result = readSyncFolder(stmt)
                }
            }
        }
        return result
    }

    fun fetchResultSyncData(resultId: Long): SyncResult? {
        var result: SyncResult? = null
        openDb { db ->
            db.prepare("SELECT * FROM results WHERE id = ? LIMIT 1")?.use { stmt ->
                stmt.bindLong(1, resultId)
                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    result = readSyncResult(stmt)
                }
            }
        }
        return result
    }

    fun fetchResultsSyncDataByFolder(folderId: Long?, name: String): List<SyncResult> {
        val list = mutableListOf<SyncResult>()
        openDb { db ->
            val sql = if (folderId != null) {
                "SELECT * FROM results WHERE folder_id = ? AND name = ?"
            } else {
                "SELECT * FROM results WHERE folder_id IS NULL AND name = ?"
            }
            db.prepare(sql)?.use { stmt ->
                if (folderId != null) {
                    stmt.bindLong(1, folderId)
                    stmt.bindText(2, name)
                } else {
                    stmt.bindText(1, name)
                }
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    list.add(readSyncResult(stmt))
                }
            }
        }
        return list
    }

    fun fetchCkRecordIdsForResults(folderId: Long?, name: String): List<String> {
        val ids = mutableListOf<String>()
        openDb { db ->
            val sql = if (folderId != null) {
                "SELECT ckRecordId FROM results WHERE folder_id = ? AND name = ?"
            } else {
                "SELECT ckRecordId FROM results WHERE folder_id IS NULL AND name = ?"
            }
            db.prepare(sql)?.use { stmt ->
                if (folderId != null) {
                    stmt.bindLong(1, folderId)
                    stmt.bindText(2, name)
                } else {
                    stmt.bindText(1, name)
                }
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    stmt.columnText(0)?.let { ids.add(it) }
                }
            }
        }
        return ids
    }

    fun addPendingSync(ckRecordId: String, operation: String) {
        openDb { db ->
            if (operation == "upload") {
                var hasDelete = false
                db.prepare("SELECT COUNT(*) FROM sync_pending WHERE ck_record_id = ? AND operation = 'delete';")
                    ?.use { stmt ->
                        stmt.bindText(1, ckRecordId)
                        if (stmt.step() == SQLiteDB.SQLITE_ROW && stmt.columnLong(0) > 0) hasDelete = true
                    }
                if (hasDelete) return@openDb
            } else if (operation == "delete") {
                db.prepare("DELETE FROM sync_pending WHERE ck_record_id = ? AND operation = 'upload';")
                    ?.use { stmt ->
                        stmt.bindText(1, ckRecordId)
                        stmt.step()
                    }
            }
            val now = System.currentTimeMillis() / 1000L
            db.prepare("INSERT OR REPLACE INTO sync_pending (ck_record_id, operation, queued_at) VALUES (?, ?, ?);")
                ?.use { stmt ->
                    stmt.bindText(1, ckRecordId)
                    stmt.bindText(2, operation)
                    stmt.bindLong(3, now)
                    stmt.step()
                }
        }
    }

    fun removePendingSync(ckRecordIds: List<String>) {
        if (ckRecordIds.isEmpty()) return
        openDb { db ->
            val placeholders = ckRecordIds.joinToString(",") { "?" }
            db.prepare("DELETE FROM sync_pending WHERE ck_record_id IN ($placeholders);")?.use { stmt ->
                ckRecordIds.forEachIndexed { i, id -> stmt.bindText(i + 1, id) }
                stmt.step()
            }
        }
    }

    fun fetchPendingSync(operation: String): List<String> {
        val ids = mutableListOf<String>()
        openDb { db ->
            db.prepare("SELECT ck_record_id FROM sync_pending WHERE operation = ? ORDER BY queued_at ASC;")
                ?.use { stmt ->
                    stmt.bindText(1, operation)
                    while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                        stmt.columnText(0)?.let { ids.add(it) }
                    }
                }
        }
        return ids
    }

    fun nukeDatabase() {
        openDb { db ->
            db.prepare("BEGIN TRANSACTION;")?.use { it.step() }
            try {
                db.prepare("DELETE FROM results;")?.use { it.step() }
                db.prepare("DELETE FROM folders;")?.use { it.step() }
                db.prepare("DELETE FROM sync_pending;")?.use { it.step() }
                db.prepare("COMMIT;")?.use { it.step() }
            } catch (e: Exception) {
                db.prepare("ROLLBACK;")?.use { it.step() }
            }
        }
    }

    // endregion

    // region CloudKit Apply (Sync Pull)

    fun applyCloudKitFolderChanges(foldersToSave: List<SyncFolder>, recordIdsToDelete: List<String>): Boolean {
        try {
            openDb { db ->
                db.prepare("BEGIN TRANSACTION;")?.use { it.step() }
                try {
                    // 1. Deletions
                    for (ckId in recordIdsToDelete) {
                        var localId: Long? = null
                        db.prepare("SELECT id FROM folders WHERE ckRecordId = ? LIMIT 1")?.use { stmt ->
                            stmt.bindText(1, ckId)
                            if (stmt.step() == SQLiteDB.SQLITE_ROW) localId = stmt.columnLong(0)
                        }
                        if (localId != null) {
                            val allIds = getAllDescendantIds(localId)
                            for (fId in allIds) {
                                db.prepare("DELETE FROM results WHERE folder_id = ?;")?.use { stmt ->
                                    stmt.bindLong(1, fId); stmt.step()
                                }
                                db.prepare("DELETE FROM folders WHERE id = ?;")?.use { stmt ->
                                    stmt.bindLong(1, fId); stmt.step()
                                }
                            }
                        }
                    }

                    // 2. Topological sort
                    val sortedFolders = topologicalSort(foldersToSave)

                    // 3. Upserts
                    for (folder in sortedFolders) {
                        val ckId = folder.ckRecordId ?: continue
                        val pLocalId = resolveParentLocalId(db, folder.parentCkRecordId)

                        var existingId: Long = -1
                        var localLastMod: Long = 0
                        var existingParentId: Long? = null

                        db.prepare("SELECT id, lastModified, parent FROM folders WHERE ckRecordId = ? LIMIT 1")
                            ?.use { stmt ->
                                stmt.bindText(1, ckId)
                                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                                    existingId = stmt.columnLong(0)
                                    localLastMod = stmt.columnLong(1)
                                    existingParentId =
                                        if (stmt.columnType(2) != SQLiteDB.SQLITE_NULL) stmt.columnLong(2) else null
                                }
                            }

                        if (existingId != -1L) {
                            val remoteLastMod = folder.lastModified ?: 0
                            if (remoteLastMod >= localLastMod) {
                                val isOrphan = folder.parentCkRecordId != null && pLocalId == null
                                val newParent = if (isOrphan) existingParentId else pLocalId
                                resolveConflictAndUpdate(db, existingId, folder, newParent, isOrphan)
                            }
                        } else {
                            val isOrphan = folder.parentCkRecordId != null && pLocalId == null
                            insertOrMergeFolder(db, folder, ckId, pLocalId, isOrphan)
                        }
                    }

                    db.prepare("COMMIT;")?.use { it.step() }
                } catch (e: Exception) {
                    db.prepare("ROLLBACK;")?.use { it.step() }
                    throw e
                }
            }
            resolveOrphanFolders()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun applyCloudKitResultChanges(resultsToSave: List<SyncResult>, recordIdsToDelete: List<String>): Boolean {
        try {
            openDb { db ->
                db.prepare("BEGIN TRANSACTION;")?.use { it.step() }
                try {
                    // 1. Deletions
                    for (ckId in recordIdsToDelete) {
                        db.prepare("DELETE FROM results WHERE ckRecordId = ?;")?.use { stmt ->
                            stmt.bindText(1, ckId); stmt.step()
                        }
                    }

                    // 2. Upserts
                    for (res in resultsToSave) {
                        val ckId = res.ckRecordId ?: continue
                        val fLocalId = resolveFolderLocalId(db, res.folderCkRecordId)

                        var existingId: Long = -1
                        var localLastMod: Long = 0
                        var existingFolderId: Long? = null

                        db.prepare("SELECT id, lastModified, folder_id FROM results WHERE ckRecordId = ? LIMIT 1")
                            ?.use { stmt ->
                                stmt.bindText(1, ckId)
                                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                                    existingId = stmt.columnLong(0)
                                    localLastMod = stmt.columnLong(1)
                                    existingFolderId =
                                        if (stmt.columnType(2) != SQLiteDB.SQLITE_NULL) stmt.columnLong(2) else null
                                }
                            }

                        if (existingId != -1L) {
                            val remoteLastMod = res.lastModified ?: 0
                            if (remoteLastMod >= localLastMod) {
                                val isOrphan = res.folderCkRecordId != null && fLocalId == null
                                val newFolder = if (isOrphan) existingFolderId else fLocalId
                                updateResultFromSync(db, existingId, res, newFolder, isOrphan)
                            }
                        } else {
                            val isOrphan = res.folderCkRecordId != null && fLocalId == null
                            insertOrMergeResult(db, res, ckId, fLocalId, isOrphan)
                        }
                    }

                    db.prepare("COMMIT;")?.use { it.step() }
                } catch (e: Exception) {
                    db.prepare("ROLLBACK;")?.use { it.step() }
                    throw e
                }
            }
            resolveOrphanResults()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // endregion

    // region Orphan Resolution

    fun resolveOrphanFolders() {
        openDb { db ->
            db.prepare("BEGIN TRANSACTION;")?.use { it.step() }
            try {
                val orphans = mutableListOf<Triple<Long, String, Long?>>()
                db.prepare(
                    """
                    SELECT f1.id, f1.name, f2.id as expected_parent
                    FROM folders f1
                    LEFT JOIN folders f2 ON f1.parentCkRecordId = f2.ckRecordId
                    WHERE f1.parentCkRecordId IS NOT NULL 
                    AND COALESCE(f1.parent, -1) != COALESCE(f2.id, -1)
                    """,
                )?.use { stmt ->
                    while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                        orphans.add(
                            Triple(
                                stmt.columnLong(0),
                                stmt.columnText(1) ?: "",
                                if (stmt.columnType(2) != SQLiteDB.SQLITE_NULL) stmt.columnLong(2) else null,
                            ),
                        )
                    }
                }

                for ((id, name, expectedParent) in orphans) {
                    val newParentId = expectedParent ?: continue
                    var conflictId: Long? = null
                    db.prepare("SELECT id FROM folders WHERE parent = ? AND name = ? AND id != ? LIMIT 1")
                        ?.use { stmt ->
                            stmt.bindLong(1, newParentId)
                            stmt.bindText(2, name)
                            stmt.bindLong(3, id)
                            if (stmt.step() == SQLiteDB.SQLITE_ROW) conflictId = stmt.columnLong(0)
                        }

                    if (conflictId != null) {
                        // Merge: move children and results to conflict, delete orphan
                        db.prepare("UPDATE results SET folder_id = ? WHERE folder_id = ?;")?.use { stmt ->
                            stmt.bindLong(1, conflictId); stmt.bindLong(2, id); stmt.step()
                        }
                        db.prepare("UPDATE folders SET parent = ? WHERE parent = ?;")?.use { stmt ->
                            stmt.bindLong(1, conflictId); stmt.bindLong(2, id); stmt.step()
                        }
                        db.prepare("DELETE FROM folders WHERE id = ?;")?.use { stmt ->
                            stmt.bindLong(1, id); stmt.step()
                        }
                    } else {
                        db.prepare("UPDATE folders SET parent = ? WHERE id = ?;")?.use { stmt ->
                            stmt.bindLong(1, newParentId); stmt.bindLong(2, id); stmt.step()
                        }
                    }
                }
                db.prepare("COMMIT;")?.use { it.step() }
            } catch (e: Exception) {
                db.prepare("ROLLBACK;")?.use { it.step() }
            }
        }
    }

    fun resolveOrphanResults() {
        openDb { db ->
            db.prepare("BEGIN TRANSACTION;")?.use { it.step() }
            try {
                val orphans = mutableListOf<Quadruple>()
                db.prepare(
                    """
                    SELECT r.id, r.name, r.bkId, f.id as expected_folder
                    FROM results r
                    LEFT JOIN folders f ON r.folderCkRecordId = f.ckRecordId
                    WHERE r.folderCkRecordId IS NOT NULL
                    AND COALESCE(r.folder_id, -1) != COALESCE(f.id, -1)
                    """,
                )?.use { stmt ->
                    while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                        orphans.add(
                            Quadruple(
                                stmt.columnLong(0),
                                stmt.columnText(1) ?: "",
                                stmt.columnInt(2),
                                if (stmt.columnType(3) != SQLiteDB.SQLITE_NULL) stmt.columnLong(3) else null,
                            ),
                        )
                    }
                }

                for (orphan in orphans) {
                    val newFolderId = orphan.expectedFolder ?: continue
                    var hasConflict = false
                    db.prepare("SELECT id FROM results WHERE folder_id = ? AND name = ? AND bkId = ? AND id != ? LIMIT 1")
                        ?.use { stmt ->
                            stmt.bindLong(1, newFolderId)
                            stmt.bindText(2, orphan.name)
                            stmt.bindInt(3, orphan.bkId)
                            stmt.bindLong(4, orphan.id)
                            if (stmt.step() == SQLiteDB.SQLITE_ROW) hasConflict = true
                        }

                    if (hasConflict) {
                        db.prepare("DELETE FROM results WHERE id = ?;")?.use { stmt ->
                            stmt.bindLong(1, orphan.id); stmt.step()
                        }
                    } else {
                        db.prepare("UPDATE results SET folder_id = ? WHERE id = ?;")?.use { stmt ->
                            stmt.bindLong(1, newFolderId); stmt.bindLong(2, orphan.id); stmt.step()
                        }
                    }
                }
                db.prepare("COMMIT;")?.use { it.step() }
            } catch (e: Exception) {
                db.prepare("ROLLBACK;")?.use { it.step() }
            }
        }
    }

    // endregion

    // region Private Helpers

    private inline fun openDb(block: (SQLiteDB) -> Unit) {
        SQLiteDB(
            dbFile.absolutePath,
            SQLiteDB.SQLITE_OPEN_READWRITE or SQLiteDB.SQLITE_OPEN_CREATE or SQLiteDB.SQLITE_OPEN_FULLMUTEX,
        ).use(block)
    }

    private fun fetchFolderCkRecordId(db: SQLiteDB, folderId: Long): String? {
        var ckId: String? = null
        db.prepare("SELECT ckRecordId FROM folders WHERE id = ? LIMIT 1")?.use { stmt ->
            stmt.bindLong(1, folderId)
            if (stmt.step() == SQLiteDB.SQLITE_ROW) ckId = stmt.columnText(0)
        }
        return ckId
    }

    private fun readSyncFolder(stmt: SQLiteStmt) = SyncFolder(
        id = stmt.columnLong(0),
        name = stmt.columnText(1) ?: "",
        parent = if (stmt.columnType(2) != SQLiteDB.SQLITE_NULL) stmt.columnLong(2) else null,
        ckRecordId = stmt.columnText(3),
        lastModified = if (stmt.columnType(4) != SQLiteDB.SQLITE_NULL) stmt.columnLong(4) else null,
        parentCkRecordId = stmt.columnText(5),
    )

    private fun readSyncResult(stmt: SQLiteStmt) = SyncResult(
        id = stmt.columnLong(0),
        folderId = if (stmt.columnType(1) != SQLiteDB.SQLITE_NULL) stmt.columnLong(1) else null,
        name = stmt.columnText(2) ?: "",
        query = stmt.columnText(3) ?: "",
        archive = stmt.columnInt(4),
        bkId = stmt.columnInt(5),
        contentId = stmt.columnText(6) ?: "",
        ckRecordId = stmt.columnText(7),
        lastModified = if (stmt.columnType(8) != SQLiteDB.SQLITE_NULL) stmt.columnLong(8) else null,
        folderCkRecordId = stmt.columnText(9),
    )

    private fun topologicalSort(folders: List<SyncFolder>): List<SyncFolder> {
        val sorted = mutableListOf<SyncFolder>()
        val pending = folders.toMutableList()
        var progress = true
        while (pending.isNotEmpty() && progress) {
            progress = false
            val iter = pending.listIterator()
            while (iter.hasNext()) {
                val f = iter.next()
                val parentInPending = pending.any { it.ckRecordId == f.parentCkRecordId }
                if (!parentInPending) {
                    sorted.add(f)
                    iter.remove()
                    progress = true
                }
            }
        }
        sorted.addAll(pending) // remaining (circular deps)
        return sorted
    }

    private fun resolveParentLocalId(db: SQLiteDB, parentCkRecordId: String?): Long? {
        if (parentCkRecordId == null) return null
        var id: Long? = null
        db.prepare("SELECT id FROM folders WHERE ckRecordId = ? LIMIT 1")?.use { stmt ->
            stmt.bindText(1, parentCkRecordId)
            if (stmt.step() == SQLiteDB.SQLITE_ROW) id = stmt.columnLong(0)
        }
        return id
    }

    private fun resolveFolderLocalId(db: SQLiteDB, folderCkRecordId: String?): Long? {
        if (folderCkRecordId == null) return null
        var id: Long? = null
        db.prepare("SELECT id FROM folders WHERE ckRecordId = ? LIMIT 1")?.use { stmt ->
            stmt.bindText(1, folderCkRecordId)
            if (stmt.step() == SQLiteDB.SQLITE_ROW) id = stmt.columnLong(0)
        }
        return id
    }

    private fun resolveConflictAndUpdate(db: SQLiteDB, existingId: Long, folder: SyncFolder, newParent: Long?, isOrphan: Boolean) {
        if (!isOrphan || newParent != null) {
            // Check unique constraint conflict
            var conflictId: Long? = null
            if (newParent != null) {
                db.prepare("SELECT id FROM folders WHERE parent = ? AND name = ? AND id != ? LIMIT 1")?.use { stmt ->
                    stmt.bindLong(1, newParent); stmt.bindText(2, folder.name); stmt.bindLong(3, existingId)
                    if (stmt.step() == SQLiteDB.SQLITE_ROW) conflictId = stmt.columnLong(0)
                }
            } else {
                db.prepare("SELECT id FROM folders WHERE parent IS NULL AND name = ? AND id != ? LIMIT 1")?.use { stmt ->
                    stmt.bindText(1, folder.name); stmt.bindLong(2, existingId)
                    if (stmt.step() == SQLiteDB.SQLITE_ROW) conflictId = stmt.columnLong(0)
                }
            }
            if (conflictId != null) {
                db.prepare("DELETE FROM folders WHERE id = ?;")?.use { stmt ->
                    stmt.bindLong(1, conflictId); stmt.step()
                }
            }
        }
        db.prepare("UPDATE folders SET name = ?, lastModified = ?, parentCkRecordId = ?, parent = ? WHERE id = ?;")?.use { stmt ->
            stmt.bindText(1, folder.name)
            stmt.bindLong(2, folder.lastModified ?: 0)
            if (folder.parentCkRecordId != null) stmt.bindText(3, folder.parentCkRecordId) else stmt.bindNull(3)
            if (newParent != null) stmt.bindLong(4, newParent) else stmt.bindNull(4)
            stmt.bindLong(5, existingId)
            stmt.step()
        }
    }

    private fun insertOrMergeFolder(db: SQLiteDB, folder: SyncFolder, ckId: String, pLocalId: Long?, isOrphan: Boolean) {
        if (isOrphan) {
            // Insert as root orphan — resolveOrphanFolders will fix later
            db.prepare("INSERT INTO folders (name, ckRecordId, lastModified, parentCkRecordId, parent) VALUES (?, ?, ?, ?, NULL);")
                ?.use { stmt ->
                    stmt.bindText(1, folder.name)
                    stmt.bindText(2, ckId)
                    stmt.bindLong(3, folder.lastModified ?: 0)
                    if (folder.parentCkRecordId != null) stmt.bindText(4, folder.parentCkRecordId) else stmt.bindNull(4)
                    stmt.step()
                }
            return
        }

        var conflictId: Long = -1
        var conflictLastMod: Long = 0
        if (pLocalId != null) {
            db.prepare("SELECT id, lastModified FROM folders WHERE parent = ? AND name = ? LIMIT 1")?.use { stmt ->
                stmt.bindLong(1, pLocalId); stmt.bindText(2, folder.name)
                if (stmt.step() == SQLiteDB.SQLITE_ROW) { conflictId = stmt.columnLong(0); conflictLastMod = stmt.columnLong(1) }
            }
        } else {
            db.prepare("SELECT id, lastModified FROM folders WHERE parent IS NULL AND name = ? LIMIT 1")?.use { stmt ->
                stmt.bindText(1, folder.name)
                if (stmt.step() == SQLiteDB.SQLITE_ROW) { conflictId = stmt.columnLong(0); conflictLastMod = stmt.columnLong(1) }
            }
        }

        if (conflictId != -1L) {
            val remoteLM = folder.lastModified ?: 0
            if (remoteLM >= conflictLastMod) {
                db.prepare("UPDATE folders SET ckRecordId = ?, lastModified = ?, parentCkRecordId = ?, parent = ? WHERE id = ?;")
                    ?.use { stmt ->
                        stmt.bindText(1, ckId); stmt.bindLong(2, remoteLM)
                        if (folder.parentCkRecordId != null) stmt.bindText(3, folder.parentCkRecordId) else stmt.bindNull(3)
                        if (pLocalId != null) stmt.bindLong(4, pLocalId) else stmt.bindNull(4)
                        stmt.bindLong(5, conflictId)
                        stmt.step()
                    }
            } else {
                db.prepare("UPDATE folders SET ckRecordId = ? WHERE id = ?;")?.use { stmt ->
                    stmt.bindText(1, ckId); stmt.bindLong(2, conflictId); stmt.step()
                }
            }
        } else {
            db.prepare("INSERT INTO folders (name, ckRecordId, lastModified, parentCkRecordId, parent) VALUES (?, ?, ?, ?, ?);")
                ?.use { stmt ->
                    stmt.bindText(1, folder.name); stmt.bindText(2, ckId); stmt.bindLong(3, folder.lastModified ?: 0)
                    if (folder.parentCkRecordId != null) stmt.bindText(3, folder.parentCkRecordId) else stmt.bindNull(3)
                    if (pLocalId != null) stmt.bindLong(5, pLocalId) else stmt.bindNull(5)
                    stmt.step()
                }
        }
    }

    private fun updateResultFromSync(db: SQLiteDB, existingId: Long, res: SyncResult, newFolder: Long?, isOrphan: Boolean) {
        if (!isOrphan || newFolder != null) {
            var conflictId: Long? = null
            if (newFolder != null) {
                db.prepare("SELECT id FROM results WHERE folder_id = ? AND name = ? AND bkId = ? AND id != ? LIMIT 1")
                    ?.use { stmt ->
                        stmt.bindLong(1, newFolder); stmt.bindText(2, res.name); stmt.bindInt(3, res.bkId); stmt.bindLong(4, existingId)
                        if (stmt.step() == SQLiteDB.SQLITE_ROW) conflictId = stmt.columnLong(0)
                    }
            } else {
                db.prepare("SELECT id FROM results WHERE folder_id IS NULL AND name = ? AND bkId = ? AND id != ? LIMIT 1")
                    ?.use { stmt ->
                        stmt.bindText(1, res.name); stmt.bindInt(2, res.bkId); stmt.bindLong(3, existingId)
                        if (stmt.step() == SQLiteDB.SQLITE_ROW) conflictId = stmt.columnLong(0)
                    }
            }
            if (conflictId != null) {
                db.prepare("DELETE FROM results WHERE id = ?;")?.use { stmt -> stmt.bindLong(1, conflictId); stmt.step() }
            }
        }
        db.prepare(
            """
            UPDATE results SET folder_id = ?, name = ?, query = ?, archives = ?,
            bkId = ?, contentId = ?, lastModified = ?, folderCkRecordId = ?
            WHERE id = ?;
            """,
        )?.use { stmt ->
            if (newFolder != null) stmt.bindLong(1, newFolder) else stmt.bindNull(1)
            stmt.bindText(2, res.name); stmt.bindText(3, res.query); stmt.bindInt(4, res.archive)
            stmt.bindInt(5, res.bkId); stmt.bindText(6, res.contentId); stmt.bindLong(7, res.lastModified ?: 0)
            if (res.folderCkRecordId != null) stmt.bindText(8, res.folderCkRecordId) else stmt.bindNull(8)
            stmt.bindLong(9, existingId)
            stmt.step()
        }
    }

    private fun insertOrMergeResult(db: SQLiteDB, res: SyncResult, ckId: String, fLocalId: Long?, isOrphan: Boolean) {
        if (isOrphan) {
            db.prepare(
                """
                INSERT INTO results (folder_id, name, query, archives, bkId, contentId, ckRecordId, lastModified, folderCkRecordId)
                VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, ?);
                """,
            )?.use { stmt ->
                stmt.bindText(1, res.name); stmt.bindText(2, res.query); stmt.bindInt(3, res.archive)
                stmt.bindInt(4, res.bkId); stmt.bindText(5, res.contentId); stmt.bindText(6, ckId)
                stmt.bindLong(7, res.lastModified ?: 0)
                if (res.folderCkRecordId != null) stmt.bindText(8, res.folderCkRecordId) else stmt.bindNull(8)
                stmt.step()
            }
            return
        }

        var conflictId: Long = -1
        var conflictLastMod: Long = 0
        if (fLocalId != null) {
            db.prepare("SELECT id, lastModified FROM results WHERE folder_id = ? AND name = ? AND bkId = ? LIMIT 1")
                ?.use { stmt ->
                    stmt.bindLong(1, fLocalId); stmt.bindText(2, res.name); stmt.bindInt(3, res.bkId)
                    if (stmt.step() == SQLiteDB.SQLITE_ROW) { conflictId = stmt.columnLong(0); conflictLastMod = stmt.columnLong(1) }
                }
        } else {
            db.prepare("SELECT id, lastModified FROM results WHERE folder_id IS NULL AND name = ? AND bkId = ? LIMIT 1")
                ?.use { stmt ->
                    stmt.bindText(1, res.name); stmt.bindInt(2, res.bkId)
                    if (stmt.step() == SQLiteDB.SQLITE_ROW) { conflictId = stmt.columnLong(0); conflictLastMod = stmt.columnLong(1) }
                }
        }

        if (conflictId != -1L) {
            val remoteLM = res.lastModified ?: 0
            if (remoteLM >= conflictLastMod) {
                db.prepare(
                    """
                    UPDATE results SET folder_id = ?, name = ?, query = ?, archives = ?,
                    bkId = ?, contentId = ?, ckRecordId = ?, lastModified = ?, folderCkRecordId = ?
                    WHERE id = ?;
                    """,
                )?.use { stmt ->
                    if (fLocalId != null) stmt.bindLong(1, fLocalId) else stmt.bindNull(1)
                    stmt.bindText(2, res.name); stmt.bindText(3, res.query); stmt.bindInt(4, res.archive)
                    stmt.bindInt(5, res.bkId); stmt.bindText(6, res.contentId); stmt.bindText(7, ckId)
                    stmt.bindLong(8, remoteLM)
                    if (res.folderCkRecordId != null) stmt.bindText(9, res.folderCkRecordId) else stmt.bindNull(9)
                    stmt.bindLong(10, conflictId)
                    stmt.step()
                }
            } else {
                db.prepare("UPDATE results SET ckRecordId = ? WHERE id = ?;")?.use { stmt ->
                    stmt.bindText(1, ckId); stmt.bindLong(2, conflictId); stmt.step()
                }
            }
        } else {
            db.prepare(
                """
                INSERT INTO results (folder_id, name, query, archives, bkId, contentId, ckRecordId, lastModified, folderCkRecordId)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
                """,
            )?.use { stmt ->
                if (fLocalId != null) stmt.bindLong(1, fLocalId) else stmt.bindNull(1)
                stmt.bindText(2, res.name); stmt.bindText(3, res.query); stmt.bindInt(4, res.archive)
                stmt.bindInt(5, res.bkId); stmt.bindText(6, res.contentId); stmt.bindText(7, ckId)
                stmt.bindLong(8, res.lastModified ?: 0)
                if (res.folderCkRecordId != null) stmt.bindText(9, res.folderCkRecordId) else stmt.bindNull(9)
                stmt.step()
            }
        }
    }

    // endregion

    data class RawResult(
        val id: Long,
        val folderId: Long?,
        val name: String,
        val query: String,
        val archive: Int,
        val bkId: Int,
        val contentId: String,
    )

    private data class Quadruple(val id: Long, val name: String, val bkId: Int, val expectedFolder: Long?)
}
