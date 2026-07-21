@file:Suppress("DEPRECATION")

package com.maktabah.database

import android.content.Context
import android.util.Log
import com.github.luben.zstd.Zstd
import com.maktabah.models.IntegratePhase
import com.maktabah.utils.normalizeArabic
import com.maktabah.utils.removingHarakat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File

object BookArchiveIntegrator {
    private const val TAG = "BookArchiveIntegrator"

    fun getArchiveIdForBook(
        context: Context,
        bookId: Int,
    ): Int? {
        val mainDbFile = File(context.filesDir, "main.sqlite")
        if (!mainDbFile.exists()) return null
        var archiveId: Int? = null
        try {
            SQLiteDB(mainDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
                db.prepare("SELECT Archive FROM \"0bok\" WHERE bkid = $bookId LIMIT 1;")?.use { stmt ->
                    if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                        archiveId = stmt.columnInt(0)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get archive id", e)
        }
        return archiveId
    }

    suspend fun integrateDatabase(
        context: Context,
        bookId: Int,
        archiveId: Int,
        downloadedDbFile: File,
        onPhaseChanged: ((IntegratePhase) -> Unit)? = null
    ): Boolean = BookArchiveSingleFlight.run(archiveId) {
        Log.d(TAG, "Integrating book $bookId into archive $archiveId from ${downloadedDbFile.absolutePath}")
        onPhaseChanged?.invoke(IntegratePhase.DATA)
        val archiveFile = File(context.filesDir, "$archiveId.sqlite")
        val archiveFtsFile = File(context.filesDir, "${archiveId}_fts.sqlite")

        var db: SQLiteDB? = null
        var ftsDb: SQLiteDB? = null

        try {
            db =
                SQLiteDB(
                    archiveFile.absolutePath,
                    SQLiteDB.SQLITE_OPEN_READWRITE or SQLiteDB.SQLITE_OPEN_CREATE or SQLiteDB.SQLITE_OPEN_FULLMUTEX,
                )
            ftsDb =
                SQLiteDB(
                    archiveFtsFile.absolutePath,
                    SQLiteDB.SQLITE_OPEN_READWRITE or SQLiteDB.SQLITE_OPEN_CREATE or SQLiteDB.SQLITE_OPEN_FULLMUTEX,
                )

            // Attach source database
            db.prepare("ATTACH DATABASE ? AS sourceDB;")?.use { stmt ->
                stmt.bindText(1, downloadedDbFile.absolutePath)
                stmt.step()
            }

            // 1. Find the table name in source database (it might not match target bookId)
            var sourceTableId: String? = null
            db.prepare("SELECT name FROM sourceDB.sqlite_master WHERE type='table' AND name LIKE 'b%' AND name NOT LIKE '%_fts' LIMIT 1;")?.use { stmt ->
                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    coroutineContext.ensureActive()
                    val name = stmt.columnText(0)
                    if (name != null && name.startsWith("b")) {
                        sourceTableId = name.substring(1)
                    }
                }
            }

            if (sourceTableId == null) {
                Log.e(TAG, "Table b% NOT FOUND in source database ${downloadedDbFile.name}")
                return@run false
            }

            val sourceTableName = "b$sourceTableId"
            val sourceTocTableName = "t$sourceTableId"
            val targetTableName = "b$bookId"
            val targetTocTableName = "t$bookId"

            Log.d(TAG, "Found source table $sourceTableName, will integrate into $targetTableName")

            // 2. Get Schema of source table
            val columns = mutableListOf<TableColumnInfo>()
            db.prepare("PRAGMA sourceDB.table_info('$sourceTableName');")?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    coroutineContext.ensureActive()
                    val name = stmt.columnText(1) ?: continue
                    val type = stmt.columnText(2) ?: "TEXT"
                    val isPk = stmt.columnInt(5) == 1
                    columns.add(TableColumnInfo(name, type, isPk))
                }
            }

            if (columns.isEmpty()) {
                Log.e(TAG, "Columns for $sourceTableName ARE EMPTY in source database")
                return@run false
            }

            Log.d(TAG, "Source table $sourceTableName has ${columns.size} columns: ${columns.joinToString { it.name }}")

            // 3. Create archive table with nass as BLOB
            db.prepare("DROP TABLE IF EXISTS main.\"$targetTableName\";")?.use { it.step() }

            val createSql = makeCreateTableSQL(targetTableName, columns)
            Log.d(TAG, "Creating table in archive: $createSql")
            db.prepare(createSql)?.use { it.step() }

            // 4. Insert and compress
            val colNames = columns.joinToString(", ") { "\"${it.name}\"" }
            val placeholders = columns.joinToString(", ") { "?" }
            val insertSql = "INSERT INTO main.\"$targetTableName\" ($colNames) VALUES ($placeholders);"

            db.prepare("BEGIN TRANSACTION;")?.use { it.step() }

            var rowCount = 0
            db.prepare(insertSql)?.use { insertStmt ->
                db.prepare("SELECT * FROM sourceDB.\"$sourceTableName\";")?.use { selectStmt ->
                    val nassIndex = columns.indexOfFirst { it.name.equals("nass", ignoreCase = true) }
                    Log.d(TAG, "nass column index: $nassIndex")

                    while (selectStmt.step() == SQLiteDB.SQLITE_ROW) {
                        coroutineContext.ensureActive()
                        insertStmt.reset()
                        insertStmt.clearBindings()

                        for (i in columns.indices) {
                            if (i == nassIndex) {
                                val nassText = selectStmt.columnText(i)
                                if (nassText != null) {
                                    val compressed = compressText(nassText)
                                    if (compressed != null) {
                                    insertStmt.bindBlob(i + 1, compressed)
                                }
                                } else {
                                    insertStmt.bindNull(i + 1)
                                }
                            } else {
                                val type = selectStmt.columnType(i)
                                when (type) {
                                    SQLiteDB.SQLITE_INTEGER -> {
                                        insertStmt.bindLong(i + 1, selectStmt.columnLong(i))
                                    }

                                    SQLiteDB.SQLITE_TEXT -> {
                                        insertStmt.bindText(i + 1, selectStmt.columnText(i) ?: "")
                                    }

                                    SQLiteDB.SQLITE_NULL -> {
                                        insertStmt.bindNull(i + 1)
                                    }

                                    SQLiteDB.SQLITE_BLOB -> {
                                        val blob = selectStmt.columnBlob(i)
                                        if (blob != null) insertStmt.bindBlob(i + 1, blob) else insertStmt.bindNull(i + 1)
                                    }

                                    SQLiteDB.SQLITE_FLOAT -> {
                                        insertStmt.bindText(i + 1, selectStmt.columnText(i) ?: "")
                                    } // Simplified fallback
                                }
                            }
                        }
                        if (insertStmt.step() != SQLiteDB.SQLITE_DONE) {
                            Log.e(TAG, "Failed to insert row $rowCount into $targetTableName")
                        }
                        rowCount++
                    }
                }
            }
            db.prepare("COMMIT;")?.use { it.step() }
            Log.d(TAG, "Inserted $rowCount rows into $targetTableName")

            // 4.5 Copy TOC Table
            val tocColumns = mutableListOf<TableColumnInfo>()
            db.prepare("PRAGMA sourceDB.table_info('$sourceTocTableName');")?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    coroutineContext.ensureActive()
                    val name = stmt.columnText(1) ?: continue
                    val type = stmt.columnText(2) ?: "TEXT"
                    val isPk = stmt.columnInt(5) == 1
                    tocColumns.add(TableColumnInfo(name, type, isPk))
                }
            }

            if (tocColumns.isNotEmpty()) {
                db.prepare("DROP TABLE IF EXISTS main.\"$targetTocTableName\";")?.use { it.step() }
                val createTocSql = makeCreateTableSQL(targetTocTableName, tocColumns)
                Log.d(TAG, "Creating TOC table: $createTocSql")
                db.prepare(createTocSql)?.use { it.step() }

                val tocColNames = tocColumns.joinToString(", ") { "\"${it.name}\"" }
                val tocPlaceholders = tocColumns.joinToString(", ") { "?" }
                val insertTocSql = "INSERT INTO main.\"$targetTocTableName\" ($tocColNames) VALUES ($tocPlaceholders);"

                db.prepare("BEGIN TRANSACTION;")?.use { it.step() }

                var tocRowCount = 0
                db.prepare(insertTocSql)?.use { insertTocStmt ->
                    db.prepare("SELECT * FROM sourceDB.\"$sourceTocTableName\";")?.use { selectTocStmt ->
                        while (selectTocStmt.step() == SQLiteDB.SQLITE_ROW) {
                            coroutineContext.ensureActive()
                            insertTocStmt.reset()
                            insertTocStmt.clearBindings()
                            for (i in tocColumns.indices) {
                                val type = selectTocStmt.columnType(i)
                                when (type) {
                                    SQLiteDB.SQLITE_INTEGER -> {
                                        insertTocStmt.bindLong(i + 1, selectTocStmt.columnLong(i))
                                    }

                                    SQLiteDB.SQLITE_TEXT -> {
                                        insertTocStmt.bindText(i + 1, selectTocStmt.columnText(i) ?: "")
                                    }

                                    SQLiteDB.SQLITE_NULL -> {
                                        insertTocStmt.bindNull(i + 1)
                                    }

                                    SQLiteDB.SQLITE_BLOB -> {
                                        val blob = selectTocStmt.columnBlob(i)
                                        if (blob != null) insertTocStmt.bindBlob(i + 1, blob) else insertTocStmt.bindNull(i + 1)
                                    }

                                    SQLiteDB.SQLITE_FLOAT -> {
                                        insertTocStmt.bindText(i + 1, selectTocStmt.columnText(i) ?: "")
                                    }
                                }
                            }
                            if (insertTocStmt.step() != SQLiteDB.SQLITE_DONE) {
                                Log.e(TAG, "Failed to insert row $tocRowCount into $targetTocTableName")
                            }
                            tocRowCount++
                        }
                    }
                }
                db.prepare("COMMIT;")?.use { it.step() }
                Log.d(TAG, "Inserted $tocRowCount rows into $targetTocTableName")
            } else {
                Log.w(TAG, "TOC table $sourceTocTableName not found in sourceDB")
            }

            // 5. Detach source database
            db.prepare("DETACH DATABASE sourceDB;")?.use { it.step() }

            // 6. Build FTS
            Log.d(TAG, "Building FTS for $targetTableName")
            onPhaseChanged?.invoke(IntegratePhase.FTS)
            val ftsTableName = "${targetTableName}_fts"
            ftsDb.prepare("DROP TABLE IF EXISTS main.\"$ftsTableName\";")?.use { it.step() }
            ftsDb
                .prepare(
                    "CREATE VIRTUAL TABLE main.\"$ftsTableName\" USING fts5(nass_clean, content='', tokenize='unicode61');",
                )?.use { it.step() }

            ftsDb.prepare("BEGIN TRANSACTION;")?.use { it.step() }

            var ftsCount = 0
            ftsDb.prepare("INSERT INTO main.\"$ftsTableName\" (rowid, nass_clean) VALUES (?, ?);")?.use { ftsInsertStmt ->
                // Re-open main table to read nass blob, decompress, and build FTS
                db.prepare("SELECT id, nass FROM main.\"$targetTableName\" WHERE nass IS NOT NULL;")?.use { ftsSelectStmt ->
                    while (ftsSelectStmt.step() == SQLiteDB.SQLITE_ROW) {
                        coroutineContext.ensureActive()
                        val id = ftsSelectStmt.columnLong(0)
                        val nassText = decompressBlob(ftsSelectStmt.columnBlobDirect(1))
                        if (nassText.isNotEmpty()) {
                            val cleanText =
                                nassText
                                    .replace("\n", " ")
                                    .replace("\r", " ")
                                    .removingHarakat()
                                    .normalizeArabic()

                            if (cleanText.isNotBlank()) {
                                ftsInsertStmt.reset()
                                ftsInsertStmt.clearBindings()
                                ftsInsertStmt.bindLong(1, id)
                                ftsInsertStmt.bindText(2, cleanText)
                                ftsInsertStmt.step()
                                ftsCount++
                            }
                        }
                    }
                }
            }
            ftsDb.prepare("COMMIT;")?.use { it.step() }
            Log.d(TAG, "Built FTS for $ftsCount rows")

            return@run true
        } catch (e: CancellationException) {
            db?.prepare("ROLLBACK;")?.use { it.step() }
            ftsDb?.prepare("ROLLBACK;")?.use { it.step() }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error integrating database for book $bookId", e)
            db?.prepare("ROLLBACK;")?.use { it.step() }
            ftsDb?.prepare("ROLLBACK;")?.use { it.step() }
            return@run false
        } finally {
            db?.close()
            ftsDb?.close()
            // Clean up downloaded file
            if (downloadedDbFile.exists()) {
                downloadedDbFile.delete()
            }
        }
    }

    private fun makeCreateTableSQL(
        tableName: String,
        columns: List<TableColumnInfo>,
    ): String {
        val defs =
            columns.joinToString(", ") { col ->
                val pk = if (col.isPrimaryKey) " PRIMARY KEY" else ""
                if (col.name.equals("nass", ignoreCase = true)) {
                    "\"${col.name}\" BLOB$pk"
                } else {
                    "\"${col.name}\" ${col.type}$pk"
                }
            }
        return "CREATE TABLE main.\"$tableName\" ($defs);"
    }

    data class TableColumnInfo(
        val name: String,
        val type: String,
        val isPrimaryKey: Boolean,
    )
}
