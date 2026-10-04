package com.maktabah.search

import android.util.Log
import com.maktabah.database.SQLiteDB
import com.maktabah.database.ZstdContextPool
import com.maktabah.database.decompressBlob
import com.maktabah.models.BookContent
import com.maktabah.models.SearchMode
import com.maktabah.utils.normalizeArabic
import com.maktabah.utils.stemArabicLight10
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

class SearchEngine {
    private val tag = "SearchEngine"

    suspend fun searchInBook(
        bookId: Int,
        archiveFile: File,
        archiveFtsFile: File,
        query: String,
        mode: SearchMode = SearchMode.PHRASE,
        nearDistance: Int = 10,
        limit: Int = 100,
        offset: Int = 0,
        onRowProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): List<BookContent> = withContext(Dispatchers.IO) {
        val results = mutableListOf<BookContent>()
        if (!archiveFile.exists() || !archiveFtsFile.exists()) {
            Log.e(tag, "Archive files not found")
            return@withContext results
        }

        var db: SQLiteDB? = null
        try {
            db = SQLiteDB(archiveFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY)

            // Attach FTS database
            db.prepare("ATTACH DATABASE ? AS fts_db;")?.use { stmt ->
                stmt.bindText(1, archiveFtsFile.absolutePath)
                stmt.step()
            }

            val isUnified = com.maktabah.database.ArchiveDatabaseTools.hasUnifiedFTS(db, "fts_db")
            val tableName = "b$bookId"
            val ftsTableName = "${tableName}_fts"

            // Normalize & stem keywords using Lucene Light10 Stemmer
            val stemmedKeywords = if (mode == SearchMode.PHRASE) {
                query.normalizeArabic()
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .map { it.stemArabicLight10() }
            } else {
                query.normalizeArabic()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { phrase ->
                        phrase.split(" ").filter { it.isNotBlank() }
                            .joinToString(" ") { it.stemArabicLight10() }
                    }
            }

            // Build FTS query
            val ftsQuery = if (mode == SearchMode.PHRASE) {
                "\"${stemmedKeywords.joinToString(" ")}\""
            } else if (stemmedKeywords.size == 1) {
                "\"${stemmedKeywords.first()}\""
            } else {
                val quoted = stemmedKeywords.map { "\"$it\"" }
                when (mode) {
                    SearchMode.CONTAINS -> quoted.joinToString(" AND ")
                    SearchMode.OR -> quoted.joinToString(" OR ")
                    SearchMode.NEAR -> "NEAR(${quoted.joinToString(" ")}, $nearDistance)"
                    SearchMode.PHRASE -> quoted.joinToString(" ") // Should not reach here
                }
            }

            val minRowId = bookId.toLong() shl 32
            val maxRowId = minRowId or 0xFFFFFFFFL

            // Get total matching rows count first
            var totalCount = 0
            val countSql = if (isUnified) {
                "SELECT COUNT(*) FROM fts_db.archive_fts WHERE nass_clean MATCH ? AND rowid BETWEEN ? AND ?;"
            } else {
                "SELECT COUNT(*) FROM fts_db.\"$ftsTableName\" WHERE nass_clean MATCH ?;"
            }
            
            db.prepare(countSql)?.use { stmt ->
                stmt.bindText(1, ftsQuery)
                if (isUnified) {
                    stmt.bindLong(2, minRowId)
                    stmt.bindLong(3, maxRowId)
                }
                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    totalCount = stmt.columnInt(0)
                }
            }

            // Trigger initial progress report
            onRowProgress(0, totalCount)

            if (totalCount == 0) {
                return@withContext results
            }

            val sql = if (isUnified) {
                """
                    SELECT main.id, main.nass, main.page, main.part
                    FROM fts_db.archive_fts AS fts
                    INNER JOIN fts_db.archive_index AS idx ON idx.rowid = fts.rowid
                    INNER JOIN "$tableName" AS main ON main.id = idx.id
                    WHERE fts.nass_clean MATCH ? AND fts.rowid BETWEEN ? AND ?
                    LIMIT ? OFFSET ?
                """.trimIndent()
            } else {
                """
                    SELECT main.id, main.nass, main.page, main.part
                    FROM "$tableName" AS main
                    INNER JOIN fts_db."$ftsTableName" AS fts ON fts.rowid = main.id
                    WHERE fts.nass_clean MATCH ?
                    LIMIT ? OFFSET ?
                """.trimIndent()
            }

            db.prepare(sql)?.use { stmt ->
                stmt.bindText(1, ftsQuery)
                if (isUnified) {
                    stmt.bindLong(2, minRowId)
                    stmt.bindLong(3, maxRowId)
                    stmt.bindInt(4, limit)
                    stmt.bindInt(5, offset)
                } else {
                    stmt.bindInt(2, limit)
                    stmt.bindInt(3, offset)
                }

                var currentFetched = 0
                var nassType = -1
                var partType = -1


                val zstdCtx = ZstdContextPool.getDecompressCtx()
                try {
                    while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                        coroutineContext.ensureActive()
                        val id = stmt.columnInt(0)
                        var nassText: String

                        if (nassType == -1) {
                            nassType = stmt.columnType(1)
                        }

                        if (nassType == SQLiteDB.SQLITE_BLOB) {
                            val blob = stmt.columnBlobDirect(1)
                            nassText = decompressBlob(blob, zstdCtx)
                        } else {
                            nassText = stmt.columnText(1) ?: ""
                        }

                        val page = stmt.columnInt(2)

                        // Helper to parse part
                        if (partType == -1) {
                            partType = stmt.columnType(3)
                        }
                        val part = if (partType == SQLiteDB.SQLITE_INTEGER) {
                            stmt.columnInt(3)
                        } else if (partType == SQLiteDB.SQLITE_TEXT) {
                            val strValue = stmt.columnText(3)
                            if (strValue != null) {
                                val dashIndex = strValue.indexOf('-')
                                if (dashIndex != -1) {
                                    strValue.substring(0, dashIndex).toIntOrNull() ?: 1
                                } else {
                                    strValue.toIntOrNull() ?: 1
                                }
                            } else {
                                1
                            }
                        } else {
                            1
                        }

                        results.add(BookContent(id, nassText, page, part))
                        currentFetched++
                        onRowProgress(currentFetched, totalCount)
                    }
                } finally {
                    ZstdContextPool.releaseDecompressCtx(zstdCtx)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Search error", e)
        } finally {
            try {
                db?.prepare("DETACH DATABASE fts_db;")?.use { it.step() }
            } catch (_: Exception) {
                // Ignore
            }
            db?.close()
        }

        return@withContext results
    }
}
