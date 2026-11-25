package com.maktabah.database

import android.content.Context
import android.util.Log
import com.maktabah.models.AuthorRow
import com.maktabah.models.BooksData
import com.maktabah.models.CategoryData
import com.maktabah.models.ImportBookMetadata
import com.maktabah.search.SearchHitResolver
import com.maktabah.utils.normalizeArabic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

object BookImportManager {
    private const val TAG = "BookImportManager"
    private const val READ_FLAGS = SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX
    private const val WRITE_FLAGS = SQLiteDB.SQLITE_OPEN_READWRITE or SQLiteDB.SQLITE_OPEN_FULLMUTEX

    private fun openDatabase(file: File, flags: Int): SQLiteDB {
        return SQLiteDB(file.absolutePath, flags).apply {
            prepare("PRAGMA busy_timeout=5000;")?.use { it.step() }
        }
    }

    fun getMaxBookId(context: Context): Int {
        val mainDbFile = File(context.filesDir, "main.sqlite")
        if (!mainDbFile.exists()) return 0
        return try {
            openDatabase(mainDbFile, READ_FLAGS).use { db ->
                db.prepare("SELECT MAX(bkid) FROM \"0bok\";")?.use { stmt ->
                    if (stmt.step() == SQLiteDB.SQLITE_ROW) stmt.columnInt(0) else 0
                } ?: 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMaxBookId", e); 0
        }
    }

    fun getMaxAuthId(context: Context): Int {
        val specialDbFile = File(context.filesDir, "special.sqlite")
        if (!specialDbFile.exists()) return 0
        return try {
            openDatabase(specialDbFile, READ_FLAGS).use { db ->
                db.prepare("SELECT MAX(authid) FROM Auth;")?.use { stmt ->
                    if (stmt.step() == SQLiteDB.SQLITE_ROW) stmt.columnInt(0) else 0
                } ?: 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMaxAuthId", e); 0
        }
    }

    fun getCategories(context: Context): List<CategoryData> {
        val mainDbFile = File(context.filesDir, "main.sqlite")
        if (!mainDbFile.exists()) return emptyList()
        val result = mutableListOf<CategoryData>()
        try {
            openDatabase(mainDbFile, READ_FLAGS).use { db ->
                db.prepare("SELECT id, name, Lvl, catord FROM \"0cat\" ORDER BY catord ASC")
                    ?.use { stmt ->
                        while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                            result.add(
                                CategoryData(
                                    id = stmt.columnInt(0),
                                    name = stmt.columnText(1) ?: "",
                                    level = stmt.columnInt(2),
                                    order = stmt.columnInt(3),
                                )
                            )
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCategories", e)
        }
        return result
    }

    fun getAuthors(context: Context): List<AuthorRow> {
        val specialDbFile = File(context.filesDir, "special.sqlite")
        if (!specialDbFile.exists()) return emptyList()
        val result = mutableListOf<AuthorRow>()
        try {
            openDatabase(specialDbFile, READ_FLAGS).use { db ->
                db.prepare("SELECT authid, auth, COALESCE(Lng,''), COALESCE(inf,''), COALESCE(HigriD,''), COALESCE(oVer,1) FROM Auth")
                    ?.use { stmt ->
                        while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                            val authName = stmt.columnText(1) ?: ""
                            val fullName = stmt.columnText(2) ?: ""
                            result.add(
                                AuthorRow(
                                    id = stmt.columnInt(0),
                                    name = authName.ifEmpty { fullName },
                                    lng = fullName,
                                    inf = stmt.columnText(3) ?: "",
                                    higriD = stmt.columnText(4) ?: "",
                                    oVer = stmt.columnInt(5),
                                )
                            )
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAuthors", e)
        }
        return result
            .map { it to it.name.normalizeArabic() }
            .sortedBy { it.second }
            .map { it.first }
    }

    fun getBooks(context: Context): List<BooksData> {
        val mainDbFile = File(context.filesDir, "main.sqlite")
        if (!mainDbFile.exists()) return emptyList()
        val result = mutableListOf<BooksData>()
        try {
            openDatabase(mainDbFile, READ_FLAGS).use { db ->
                db.prepare("SELECT bkid, cat, bk, Archive, COALESCE(inf,''), COALESCE(betaka,''), COALESCE(authno,0) FROM \"0bok\" ORDER BY bk ASC")
                    ?.use { stmt ->
                        while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                            result.add(
                                BooksData(
                                    id = stmt.columnInt(0),
                                    categoryId = stmt.columnInt(1),
                                    name = stmt.columnText(2) ?: "",
                                    archive = stmt.columnInt(3),
                                    info = stmt.columnText(4) ?: "",
                                    betaka = stmt.columnText(5) ?: "",
                                    authno = stmt.columnInt(6),
                                    auth = "",
                                    authInf = "",
                                )
                            )
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getBooks", e)
        }
        return result
    }

    /**
     * Mengimpor buku baru atau mengganti buku yang sudah ada.
     *
     * @param sourceFile file SQLite sumber yang dipilih user
     * @param metadata   metadata buku yang akan di-insert/update
     * @param newAuthor  jika tidak null, author baru akan di-insert ke special.sqlite
     * @param isNewBook  true untuk mode import buku baru, false untuk replace buku existing
     */
    suspend fun importBook(
        context: Context,
        sourceFile: File,
        metadata: ImportBookMetadata,
        newAuthor: AuthorRow?,
        isNewBook: Boolean = true,
    ): Result<Unit> {
        var catalogCommitted = false
        var contentIntegrated = false
        var insertedAuthorId: Int? = null
        return try {
            val oldArchiveId = BookArchiveIntegrator.getArchiveIdForBook(context, metadata.bkid)

            // 1. Integrasikan konten (tabel bX, tX, FTS) terlebih dahulu agar kegagalan tidak
            // meninggalkan metadata yatim di main.sqlite / special.sqlite
            val success = BookArchiveIntegrator.integrateDatabase(
                context = context,
                bookId = metadata.bkid,
                archiveId = metadata.archiveId,
                downloadedDbFile = sourceFile,
            )
            if (!success) return Result.failure(Exception("Gagal mengintegrasikan konten buku"))
            contentIntegrated = true

            val mainDbFile = File(context.filesDir, "main.sqlite")
            val specialDbFile = File(context.filesDir, "special.sqlite")

            // 2 & 3. Tulis author baru (jika ada) & metadata buku dalam single-flight katalog
            BookArchiveSingleFlight.runCatalogWrite {
                if (newAuthor != null && specialDbFile.exists()) {
                    openDatabase(specialDbFile, WRITE_FLAGS).use { specDb ->
                        insertedAuthorId = specDb.inTransaction {
                            specDb.prepare(
                                "INSERT OR REPLACE INTO Auth (authid, auth, Lng, inf, HigriD, oVer) VALUES (?,?,?,?,?,?)"
                            )?.use { stmt ->
                                stmt.bindInt(1, newAuthor.id)
                                stmt.bindText(2, newAuthor.name)
                                stmt.bindText(3, newAuthor.lng)
                                stmt.bindText(4, newAuthor.inf)
                                stmt.bindText(5, newAuthor.higriD)
                                stmt.bindInt(6, newAuthor.oVer)
                                if (stmt.step() != SQLiteDB.SQLITE_DONE) {
                                    throw Exception("Gagal menyimpan author baru ke special.sqlite")
                                }
                            }
                            newAuthor.id
                        }
                    }
                }

                openDatabase(mainDbFile, WRITE_FLAGS).use { db ->
                    catalogCommitted = db.inTransaction {
                        db.prepare("DELETE FROM \"0bok\" WHERE bkid = ?")?.use { stmt ->
                            stmt.bindInt(1, metadata.bkid)
                            stmt.step()
                        }

                        db.prepare(
                            """INSERT OR REPLACE INTO "0bok"
                                (bkid, cat, bk, Archive, betaka, authno, inf, TafseerNam, bVer, PdfCs)
                                VALUES (?,?,?,?,?,?,?,?,?,?)"""
                        )?.use { stmt ->
                            stmt.bindInt(1, metadata.bkid)
                            stmt.bindInt(2, metadata.categoryId)
                            stmt.bindText(3, metadata.bookName)
                            stmt.bindInt(4, metadata.archiveId)
                            if (metadata.betaka != null) stmt.bindText(5, metadata.betaka) else stmt.bindNull(5)
                            if (metadata.authno != null) stmt.bindInt(6, metadata.authno) else stmt.bindNull(6)
                            if (metadata.inf != null) stmt.bindText(7, metadata.inf) else stmt.bindNull(7)
                            if (metadata.tafseerNam != null) stmt.bindText(8, metadata.tafseerNam) else stmt.bindNull(8)
                            stmt.bindInt(9, metadata.bVer)
                            stmt.bindInt(10, if (metadata.isMultiLanguage) 3 else 0)
                            if (stmt.step() != SQLiteDB.SQLITE_DONE) {
                                throw Exception("Gagal menyimpan metadata buku ke 0bok")
                            }
                        }
                        true
                    }
                }
            }

            if (oldArchiveId != null && oldArchiveId > 0 && oldArchiveId != metadata.archiveId) {
                BookArchiveIntegrator.removeBookFromArchive(context, metadata.bkid, oldArchiveId)
            }

            SearchHitResolver.clearCache()
            Result.success(Unit)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (contentIntegrated && isNewBook) {
                    BookArchiveIntegrator.removeBookFromArchive(context, metadata.bkid, metadata.archiveId)
                }
                if (catalogCommitted || insertedAuthorId != null) {
                    rollbackFailedNewImport(
                        context = context,
                        bookId = metadata.bkid,
                        isNewBook = isNewBook,
                        insertedAuthorId = insertedAuthorId,
                    )
                }
            }
            throw e
        } catch (e: Exception) {
            if (contentIntegrated && isNewBook) {
                BookArchiveIntegrator.removeBookFromArchive(context, metadata.bkid, metadata.archiveId)
            }
            if (catalogCommitted || insertedAuthorId != null) {
                rollbackFailedNewImport(
                    context = context,
                    bookId = metadata.bkid,
                    isNewBook = isNewBook,
                    insertedAuthorId = insertedAuthorId,
                )
            }
            Log.e(TAG, "importBook", e)
            Result.failure(e)
        } finally {
            if (sourceFile.exists()) {
                sourceFile.delete()
            }
        }
    }

    private suspend fun rollbackFailedNewImport(
        context: Context,
        bookId: Int,
        isNewBook: Boolean,
        insertedAuthorId: Int?,
    ) {
        if (!isNewBook && insertedAuthorId == null) return
        try {
            BookArchiveSingleFlight.runCatalogWrite {
                val mainDbFile = File(context.filesDir, "main.sqlite")
                val specialDbFile = File(context.filesDir, "special.sqlite")
                var deleteOrphanAuthor = false

                if (mainDbFile.exists()) {
                    openDatabase(mainDbFile, WRITE_FLAGS).use { db ->
                        db.inTransaction {
                            if (isNewBook) {
                                db.prepare("DELETE FROM \"0bok\" WHERE bkid = ?;")?.use { stmt ->
                                    stmt.bindInt(1, bookId)
                                    stmt.step()
                                }
                            }
                            if (insertedAuthorId != null) {
                                val stillUsed = db.prepare("SELECT 1 FROM \"0bok\" WHERE authno = ? LIMIT 1;")?.use { stmt ->
                                    stmt.bindInt(1, insertedAuthorId)
                                    stmt.step() == SQLiteDB.SQLITE_ROW
                                } ?: false
                                if (!stillUsed) {
                                    deleteOrphanAuthor = true
                                }
                            }
                        }
                    }
                }

                if (deleteOrphanAuthor && insertedAuthorId != null && specialDbFile.exists()) {
                    openDatabase(specialDbFile, WRITE_FLAGS).use { specDb ->
                        specDb.inTransaction {
                            specDb.prepare("DELETE FROM Auth WHERE authid = ?;")?.use { stmt ->
                                stmt.bindInt(1, insertedAuthorId)
                                stmt.step()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "rollbackFailedNewImport", e)
        }
    }

    /**
     * Mengubah ID buku di arsip konten, FTS, tabel 0bok, dan shorts.
     * Anotasi dan saved results diupdate oleh pemanggil.
     */
    suspend fun changeBookId(context: Context, oldId: Int, newId: Int): Result<Unit> {
        if (oldId == newId) return Result.success(Unit)
        if (isBookIdTaken(context, newId)) {
            return Result.failure(Exception("ID buku $newId sudah digunakan"))
        }

        return try {
            val mainDbFile = File(context.filesDir, "main.sqlite")
            if (!mainDbFile.exists()) {
                return Result.failure(IllegalStateException("main.sqlite tidak ditemukan"))
            }
            val archiveId = BookArchiveIntegrator.getArchiveIdForBook(context, oldId)
            if (archiveId != null && archiveId > 0) {
                val migrated = BookArchiveIntegrator.migrateBookId(
                    context = context,
                    oldId = oldId,
                    newId = newId,
                    archiveId = archiveId,
                )
                if (!migrated) {
                    return Result.failure(Exception("Gagal memigrasikan konten buku ke ID baru"))
                }
            }

            BookArchiveSingleFlight.runCatalogWrite {
                openDatabase(mainDbFile, WRITE_FLAGS).use { db ->
                    db.inTransaction {
                        db.prepare("UPDATE \"0bok\" SET bkid = ? WHERE bkid = ?")?.use { stmt ->
                            stmt.bindInt(1, newId)
                            stmt.bindInt(2, oldId)
                            stmt.step()
                        }
                    }
                }
            }

            val specialDbFile = File(context.filesDir, "special.sqlite")
            if (specialDbFile.exists()) {
                openDatabase(specialDbFile, WRITE_FLAGS).use { specDb ->
                    specDb.prepare("UPDATE shorts SET Bk = ? WHERE CAST(Bk AS INTEGER) = ?")?.use { stmt ->
                        stmt.bindInt(1, newId)
                        stmt.bindInt(2, oldId)
                        stmt.step()
                    }
                }
            }
            SearchHitResolver.clearCache()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "changeBookId", e)
            Result.failure(e)
        }
    }

    /**
     * Menghapus metadata buku non-katalog-inti dari main.sqlite ("0bok") dan membersihkan
     * author non-katalog yang menjadi yatim di special.sqlite (Auth).
     * Mengembalikan himpunan bkid yang benar-benar terhapus dari katalog.
     */
    suspend fun deleteCatalogBookMetadata(
        context: Context,
        bookIds: List<Int>,
        coreAuthorIds: Set<Int>?,
    ): Set<Int> {
        val targetIds = bookIds.distinct()
        if (targetIds.isEmpty()) return emptySet()

        val mainDbFile = File(context.filesDir, "main.sqlite")
        if (!mainDbFile.exists()) return emptySet()
        val specialDbFile = File(context.filesDir, "special.sqlite")

        return BookArchiveSingleFlight.runCatalogWrite {
            val deletedBookIds = HashSet<Int>()
            val affectedAuthIds = HashSet<Int>()
            val orphanedAuthorIds = HashSet<Int>()

            openDatabase(mainDbFile, WRITE_FLAGS).use { db ->
                db.inTransaction {
                    for (chunk in targetIds.chunked(900)) {
                        val placeholders = chunk.joinToString(",") { "?" }
                        db.prepare("SELECT bkid, COALESCE(authno, 0) FROM \"0bok\" WHERE bkid IN ($placeholders);")?.use { stmt ->
                            chunk.forEachIndexed { idx, id -> stmt.bindInt(idx + 1, id) }
                            while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                                deletedBookIds.add(stmt.columnInt(0))
                                val authNo = stmt.columnInt(1)
                                if (authNo > 0) affectedAuthIds.add(authNo)
                            }
                        }
                        db.prepare("DELETE FROM \"0bok\" WHERE bkid IN ($placeholders);")?.use { stmt ->
                            chunk.forEachIndexed { idx, id -> stmt.bindInt(idx + 1, id) }
                            stmt.step()
                        }
                    }

                    if (!coreAuthorIds.isNullOrEmpty()) {
                        for (authId in affectedAuthIds) {
                            if (authId in coreAuthorIds) continue
                            val isStillReferenced = db.prepare("SELECT 1 FROM \"0bok\" WHERE authno = ? LIMIT 1;")?.use { stmt ->
                                stmt.bindInt(1, authId)
                                stmt.step() == SQLiteDB.SQLITE_ROW
                            } ?: false
                            if (!isStillReferenced) {
                                orphanedAuthorIds.add(authId)
                            }
                        }
                    }
                }
            }

            if ((orphanedAuthorIds.isNotEmpty() || deletedBookIds.isNotEmpty()) && specialDbFile.exists()) {
                try {
                    openDatabase(specialDbFile, WRITE_FLAGS).use { specDb ->
                        specDb.inTransaction {
                            for (chunk in orphanedAuthorIds.toList().chunked(900)) {
                                val placeholders = chunk.joinToString(",") { "?" }
                                specDb.prepare("DELETE FROM Auth WHERE authid IN ($placeholders);")?.use { stmt ->
                                    chunk.forEachIndexed { idx, id -> stmt.bindInt(idx + 1, id) }
                                    stmt.step()
                                }
                            }
                            if (deletedBookIds.isNotEmpty()) {
                                val hasShortsTable = specDb.prepare(
                                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='shorts' LIMIT 1;"
                                )?.use { it.step() == SQLiteDB.SQLITE_ROW } == true
                                if (hasShortsTable) {
                                    for (chunk in deletedBookIds.toList().chunked(900)) {
                                        val placeholders = chunk.joinToString(",") { "?" }
                                        specDb.prepare(
                                            "DELETE FROM shorts WHERE CAST(Bk AS INTEGER) IN ($placeholders);"
                                        )?.use { stmt ->
                                            chunk.forEachIndexed { idx, id -> stmt.bindInt(idx + 1, id) }
                                            stmt.step()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed cleaning orphaned authors or shorts from special.sqlite", e)
                }
            }

            deletedBookIds
        }
    }

    fun isBookIdTaken(context: Context, bookId: Int): Boolean {
        val mainDbFile = File(context.filesDir, "main.sqlite")
        if (!mainDbFile.exists()) return false
        return try {
            openDatabase(mainDbFile, READ_FLAGS).use { db ->
                db.prepare("SELECT 1 FROM \"0bok\" WHERE bkid = ? LIMIT 1")?.use { stmt ->
                    stmt.bindInt(1, bookId)
                    stmt.step() == SQLiteDB.SQLITE_ROW
                } ?: false
            }
        } catch (e: Exception) {
            false
        }
    }
}
