@file:Suppress("DEPRECATION")

package com.maktabah.database

import android.content.Context
import android.util.Log
import com.maktabah.models.IntegratePhase
import com.maktabah.utils.normalizeArabic
import com.maktabah.utils.removingHarakat
import com.maktabah.utils.stemArabicLight10
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
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
                db.prepare("SELECT Archive FROM \"0bok\" WHERE bkid = ? LIMIT 1;")?.use { stmt ->
                    stmt.bindInt(1, bookId)
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
            val sourceTableId = findSourceTableId(db)
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
            val columns = getTableSchema(db, sourceTableName)
            if (columns.isEmpty()) {
                Log.e(TAG, "Columns for $sourceTableName ARE EMPTY in source database")
                return@run false
            }

            Log.d(TAG, "Source table $sourceTableName has ${columns.size} columns: ${columns.joinToString { it.name }}")

            // 3 & 4. Copy book table data with compression
            copyBookData(db, sourceTableName, targetTableName, columns)

            // 4.5 Copy TOC Table
            copyTocData(db, sourceTocTableName, targetTocTableName)

            // 5. Detach source database
            db.prepare("DETACH DATABASE sourceDB;")?.use { it.step() }

            // 6. Build FTS
            Log.d(TAG, "Building FTS for $targetTableName")
            onPhaseChanged?.invoke(IntegratePhase.FTS)
            buildFtsIndex(db, ftsDb, targetTableName)

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

    private suspend fun findSourceTableId(db: SQLiteDB): String? {
        var sourceTableId: String? = null
        db.prepare("SELECT name FROM sourceDB.sqlite_master WHERE type='table' AND name LIKE 'b%' AND name NOT LIKE '%_fts' LIMIT 1;")?.use { stmt ->
            if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                yield()
                val name = stmt.columnText(0)
                if (name != null && name.startsWith("b")) {
                    sourceTableId = name.substring(1)
                }
            }
        }
        return sourceTableId
    }

    private suspend fun getTableSchema(db: SQLiteDB, tableName: String): List<TableColumnInfo> {
        val columns = mutableListOf<TableColumnInfo>()
        db.prepare("PRAGMA sourceDB.table_info('$tableName');")?.use { stmt ->
            while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                yield()
                val name = stmt.columnText(1) ?: continue
                val type = stmt.columnText(2) ?: "TEXT"
                val isPk = stmt.columnInt(5) == 1
                columns.add(TableColumnInfo(name, type, isPk))
            }
        }
        return columns
    }

    private fun bindStandardColumn(
        selectStmt: SQLiteStmt,
        insertStmt: SQLiteStmt,
        colIndex: Int
    ) {
        val bindPos = colIndex + 1
        when (selectStmt.columnType(colIndex)) {
            SQLiteDB.SQLITE_INTEGER -> insertStmt.bindLong(bindPos, selectStmt.columnLong(colIndex))
            SQLiteDB.SQLITE_TEXT -> insertStmt.bindText(bindPos, selectStmt.columnText(colIndex) ?: "")
            SQLiteDB.SQLITE_NULL -> insertStmt.bindNull(bindPos)
            SQLiteDB.SQLITE_BLOB -> {
                val blob = selectStmt.columnBlob(colIndex)
                if (blob != null) insertStmt.bindBlob(bindPos, blob) else insertStmt.bindNull(bindPos)
            }
            SQLiteDB.SQLITE_FLOAT -> insertStmt.bindText(bindPos, selectStmt.columnText(colIndex) ?: "")
        }
    }

    private suspend fun copyBookData(
        db: SQLiteDB,
        sourceTableName: String,
        targetTableName: String,
        columns: List<TableColumnInfo>
    ) {
        db.prepare("DROP TABLE IF EXISTS main.\"$targetTableName\";")?.use { it.step() }

        val createSql = makeCreateTableSQL(targetTableName, columns)
        Log.d(TAG, "Creating table in archive: $createSql")
        db.prepare(createSql)?.use { it.step() }

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
                    yield()
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
                            bindStandardColumn(selectStmt, insertStmt, i)
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
    }

    private suspend fun copyTocData(
        db: SQLiteDB,
        sourceTocTableName: String,
        targetTocTableName: String
    ) {
        val tocColumns = getTableSchema(db, sourceTocTableName)
        if (tocColumns.isEmpty()) {
            Log.w(TAG, "TOC table $sourceTocTableName not found in sourceDB")
            return
        }

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
                    yield()
                    insertTocStmt.reset()
                    insertTocStmt.clearBindings()
                    for (i in tocColumns.indices) {
                        bindStandardColumn(selectTocStmt, insertTocStmt, i)
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
    }

    private suspend fun buildFtsIndex(
        db: SQLiteDB,
        ftsDb: SQLiteDB,
        targetTableName: String
    ) {
        val ftsTableName = "${targetTableName}_fts"
        ftsDb.prepare("DROP TABLE IF EXISTS main.\"$ftsTableName\";")?.use { it.step() }
        ftsDb
            .prepare(
                "CREATE VIRTUAL TABLE main.\"$ftsTableName\" USING fts5(nass_clean, content='', tokenize='unicode61');",
            )?.use { it.step() }

        ftsDb.prepare("BEGIN TRANSACTION;")?.use { it.step() }

        var ftsCount = 0
        ftsDb.prepare("INSERT INTO main.\"$ftsTableName\" (rowid, nass_clean) VALUES (?, ?);")?.use { ftsInsertStmt ->
            db.prepare("SELECT id, nass FROM main.\"$targetTableName\" WHERE nass IS NOT NULL;")?.use { ftsSelectStmt ->
                val ctx = ZstdContextPool.getDecompressCtx()
                try {
                    while (ftsSelectStmt.step() == SQLiteDB.SQLITE_ROW) {
                        yield()
                        val id = ftsSelectStmt.columnLong(0)
                        val nassText = decompressBlob(ftsSelectStmt.columnBlobDirect(1), ctx)
                        if (nassText.isNotEmpty()) {
                            val cleanText =
                                nassText
                                    .replace("\n", " ")
                                    .replace("\r", " ")
                                    .removingHarakat()
                                    .normalizeArabic()
                                    .stemArabicLight10()

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
                } finally {
                    ZstdContextPool.releaseDecompressCtx(ctx)
                }
            }
        }
        ftsDb.prepare("COMMIT;")?.use { it.step() }
        Log.d(TAG, "Built FTS for $ftsCount rows")
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
