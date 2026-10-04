package com.maktabah.database

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.maktabah.R
import com.maktabah.utils.cleaningLineBreaks
import com.maktabah.utils.normalizeArabic
import com.maktabah.utils.removingHarakat
import com.maktabah.utils.stemArabicLight10
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File

class FtsMigrationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val tag = "FtsMigrationWorker"
    private val channelId = "FTS_MIGRATION_CHANNEL"
    private val notificationId = 1001

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val outdatedArchives = FtsMigrationManager.getOutdatedArchives(applicationContext)
        if (outdatedArchives.isEmpty()) {
            Log.i(tag, "Tidak ada arsip yang memerlukan migrasi FTS (semua sudah versi ${FtsMigrationManager.CURRENT_FTS_VERSION} atau belum ada arsip).")
            return@withContext Result.success()
        }

        Log.i(tag, "Ditemukan ${outdatedArchives.size} arsip yang butuh migrasi: $outdatedArchives")
        createNotificationChannel()

        val startingMsg = applicationContext.getString(R.string.fts_migration_starting)
        try {
            setForeground(createForegroundInfo(0, outdatedArchives.size, startingMsg))
        } catch (e: Exception) {
            Log.w(tag, "Gagal mengaktifkan foreground service notification: ${e.message}")
        }

        val filesDir = applicationContext.filesDir
        var completedArchives = 0

        for (archiveId in outdatedArchives) {
            val archiveFile = File(filesDir, "$archiveId.sqlite")
            val ftsFile = File(filesDir, "${archiveId}_fts.sqlite")
            if (!archiveFile.exists()) continue

            Log.i(tag, "Memulai migrasi untuk Arsip $archiveId...")
            migrateArchive(archiveId, archiveFile, ftsFile) { currentBook, totalBooks ->
                val msg = applicationContext.getString(
                    R.string.fts_migration_progress_book,
                    archiveId,
                    currentBook,
                    totalBooks
                )
                try {
                    setForegroundAsync(createForegroundInfo(completedArchives, outdatedArchives.size, msg))
                } catch (e: Exception) {
                    Log.d(tag, "Gagal update status notifikasi: ${e.message}")
                }
            }
            completedArchives++
            try {
                val completedMsg = applicationContext.getString(
                    R.string.fts_migration_progress_completed,
                    archiveId,
                    completedArchives,
                    outdatedArchives.size
                )
                setForegroundAsync(
                    createForegroundInfo(
                        completedArchives,
                        outdatedArchives.size,
                        completedMsg
                    )
                )
            } catch (_: Exception) {}
        }

        Log.i(tag, "Semua arsip berhasil dimigrasikan ke FTS v${FtsMigrationManager.CURRENT_FTS_VERSION}.")
        return@withContext Result.success()
    }

    private suspend fun migrateArchive(
        archiveId: Int,
        archiveFile: File,
        ftsFile: File,
        onBookProgress: suspend (Int, Int) -> Unit
    ) {
        var db: SQLiteDB? = null
        var ftsDb: SQLiteDB? = null
        try {
            db = SQLiteDB(archiveFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY)
            ftsDb = SQLiteDB(
                ftsFile.absolutePath,
                SQLiteDB.SQLITE_OPEN_READWRITE or SQLiteDB.SQLITE_OPEN_CREATE
            )

            ftsDb.prepare("PRAGMA synchronous = OFF;")?.use { it.step() }
            ftsDb.prepare("PRAGMA journal_mode = MEMORY;")?.use { it.step() }
            ftsDb.prepare("PRAGMA temp_store = MEMORY;")?.use { it.step() }

            ftsDb.prepare("CREATE TABLE IF NOT EXISTS main.metadata (key TEXT PRIMARY KEY, value INTEGER);")?.use { it.step() }
            ftsDb.prepare("CREATE TABLE IF NOT EXISTS main.archive_index (rowid INTEGER PRIMARY KEY, book_id INTEGER, page INTEGER, id INTEGER, part INTEGER);")?.use { it.step() }
            ftsDb.prepare("CREATE VIRTUAL TABLE IF NOT EXISTS main.archive_fts USING fts5(nass_clean, content='', contentless_delete=1, tokenize='unicode61');")?.use { it.step() }

            // Cari semua tabel buku b{id} dari archiveFile
            val bookIds = mutableListOf<Int>()
            db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'b%';")?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    val name = stmt.columnText(0) ?: continue
                    if (name.startsWith("b")) {
                        val bookId = name.drop(1).toIntOrNull()
                        if (bookId != null) {
                            bookIds.add(bookId)
                        }
                    }
                }
            }

            Log.i(tag, "Arsip $archiveId berisi ${bookIds.size} buku untuk diindeks.")
            if (bookIds.isEmpty()) {
                ftsDb.prepare("INSERT OR REPLACE INTO main.metadata (key, value) VALUES ('fts_version', ${FtsMigrationManager.CURRENT_FTS_VERSION});")?.use { it.step() }
                return
            }

            ftsDb.prepare("BEGIN TRANSACTION;")?.use { it.step() }
            var currentBook = 0

            ftsDb.prepare("INSERT INTO main.archive_fts (rowid, nass_clean) VALUES (?, ?);")?.use { ftsInsertStmt ->
                ftsDb.prepare("INSERT OR REPLACE INTO main.archive_index(rowid, book_id, page, id, part) VALUES (?, ?, ?, ?, ?);")?.use { indexInsertStmt ->

                    val ctx = ZstdContextPool.getDecompressCtx()
                    try {
                        for (bookId in bookIds) {
                            currentBook++
                            onBookProgress(currentBook, bookIds.size)

                            val tableName = "b$bookId"
                            val oldFtsTable = "${tableName}_fts"

                            // Bersihkan entri buku ini jika sudah ada sebelumnya di archive_index / archive_fts
                            try {
                                ftsDb.prepare("DELETE FROM main.archive_fts WHERE rowid IN (SELECT rowid FROM main.archive_index WHERE book_id = ?);")?.use { stmt ->
                                    stmt.bindLong(1, bookId.toLong())
                                    stmt.step()
                                }
                                ftsDb.prepare("DELETE FROM main.archive_index WHERE book_id = ?;")?.use { stmt ->
                                    stmt.bindLong(1, bookId.toLong())
                                    stmt.step()
                                }
                            } catch (_: Exception) {}

                            db.prepare("SELECT id, nass, page, part FROM main.\"$tableName\" WHERE nass IS NOT NULL;")?.use { ftsSelectStmt ->
                                while (ftsSelectStmt.step() == SQLiteDB.SQLITE_ROW) {
                                    yield()
                                    val id = ftsSelectStmt.columnLong(0)
                                    val page = ftsSelectStmt.columnLong(2)
                                    val part = ftsSelectStmt.columnLong(3)

                                    val nassText = when (ftsSelectStmt.columnType(1)) {
                                        SQLiteDB.SQLITE_BLOB -> decompressBlob(ftsSelectStmt.columnBlobDirect(1), ctx)
                                        SQLiteDB.SQLITE_TEXT -> ftsSelectStmt.columnText(1) ?: ""
                                        else -> ""
                                    }
                                    if (nassText.isNotEmpty()) {
                                        val cleanText = nassText
                                            .cleaningLineBreaks()
                                            .removingHarakat()
                                            .normalizeArabic()
                                            .stemArabicLight10()

                                        if (cleanText.isNotBlank()) {
                                            val packedRowId = (bookId.toLong() shl 32) or (id and 0xFFFFFFFFL)

                                            ftsInsertStmt.reset()
                                            ftsInsertStmt.clearBindings()
                                            ftsInsertStmt.bindLong(1, packedRowId)
                                            ftsInsertStmt.bindText(2, cleanText)
                                            ftsInsertStmt.step()

                                            indexInsertStmt.reset()
                                            indexInsertStmt.clearBindings()
                                            indexInsertStmt.bindLong(1, packedRowId)
                                            indexInsertStmt.bindLong(2, bookId.toLong())
                                            indexInsertStmt.bindLong(3, page)
                                            indexInsertStmt.bindLong(4, id)
                                            indexInsertStmt.bindLong(5, part)
                                            indexInsertStmt.step()
                                        }
                                    }
                                }
                            }

                            // Hapus tabel FTS lawas jika ada
                            ftsDb.prepare("DROP TABLE IF EXISTS main.\"$oldFtsTable\";")?.use { it.step() }
                        }
                    } finally {
                        ZstdContextPool.releaseDecompressCtx(ctx)
                    }
                }
            }

            ftsDb.prepare("INSERT OR REPLACE INTO main.metadata (key, value) VALUES ('fts_version', ${FtsMigrationManager.CURRENT_FTS_VERSION});")?.use { it.step() }
            ftsDb.prepare("COMMIT;")?.use { it.step() }
            ftsDb.prepare("VACUUM;")?.use { it.step() }
            Log.i(tag, "Sukses migrasi Arsip $archiveId ke FTS v${FtsMigrationManager.CURRENT_FTS_VERSION}.")
        } catch (e: Exception) {
            Log.e(tag, "Error saat migrasi Arsip $archiveId", e)
            ftsDb?.prepare("ROLLBACK;")?.use { it.step() }
        } finally {
            db?.close()
            ftsDb?.close()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                applicationContext.getString(R.string.fts_migration_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = applicationContext.getString(R.string.fts_migration_channel_desc)
            }
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(progress: Int, max: Int, message: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(applicationContext.getString(R.string.fts_migration_notification_title))
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(max, progress, false)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}
