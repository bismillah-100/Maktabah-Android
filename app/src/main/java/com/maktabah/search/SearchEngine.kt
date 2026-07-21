package com.maktabah.search

import android.util.Log
import com.github.luben.zstd.Zstd
import com.maktabah.database.ZstdContextPool
import com.maktabah.database.SQLiteDB
import com.maktabah.models.BookContent
import com.maktabah.models.SearchMode
import com.maktabah.utils.normalizeArabic
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

            val tableName = "b$bookId"
            val ftsTableName = "${tableName}_fts"

            // Normalize keywords
            val normalizedQuery = query.normalizeArabic()

            // Build FTS query
            val ftsQuery = when (mode) {
                SearchMode.PHRASE -> "\"$normalizedQuery\""
                SearchMode.CONTAINS -> normalizedQuery.split(" ").filter { it.isNotBlank() }
                    .joinToString(" AND ")

                SearchMode.OR -> normalizedQuery.split(" ").filter { it.isNotBlank() }
                    .joinToString(" OR ")
            }

            // Get total matching rows count first
            var totalCount = 0
            val countSql = "SELECT COUNT(*) FROM fts_db.\"$ftsTableName\" WHERE nass_clean MATCH ?;"
            db.prepare(countSql)?.use { stmt ->
                stmt.bindText(1, ftsQuery)
                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    totalCount = stmt.columnInt(0)
                }
            }

            // Trigger initial progress report
            onRowProgress(0, totalCount)

            if (totalCount == 0) {
                return@withContext results
            }

            val sql = """
                SELECT main.id, main.nass, main.page, main.part
                FROM "$tableName" AS main
                INNER JOIN fts_db."$ftsTableName" AS fts ON fts.rowid = main.id
                WHERE fts.nass_clean MATCH ?
                LIMIT ? OFFSET ?
            """.trimIndent()

            db.prepare(sql)?.use { stmt ->
                stmt.bindText(1, ftsQuery)
                stmt.bindInt(2, limit)
                stmt.bindInt(3, offset)

                var currentFetched = 0
                var nassType = -1
                var partType = -1


                val zstdCtx = ZstdContextPool.getDecompressCtx()
                try {
                    while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                        coroutineContext.ensureActive()
                        val id = stmt.columnInt(0)
                        var nassText = ""

                        if (nassType == -1) {
                            nassType = stmt.columnType(1)
                        }

                        if (nassType == SQLiteDB.SQLITE_BLOB) {
                            val blob = stmt.columnBlobDirect(1)
                            if (blob != null) {
                                val decompressedSize =
                                    Zstd.getFrameContentSize(blob).toInt()
                                if (decompressedSize > 0) {
                                    val dstBuf = ZstdContextPool.getDirectBuffer(decompressedSize)
                                    zstdCtx.decompressDirectByteBuffer(dstBuf, 0, decompressedSize, blob, 0, blob.limit())
                                    val dst = ByteArray(decompressedSize)
                                    dstBuf.get(dst)
                                    ZstdContextPool.releaseDirectBuffer(dstBuf)

                                    nassText = String(dst)
                                }
                            }
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
