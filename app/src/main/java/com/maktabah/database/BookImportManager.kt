package com.maktabah.database

import android.content.Context
import android.util.Log
import com.maktabah.models.AuthorRow
import com.maktabah.models.BooksData
import com.maktabah.models.CategoryData
import com.maktabah.models.ImportBookMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File

object BookImportManager {
    private const val TAG = "BookImportManager"

    fun getMaxBookId(context: Context): Int {
        val mainDbFile = File(context.filesDir, "main.sqlite")
        if (!mainDbFile.exists()) return 0
        return try {
            SQLiteDB(mainDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
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
            SQLiteDB(specialDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
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
            SQLiteDB(mainDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
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
            SQLiteDB(specialDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
                db.prepare("SELECT authid, auth, COALESCE(Lng,''), COALESCE(inf,''), COALESCE(HigriD,''), COALESCE(oVer,1) FROM Auth ORDER BY auth ASC")
                    ?.use { stmt ->
                        while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                            result.add(
                                AuthorRow(
                                    id = stmt.columnInt(0),
                                    name = stmt.columnText(1) ?: "",
                                    lng = stmt.columnText(2) ?: "",
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
    }

    fun getBooks(context: Context): List<BooksData> {
        val mainDbFile = File(context.filesDir, "main.sqlite")
        if (!mainDbFile.exists()) return emptyList()
        val result = mutableListOf<BooksData>()
        try {
            SQLiteDB(mainDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
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
     */
    suspend fun importBook(
        context: Context,
        sourceFile: File,
        metadata: ImportBookMetadata,
        newAuthor: AuthorRow?,
    ): Result<Unit> {
        return try {
            val mainDbFile = File(context.filesDir, "main.sqlite")
            val specialDbFile = File(context.filesDir, "special.sqlite")

            // 1. Insert author baru jika diperlukan
            if (newAuthor != null && specialDbFile.exists()) {
                SQLiteDB(specialDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READWRITE).use { specDb ->
                    specDb.prepare(
                        "INSERT OR REPLACE INTO Auth (authid, auth, Lng, inf, HigriD, oVer) VALUES (?,?,?,?,?,?)"
                    )?.use { stmt ->
                        stmt.bindInt(1, newAuthor.id)
                        stmt.bindText(2, newAuthor.name)
                        stmt.bindText(3, newAuthor.lng)
                        stmt.bindText(4, newAuthor.inf)
                        stmt.bindText(5, newAuthor.higriD)
                        stmt.bindInt(6, newAuthor.oVer)
                        stmt.step()
                    }
                }
            }

            // 2. Insert/replace metadata buku ke main.sqlite
            SQLiteDB(mainDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READWRITE).use { db ->
                db.prepare("BEGIN TRANSACTION;")?.use { it.step() }
                try {
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
                    db.prepare("COMMIT;")?.use { it.step() }
                } catch (e: Exception) {
                    db.prepare("ROLLBACK;")?.use { it.step() }
                    throw e
                }
            }

            // 3. Integrasikan konten (tabel bX, tX, FTS)
            val success = BookArchiveIntegrator.integrateDatabase(
                context = context,
                bookId = metadata.bkid,
                archiveId = metadata.archiveId,
                downloadedDbFile = sourceFile,
            )
            if (!success) return Result.failure(Exception("Gagal mengintegrasikan konten buku"))

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "importBook", e)
            Result.failure(e)
        }
    }

    /**
     * Mengubah ID buku di tabel 0bok. Anotasi diupdate oleh pemanggil.
     */
    fun changeBookId(context: Context, oldId: Int, newId: Int): Result<Unit> {
        return try {
            val mainDbFile = File(context.filesDir, "main.sqlite")
            SQLiteDB(mainDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READWRITE).use { db ->
                db.prepare("UPDATE \"0bok\" SET bkid = ? WHERE bkid = ?")?.use { stmt ->
                    stmt.bindInt(1, newId)
                    stmt.bindInt(2, oldId)
                    stmt.step()
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "changeBookId", e)
            Result.failure(e)
        }
    }

    fun isBookIdTaken(context: Context, bookId: Int): Boolean {
        val mainDbFile = File(context.filesDir, "main.sqlite")
        if (!mainDbFile.exists()) return false
        return try {
            SQLiteDB(mainDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
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
