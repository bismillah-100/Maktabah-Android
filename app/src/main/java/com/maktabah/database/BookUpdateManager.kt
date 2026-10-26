package com.maktabah.database

import android.content.Context
import android.util.Log
import com.maktabah.models.AuthIndexEntry
import com.maktabah.models.AuthorRow
import com.maktabah.models.BookIndexEntry
import com.maktabah.models.BookUpdateItem
import com.maktabah.models.BookUpdateMetadata
import com.maktabah.models.BookUpdateResult
import com.maktabah.models.UpdateAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

object BookUpdateManager {
    private const val TAG = "BookUpdateManager"

    const val DRIVE_LINK = "https://drive.google.com/uc?export=download&id="
    const val MAIN_CSV_URL = "${DRIVE_LINK}1FYrscpCBIuIym2ZHB6QBwYfy9eswYDna"
    const val AUTH_CSV_URL = "${DRIVE_LINK}1Aekhq21Ihsxr1sAhnJSxZxA59yCxmEmq"

    private val versionColumnCandidates = listOf("bver", "bVer")
    @Volatile
    private var cachedVersionColumn: String? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    data class AuthorContext(
        val authId: Int,
        val versionName: Long,
        val downloadURL: String,
    )

    data class StagedBookUpdate(
        val entry: BookIndexEntry,
        val metadata: BookUpdateMetadata,
        val downloadedBookFile: File,
        val authorContext: AuthorContext?,
        val workingDirectory: File,
    )

    private sealed interface BookVersionState {
        data object NotInLibrary : BookVersionState
        data object UnknownVersion : BookVersionState
        data class Version(val value: Long) : BookVersionState

        val existsInLibrary: Boolean
            get() = this !is NotInLibrary

        val currentVersion: Long?
            get() = (this as? Version)?.value
    }

    // ── 1. Fetch & Compare Available Updates ──────────────────────────────────

    suspend fun fetchAvailableUpdates(
        context: Context,
        indexUrl: String = MAIN_CSV_URL,
    ): List<BookUpdateItem> = withContext(Dispatchers.IO) {
        val entries = fetchIndexEntries(indexUrl)
        if (entries.isEmpty()) return@withContext emptyList()

        val mainDbFile = File(context.filesDir, "main.sqlite")
        val categoriesMap = mutableMapOf<Int, String>()
        val localBooksMap = mutableMapOf<Int, Pair<String, Long?>>() // bkid -> (bkName, bVer)

        if (mainDbFile.exists()) {
            SQLiteDB(mainDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX).use { db ->
                db.prepare("SELECT id, name FROM \"0cat\"")?.use { stmt ->
                    while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                        categoriesMap[stmt.columnInt(0)] = stmt.columnText(1) ?: ""
                    }
                }

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
        }

        entries.map { entry ->
            val local = localBooksMap[entry.bkid]
            val exists = local != null
            val currentVer = local?.second
            val bookName = local?.first?.takeIf { it.isNotEmpty() } ?: entry.bk
            val categoryName = categoriesMap[entry.category] ?: ""

            BookUpdateItem(
                id = entry.bkid,
                bookName = bookName,
                category = entry.category,
                categoryName = categoryName,
                existsInLibrary = exists,
                currentVersion = currentVer,
                newVersion = entry.versionName,
                fileSize = entry.fileSize,
                downloadURL = entry.downloadURL,
            )
        }
    }

    // ── 2. Stage Book Download (Metadata DB + Book DB) ────────────────────────

    suspend fun stageBookDownload(
        context: Context,
        entry: BookIndexEntry,
        authIndex: Map<Int, AuthIndexEntry>,
    ): StagedBookUpdate = withContext(Dispatchers.IO) {
        val workingDir = getWorkingDirectory(context)
        val metadataFile = downloadFile(
            url = entry.downloadURL,
            directory = workingDir,
            filePrefix = "metadata_${entry.bkid}",
            isSqlite = true,
        )

        val metadata = try {
            readBookMetadata(metadataFile, entry.bkid)
                ?: throw IllegalStateException("Metadata kitab (tabel main_update) tidak ditemukan untuk buku ${entry.bkid}.")
        } finally {
            removeDatabaseAndSidecars(metadataFile)
        }

        val link = metadata.link?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Link download buku tidak tersedia untuk buku ${entry.bkid}.")

        val bookUrl = if (link.startsWith("http")) link else "$DRIVE_LINK$link"
        val downloadedBookFile = downloadFile(
            url = bookUrl,
            directory = workingDir,
            filePrefix = "book_${metadata.bkid}",
            isSqlite = true,
        )

        val authorContext = metadata.authno?.let { authId ->
            authIndex[authId]?.let { authEntry ->
                AuthorContext(
                    authId = authId,
                    versionName = authEntry.versionName,
                    downloadURL = authEntry.downloadURL,
                )
            }
        }

        StagedBookUpdate(
            entry = entry,
            metadata = metadata,
            downloadedBookFile = downloadedBookFile,
            authorContext = authorContext,
            workingDirectory = workingDir,
        )
    }

    // ── 3. Apply Staged Update (Author + Archive Integration + 0bok) ──────────

    suspend fun applyStagedBookUpdate(
        context: Context,
        staged: StagedBookUpdate,
    ): BookUpdateResult = withContext(Dispatchers.IO) {
        try {
            val versionState = getBookVersionState(context, staged.metadata.bkid)
            val exists = versionState.existsInLibrary
            val needsUpdate = !exists || versionState.currentVersion == null || versionState.currentVersion != staged.entry.versionName

            if (exists && !needsUpdate) {
                return@withContext BookUpdateResult(
                    bookId = staged.metadata.bkid,
                    catId = staged.entry.category,
                    action = UpdateAction.SKIPPED,
                )
            }

            // 1. Update Author jika diperlukan
            staged.authorContext?.let { authCtx ->
                ensureAuthor(
                    context = context,
                    authId = authCtx.authId,
                    downloadUrl = authCtx.downloadURL,
                    workingDirectory = staged.workingDirectory,
                    newVersion = authCtx.versionName,
                )
            }

            // 2. Integrasi tabel b{id}, t{id} & FTS5 ke archive
            val integrated = BookArchiveIntegrator.integrateDatabase(
                context = context,
                bookId = staged.metadata.bkid,
                archiveId = staged.metadata.archive,
                downloadedDbFile = staged.downloadedBookFile,
            )
            if (!integrated) {
                throw IllegalStateException("Gagal mengintegrasikan database untuk buku ${staged.metadata.bkid}")
            }

            // 3. Simpan metadata ke main.sqlite (0bok)
            saveBookMetadata(context, staged.metadata, exists)

            BookUpdateResult(
                bookId = staged.metadata.bkid,
                catId = staged.metadata.cat ?: staged.entry.category,
                action = if (exists) UpdateAction.UPDATED else UpdateAction.INSERTED,
            )
        } finally {
            removeDatabaseAndSidecars(staged.downloadedBookFile)
        }
    }

    // ── 4. SQLite Operations ──────────────────────────────────────────────────

    private fun readBookMetadata(file: File, fallbackBookId: Int): BookUpdateMetadata? {
        if (!file.exists()) return null
        return try {
            SQLiteDB(file.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX).use { db ->
                // Periksa apakah tabel main_update memiliki kolom PdfCs
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

    private fun saveBookMetadata(context: Context, metadata: BookUpdateMetadata, exists: Boolean) {
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

    private fun getBookVersionState(context: Context, bookId: Int): BookVersionState {
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

    private fun resolveVersionColumn(db: SQLiteDB): String? {
        cachedVersionColumn?.let { return it }
        val columns = mutableListOf<String>()
        db.prepare("PRAGMA table_info('0bok');")?.use { stmt ->
            while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                stmt.columnText(1)?.let { columns.add(it) }
            }
        }
        val matched = columns.firstOrNull { col ->
            versionColumnCandidates.any { it.equals(col, ignoreCase = true) }
        }
        if (matched != null) {
            cachedVersionColumn = matched
        }
        return matched
    }

    private fun ensureAuthor(
        context: Context,
        authId: Int,
        downloadUrl: String,
        workingDirectory: File,
        newVersion: Long,
    ) {
        val specialDbFile = File(context.filesDir, "special.sqlite")
        if (!specialDbFile.exists()) return

        val needsUpdate = SQLiteDB(
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

        if (!needsUpdate) return

        val downloadedAuthFile = downloadFile(
            url = downloadUrl,
            directory = workingDirectory,
            filePrefix = "auth_$authId",
            isSqlite = true,
        )
        try {
            val authorRow = SQLiteDB(
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
            } ?: return

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
        } finally {
            removeDatabaseAndSidecars(downloadedAuthFile)
        }
    }

    // ── 5. Network & CSV Parsing ──────────────────────────────────────────────

    suspend fun fetchIndexEntries(url: String = MAIN_CSV_URL): List<BookIndexEntry> = withContext(Dispatchers.IO) {
        val csv = fetchCsvString(url)
        parseIndexCsv(csv)
    }

    suspend fun fetchAuthIndexEntries(url: String = AUTH_CSV_URL): List<AuthIndexEntry> = withContext(Dispatchers.IO) {
        val csv = fetchCsvString(url)
        parseAuthIndexCsv(csv)
    }

    private fun fetchCsvString(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Maktabah-Android")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} saat mengambil index dari server")
            }
            return response.body.string()
        }
    }

    private fun downloadFile(
        url: String,
        directory: File,
        filePrefix: String,
        isSqlite: Boolean,
    ): File {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Maktabah-Android")
            .build()
        val ext = if (isSqlite) ".sqlite" else ""
        val destFile = File(directory, "${filePrefix}_${UUID.randomUUID()}$ext")

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Gagal mengunduh file (HTTP ${response.code})")
            }
            response.body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return destFile
    }

    fun getWorkingDirectory(context: Context): File {
        val dir = File(context.cacheDir, "Updates")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun cleanupWorkingDirectory(context: Context) {
        val dir = File(context.cacheDir, "Updates")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    private fun removeDatabaseAndSidecars(file: File) {
        if (file.exists()) file.delete()
        File("${file.absolutePath}-wal").let { if (it.exists()) it.delete() }
        File("${file.absolutePath}-shm").let { if (it.exists()) it.delete() }
        File("${file.absolutePath}-journal").let { if (it.exists()) it.delete() }
    }

    internal fun parseIndexCsv(csv: String): List<BookIndexEntry> {
        val rows = parseCsv(csv, ';')
        if (rows.isEmpty()) return emptyList()
        val dataRows = trimHeaderIfNeeded(rows, "bkid")
        return dataRows.mapNotNull { cols ->
            if (cols.size < 6) return@mapNotNull null
            val bkid = cols[0].trim().toIntOrNull() ?: return@mapNotNull null
            val cat = cols[1].trim().toIntOrNull() ?: return@mapNotNull null
            val ver = cols[2].trim().toLongOrNull() ?: return@mapNotNull null
            val idFile = cols[3].trim()
            val size = cols[4].trim().toLongOrNull() ?: return@mapNotNull null
            val bkName = cols[5].trim()
            BookIndexEntry(
                bkid = bkid,
                bk = bkName,
                category = cat,
                versionName = ver,
                downloadURL = "$DRIVE_LINK$idFile",
                fileSize = size,
            )
        }
    }

    internal fun parseAuthIndexCsv(csv: String): List<AuthIndexEntry> {
        val rows = parseCsv(csv, ';')
        if (rows.isEmpty()) return emptyList()
        val dataRows = trimHeaderIfNeeded(rows, "authid")
        return dataRows.mapNotNull { cols ->
            if (cols.size < 3) return@mapNotNull null
            val authId = cols[0].trim().toIntOrNull() ?: return@mapNotNull null
            val ver = cols[1].trim().toLongOrNull() ?: return@mapNotNull null
            val idFile = cols[2].trim()
            AuthIndexEntry(
                authId = authId,
                versionName = ver,
                downloadURL = "$DRIVE_LINK$idFile",
            )
        }
    }

    private fun trimHeaderIfNeeded(rows: List<List<String>>, headerKey: String): List<List<String>> {
        val firstCell = rows.firstOrNull()?.firstOrNull()?.trim() ?: return rows
        return if (firstCell.equals(headerKey, ignoreCase = true)) rows.drop(1) else rows
    }

    private fun parseCsv(csv: String, separator: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var insideQuotes = false

        for (char in csv) {
            when (char) {
                '"' -> insideQuotes = !insideQuotes
                separator -> {
                    if (insideQuotes) {
                        currentField.append(char)
                    } else {
                        currentRow.add(currentField.toString())
                        currentField.clear()
                    }
                }
                '\n' -> {
                    if (insideQuotes) {
                        currentField.append(char)
                    } else {
                        currentRow.add(currentField.toString())
                        rows.add(currentRow)
                        currentRow = mutableListOf()
                        currentField.clear()
                    }
                }
                '\r' -> continue
                else -> currentField.append(char)
            }
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString())
            rows.add(currentRow)
        }
        return rows
    }
}
