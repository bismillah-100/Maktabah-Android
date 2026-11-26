package com.maktabah.search

import android.content.Context
import android.util.LruCache
import com.maktabah.database.BookArchiveIntegrator
import com.maktabah.database.SQLiteDB
import com.maktabah.database.ZstdContextPool
import com.maktabah.database.decompressBlob
import com.maktabah.models.SearchMode
import com.maktabah.ui.search.computeArabicHighlightRanges
import com.maktabah.utils.cleaningLineBreaks
import com.maktabah.utils.convertToArabicDigits
import com.maktabah.utils.snippetAround
import com.maktabah.utils.snippetNear
import com.maktabah.utils.stripSpanTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

object SearchHitResolver {
    private val cache = LruCache<Long, String>(1000)
    private val highlightCache = LruCache<String, Pair<String, List<IntRange>>>(1000)

    private class CachedDb(val db: SQLiteDB) {
        val queryLock = Any()
        var activeUsers: Int = 0
        var markedForClose: Boolean = false
    }

    private val dbCacheLock = Any()
    private val dbCache = object : LruCache<String, CachedDb>(8) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: CachedDb, newValue: CachedDb?) {
            synchronized(dbCacheLock) {
                oldValue.markedForClose = true
                if (oldValue.activeUsers == 0) {
                    runCatching { oldValue.db.close() }
                }
            }
        }
    }

    fun getCachedSnippet(packedId: Long): String? = cache.get(packedId)

    fun putCachedSnippet(packedId: Long, snippet: String) {
        cache.put(packedId, snippet)
    }

    private fun buildHighlightKey(
        packedId: Long,
        searchKeywords: List<String>,
        mode: SearchMode,
        nearDistance: Int,
    ): String = "$packedId:${mode.name}:$nearDistance:${searchKeywords.joinToString("\u0000")}"

    fun getCachedHighlightRanges(
        packedId: Long,
        text: String,
        searchKeywords: List<String>,
        mode: SearchMode,
        nearDistance: Int,
    ): List<IntRange>? {
        if (text.isEmpty() || searchKeywords.isEmpty()) return emptyList()
        val entry = highlightCache.get(buildHighlightKey(packedId, searchKeywords, mode, nearDistance))
        return if (entry != null && entry.first == text) entry.second else null
    }

    suspend fun resolveHighlightRanges(
        packedId: Long,
        text: String,
        searchKeywords: List<String>,
        mode: SearchMode,
        nearDistance: Int,
    ): List<IntRange> {
        if (text.isEmpty() || searchKeywords.isEmpty()) return emptyList()
        getCachedHighlightRanges(packedId, text, searchKeywords, mode, nearDistance)?.let { return it }
        return withContext(Dispatchers.Default) {
            coroutineContext.ensureActive()
            val ranges = computeArabicHighlightRanges(text, searchKeywords, mode, nearDistance)
            highlightCache.put(buildHighlightKey(packedId, searchKeywords, mode, nearDistance), text to ranges)
            ranges
        }
    }

    fun clearCache() {
        cache.evictAll()
        highlightCache.evictAll()
        synchronized(dbCacheLock) {
            dbCache.evictAll()
        }
    }

    suspend fun resolveSnippet(
        context: Context,
        bookId: Int,
        contentId: Int,
        archiveId: Int,
        searchKeywords: List<String>,
        mode: SearchMode,
        nearDistance: Int
    ): String = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val packedId = (bookId.toLong() shl 32) or (contentId.toLong() and 0xFFFFFFFFL)
        val cached = cache.get(packedId)
        if (cached != null) {
            if (getCachedHighlightRanges(packedId, cached, searchKeywords, mode, nearDistance) == null) {
                val ranges = computeArabicHighlightRanges(cached, searchKeywords, mode, nearDistance)
                highlightCache.put(buildHighlightKey(packedId, searchKeywords, mode, nearDistance), cached to ranges)
            }
            return@withContext cached
        }

        val actualArchiveId = if (archiveId > 0) archiveId else (BookArchiveIntegrator.getArchiveIdForBook(context, bookId) ?: 0)
        val archiveFile = File(context.filesDir, "$actualArchiveId.sqlite")
        if (!archiveFile.exists()) return@withContext ""

        coroutineContext.ensureActive()
        val rawNass = fetchSingleNass(archiveFile, bookId, contentId) ?: ""
        if (rawNass.isBlank()) return@withContext ""

        coroutineContext.ensureActive()
        val stripped = rawNass.cleaningLineBreaks().stripSpanTags()
        val normalized = stripped.convertToArabicDigits()
        val snippet = if (mode == SearchMode.NEAR) {
            normalized.snippetNear(searchKeywords, nearDistance, contextLength = 60)
        } else {
            normalized.snippetAround(searchKeywords, contextLength = 60)
        }

        cache.put(packedId, snippet)
        if (snippet.isNotEmpty() && searchKeywords.isNotEmpty()) {
            val ranges = computeArabicHighlightRanges(snippet, searchKeywords, mode, nearDistance)
            highlightCache.put(buildHighlightKey(packedId, searchKeywords, mode, nearDistance), snippet to ranges)
        }
        snippet
    }

    private fun acquireDb(path: String): CachedDb? {
        synchronized(dbCacheLock) {
            val existing = dbCache.get(path)
            if (existing != null && !existing.markedForClose) {
                existing.activeUsers++
                return existing
            }
            val opened = try {
                SQLiteDB(
                    path,
                    SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX,
                )
            } catch (_: Exception) {
                return null
            }
            val entry = CachedDb(opened).apply { activeUsers = 1 }
            dbCache.put(path, entry)
            return entry
        }
    }

    private fun releaseDb(entry: CachedDb) {
        synchronized(dbCacheLock) {
            entry.activeUsers--
            if (entry.markedForClose && entry.activeUsers <= 0) {
                runCatching { entry.db.close() }
            }
        }
    }

    private fun fetchSingleNass(archiveFile: File, bookId: Int, contentId: Int): String? {
        val entry = acquireDb(archiveFile.absolutePath) ?: return null
        var nass: String? = null
        try {
            synchronized(entry.queryLock) {
                entry.db.prepare("SELECT nass FROM \"b$bookId\" WHERE id = ? LIMIT 1;")?.use { stmt ->
                    stmt.bindInt(1, contentId)
                    if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                        val type = stmt.columnType(0)
                        if (type == SQLiteDB.SQLITE_BLOB) {
                            val ctx = ZstdContextPool.getDecompressCtx()
                            try {
                                val blob = stmt.columnBlobDirect(0)
                                nass = decompressBlob(blob, ctx)
                            } finally {
                                ZstdContextPool.releaseDecompressCtx(ctx)
                            }
                        } else if (type == SQLiteDB.SQLITE_TEXT) {
                            nass = stmt.columnText(0)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            releaseDb(entry)
        }
        return nass
    }
}
