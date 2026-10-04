package com.maktabah.search

import android.content.Context
import android.util.LruCache
import com.maktabah.database.BookArchiveIntegrator
import com.maktabah.database.SQLiteDB
import com.maktabah.database.ZstdContextPool
import com.maktabah.database.decompressBlob
import com.maktabah.models.SearchMode
import com.maktabah.utils.cleaningLineBreaks
import com.maktabah.utils.convertToArabicDigits
import com.maktabah.utils.normalizeArabic
import com.maktabah.utils.snippetAround
import com.maktabah.utils.snippetNear
import com.maktabah.utils.stripSpanTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SearchHitResolver {
    private val cache = LruCache<Long, String>(1000)

    fun getCachedSnippet(packedId: Long): String? = cache.get(packedId)

    fun putCachedSnippet(packedId: Long, snippet: String) {
        cache.put(packedId, snippet)
    }

    fun clearCache() {
        cache.evictAll()
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
        val packedId = (bookId.toLong() shl 32) or (contentId.toLong() and 0xFFFFFFFFL)
        val cached = cache.get(packedId)
        if (cached != null) return@withContext cached

        val actualArchiveId = if (archiveId > 0) archiveId else (BookArchiveIntegrator.getArchiveIdForBook(context, bookId) ?: 0)
        val archiveFile = File(context.filesDir, "$actualArchiveId.sqlite")
        if (!archiveFile.exists()) return@withContext ""

        val rawNass = fetchSingleNass(archiveFile, bookId, contentId) ?: ""
        if (rawNass.isBlank()) return@withContext ""

        val stripped = rawNass.cleaningLineBreaks().stripSpanTags()
        val normalized = stripped.convertToArabicDigits()
        val snippet = if (mode == SearchMode.NEAR) {
            normalized.snippetNear(searchKeywords, nearDistance, contextLength = 60)
        } else {
            normalized.snippetAround(searchKeywords, contextLength = 60)
        }

        cache.put(packedId, snippet)
        snippet
    }

    private fun fetchSingleNass(archiveFile: File, bookId: Int, contentId: Int): String? {
        var nass: String? = null
        try {
            SQLiteDB(archiveFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
                db.prepare("SELECT nass FROM \"b$bookId\" WHERE id = ? LIMIT 1;")?.use { stmt ->
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
        } catch (_: Exception) {}
        return nass
    }
}
