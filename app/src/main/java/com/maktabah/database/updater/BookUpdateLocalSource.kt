package com.maktabah.database.updater

import android.content.Context
import android.util.Log
import com.maktabah.database.SQLiteDB
import com.maktabah.models.AuthorRow
import com.maktabah.models.BookUpdateMetadata
import java.io.File

class BookUpdateLocalSource {
    companion object {
        private const val TAG = "BookUpdateLocalSource"
        private val VERSION_COLUMN_CANDIDATES = listOf("bver", "bVer")
    }

    @Volatile
    private var cachedVersionColumn: String? = null

    sealed interface BookVersionState {
        data object NotInLibrary : BookVersionState
        data object UnknownVersion : BookVersionState
        data class Version(val value: Long) : BookVersionState

        val existsInLibrary: Boolean
            get() = this !is NotInLibrary

        val currentVersion: Long?
            get() = (this as? Version)?.value
    }

    fun loadCategories(context: Context): Map<Int, String> {
        val mainDbFile = File(context.filesDir, "main.sqlite")
        if (!mainDbFile.exists()) return emptyMap()

        val categoriesMap = mutableMapOf<Int, String>()
        SQLiteDB(mainDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX).use { db ->
            db.prepare("SELECT id, name FROM \"0cat\"")?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    categoriesMap[stmt.columnInt(0)] = stmt.columnText(1) ?: ""
                }
            }
        }
        return categoriesMap
    }

    fun loadLocalBooks(context: Context): Map<Int, Pair<String, Long?>> {
        val mainDbFile = File(context.filesDir, "main.sqlite")
        if (!mainDbFile.exists()) return emptyMap()

        val localBooksMap = mutableMapOf<Int, Pair<String, Long?>>()
        SQLiteDB(mainDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX).use { db ->
            val verCol = resolveVersionColumn(db)
            val sql = if (verCol != null) {
                "SELECT bkid, bk, \"$verCol\" FROM \"0bok\""
            } else {
                "SELECT bkid, bk, NULL FROM \"0bok\""
            }
            db.prepare(sql)?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    val bkid = stmt.columnInt(0)
                    val bk = stmt.columnText(1) ?: ""
                    val ver = if (stmt.columnType(2) == SQLiteDB.SQLITE_NULL) null else stmt.columnLong(2)
                    localBooksMap[bkid] = Pair(bk, ver)
                }
            }
        }
        return localBooksMap
    }

    fun readBookMetadata(file: File, fallbackBookId: Int): BookUpdateMetadata? {
        if (!file.exists()) return null
        return try {
            SQLiteDB(file.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX).use { db ->
                val columns = mutableListOf<String>()
                db.prepare("PRAGMA table_info('main_update');")?.use { pragmaStmt ->
                    while (pragmaStmt.step() == SQLiteDB.SQLITE_ROW) {
                        pragmaStmt.columnText(1)?.let { columns.add(it) }
                    }
                }
                val hasPdfCs = columns.any { it.equals("PdfCs", ignoreCase = true) }

                val sql = if (hasPdfCs) {
                    """
                    SELECT bkid, bk, cat, betaka, inf, authno, archive, TafseerNam, bVer, link, PdfCs
                    FROM main_update WHERE bkid = ? LIMIT 1;
                    """.trimIndent()
                } else {
                    """
                    SELECT bkid, bk, cat, betaka, inf, authno, archive, TafseerNam, bVer, link
                    FROM main_update WHERE bkid = ? LIMIT 1;
                    """.trimIndent()
                }

                val stmt = db.prepare(sql) ?: return null

                stmt.use { s ->
                    s.bindInt(1, fallbackBookId)
                    if (s.step() != SQLiteDB.SQLITE_ROW) return null

                    val bkid = s.columnInt(0)
                    val bk = s.columnText(1) ?: ""
                    val cat = s.columnInt(2)
                    val betaka = s.columnText(3)?.takeIf { it.isNotEmpty() }
                    val inf = s.columnText(4)?.takeIf { it.isNotEmpty() }
                    val authno = s.columnInt(5)
                    val archive = s.columnInt(6).let { if (it in 1..20) it else 20 }
                    val tafseerNam = s.columnText(7)?.takeIf { it.isNotEmpty() }
                    val bVer = s.columnInt(8)
                    val link = s.columnText(9)?.takeIf { it.isNotEmpty() }
                    val pdfCs = if (hasPdfCs) s.columnInt(10) else null

                    BookUpdateMetadata(
                        bkid = bkid,
                        cat = cat,
                        bk = bk,
                        archive = archive,
                        betaka = betaka,
                        authno = authno,
                        inf = inf,
                        tafseerNam = tafseerNam,
                        bVer = bVer,
                        link = link,
                        pdfCs = pdfCs,
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readBookMetadata error", e)
            null
        }
    }

    fun saveBookMetadata(context: Context, metadata: BookUpdateMetadata, exists: Boolean) {
        val mainDbFile = File(context.filesDir, "main.sqlite")
        if (!mainDbFile.exists()) throw IllegalStateException("main.sqlite tidak ditemukan")

        SQLiteDB(mainDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READWRITE or SQLiteDB.SQLITE_OPEN_FULLMUTEX).use { db ->
            val verCol = resolveVersionColumn(db) ?: "bVer"
            if (!exists) {
                val sql = """
                    INSERT OR REPLACE INTO "0bok"
                    (bkid, cat, bk, Archive, betaka, authno, inf, TafseerNam, "$verCol", PdfCs)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """.trimIndent()
                db.prepare(sql)?.use { stmt ->
                    stmt.bindInt(1, metadata.bkid)
                    stmt.bindInt(2, metadata.cat ?: 0)
                    stmt.bindText(3, metadata.bk)
                    stmt.bindInt(4, metadata.archive)
                    stmt.bindText(5, metadata.betaka ?: "")
                    stmt.bindInt(6, metadata.authno ?: 0)
                    stmt.bindText(7, metadata.inf ?: "")
                    if (metadata.tafseerNam != null) stmt.bindText(8, metadata.tafseerNam) else stmt.bindNull(8)
                    if (metadata.bVer != null) stmt.bindInt(9, metadata.bVer) else stmt.bindNull(9)
                    if (metadata.pdfCs != null) stmt.bindInt(10, metadata.pdfCs) else stmt.bindNull(10)
                    if (stmt.step() != SQLiteDB.SQLITE_DONE) {
                        throw IllegalStateException("Gagal insert metadata buku ${metadata.bkid} ke 0bok")
                    }
                }
            } else {
                val sql = "UPDATE \"0bok\" SET \"$verCol\" = ? WHERE bkid = ?;"
                db.prepare(sql)?.use { stmt ->
                    if (metadata.bVer != null) stmt.bindInt(1, metadata.bVer) else stmt.bindNull(1)
                    stmt.bindInt(2, metadata.bkid)
                    if (stmt.step() != SQLiteDB.SQLITE_DONE) {
                        throw IllegalStateException("Gagal update versi buku ${metadata.bkid} di 0bok")
                    }
                }
            }
        }
    }

    fun getBookVersionState(context: Context, bookId: Int): BookVersionState {
        val mainDbFile = File(context.filesDir, "main.sqlite")
        if (!mainDbFile.exists()) return BookVersionState.UnknownVersion
        return try {
            SQLiteDB(mainDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX).use { db ->
                val verCol = resolveVersionColumn(db) ?: return BookVersionState.UnknownVersion
                db.prepare("SELECT \"$verCol\" FROM \"0bok\" WHERE bkid = ? LIMIT 1;")?.use { stmt ->
                    stmt.bindInt(1, bookId)
                    if (stmt.step() != SQLiteDB.SQLITE_ROW) {
                        return BookVersionState.NotInLibrary
                    }
                    if (stmt.columnType(0) == SQLiteDB.SQLITE_NULL) {
                        return BookVersionState.UnknownVersion
                    }
                    BookVersionState.Version(stmt.columnLong(0))
                } ?: BookVersionState.UnknownVersion
            }
        } catch (e: Exception) {
            Log.e(TAG, "getBookVersionState", e)
            BookVersionState.UnknownVersion
        }
    }

    fun isAuthorOutdated(context: Context, authId: Int, newVersion: Long): Boolean {
        val specialDbFile = File(context.filesDir, "special.sqlite")
        if (!specialDbFile.exists()) return false

        return SQLiteDB(
            specialDbFile.absolutePath,
            SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX,
        ).use { specialDb ->
            var currentVer: Long? = null
            specialDb.prepare("SELECT oVer FROM Auth WHERE authid = ? LIMIT 1;")?.use { stmt ->
                stmt.bindInt(1, authId)
                if (stmt.step() == SQLiteDB.SQLITE_ROW && stmt.columnType(0) != SQLiteDB.SQLITE_NULL) {
                    currentVer = stmt.columnLong(0)
                }
            }
            currentVer == null || newVersion > currentVer
        }
    }

    fun readAuthorFromDownloadedDb(downloadedAuthFile: File, authId: Int): AuthorRow? {
        if (!downloadedAuthFile.exists()) return null
        return SQLiteDB(
            downloadedAuthFile.absolutePath,
            SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX,
        ).use { authDb ->
            authDb.prepare("SELECT authid, auth, inf, Lng, HigriD, oVer FROM Auth WHERE authid = ? LIMIT 1;")?.use { stmt ->
                stmt.bindInt(1, authId)
                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    AuthorRow(
                        id = stmt.columnInt(0),
                        name = stmt.columnText(1) ?: "",
                        inf = stmt.columnText(2) ?: "",
                        lng = stmt.columnText(3) ?: "",
                        higriD = stmt.columnText(4) ?: "",
                        oVer = stmt.columnInt(5),
                    )
                } else null
            }
        }
    }

    fun saveAuthor(context: Context, authorRow: AuthorRow) {
        val specialDbFile = File(context.filesDir, "special.sqlite")
        if (!specialDbFile.exists()) return

        SQLiteDB(
            specialDbFile.absolutePath,
            SQLiteDB.SQLITE_OPEN_READWRITE or SQLiteDB.SQLITE_OPEN_FULLMUTEX,
        ).use { specialDb ->
            specialDb.prepare(
                "INSERT OR REPLACE INTO Auth (authid, auth, inf, Lng, HigriD, oVer) VALUES (?, ?, ?, ?, ?, ?);"
            )?.use { stmt ->
                stmt.bindInt(1, authorRow.id)
                stmt.bindText(2, authorRow.name)
                stmt.bindText(3, authorRow.inf)
                stmt.bindText(4, authorRow.lng)
                stmt.bindText(5, authorRow.higriD)
                stmt.bindInt(6, authorRow.oVer)
                stmt.step()
            }
        }
    }

    fun removeDatabaseAndSidecars(file: File) {
        if (file.exists()) file.delete()
        File("${file.absolutePath}-wal").let { if (it.exists()) it.delete() }
        File("${file.absolutePath}-shm").let { if (it.exists()) it.delete() }
        File("${file.absolutePath}-journal").let { if (it.exists()) it.delete() }
    }

    private fun resolveVersionColumn(db: SQLiteDB): String? {
        cachedVersionColumn?.let { return it }
        val columns = mutableListOf<String>()
        db.prepare("PRAGMA table_info('0bok');")?.use { stmt ->
            while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                stmt.columnText(1)?.let { columns.add(it) }
            }
        }
        val matched = columns.firstOrNull { col ->
            VERSION_COLUMN_CANDIDATES.any { it.equals(col, ignoreCase = true) }
        }
        if (matched != null) {
            cachedVersionColumn = matched
        }
        return matched
    }
}
