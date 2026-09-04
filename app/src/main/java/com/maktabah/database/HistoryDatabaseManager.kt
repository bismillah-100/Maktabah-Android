package com.maktabah.database

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.maktabah.models.ReadingEntry
import org.json.JSONObject
import java.io.File

/**
 * Mengelola penyimpanan History dan Favorite ke SQLite.
 * Mengikuti skema dan API iOS HistoryDatabaseManager.swift.
 *
 * Singleton: diinisialisasi oleh HistoryViewModel, diakses oleh CloudKitSyncManager.
 */
class HistoryDatabaseManager(private val dbFile: File) {

    companion object {
        @Volatile
        var instance: HistoryDatabaseManager? = null
            private set

        fun getInstance(context: Context): HistoryDatabaseManager {
            return instance ?: synchronized(this) {
                instance ?: HistoryDatabaseManager(File(context.applicationContext.filesDir, "History.sqlite")).also {
                    instance = it
                }
            }
        }

        private const val MIGRATION_FLAG = "HistoryVM_SQLiteMigrated"

        @Volatile
        private var isDbSetup = false
    }

    init {
        instance = this
    }

    private fun ensureSetup() {
        if (!isDbSetup) {
            synchronized(this) {
                if (!isDbSetup) {
                    setupDatabase()
                    isDbSetup = true
                }
            }
        }
    }

    // region Setup

    private fun setupDatabase() {
        SQLiteDB(
            dbFile.absolutePath,
            SQLiteDB.SQLITE_OPEN_READWRITE or SQLiteDB.SQLITE_OPEN_CREATE or SQLiteDB.SQLITE_OPEN_FULLMUTEX
        ).use { db ->
            db.prepare("PRAGMA journal_mode=WAL;")?.use { it.step() }
            db.prepare("PRAGMA busy_timeout=5000;")?.use { it.step() }
            db.prepare("""
                CREATE TABLE IF NOT EXISTS reading_entries (
                    book_id INTEGER PRIMARY KEY,
                    last_content_id INTEGER,
                    last_opened_at INTEGER,
                    favorited_at INTEGER,
                    position_updated_at INTEGER,
                    updated_at INTEGER NOT NULL,
                    is_favorite INTEGER NOT NULL DEFAULT 0,
                    ck_record_id TEXT
                );
            """.trimIndent())?.use { it.step() }
            db.prepare("""
                CREATE TABLE IF NOT EXISTS history_order (
                    position INTEGER PRIMARY KEY,
                    book_id INTEGER NOT NULL
                );
            """.trimIndent())?.use { it.step() }
            db.prepare("""
                CREATE TABLE IF NOT EXISTS sync_pending (
                    ck_record_id TEXT PRIMARY KEY,
                    operation TEXT NOT NULL CHECK(operation IN ('upload', 'delete')),
                    queued_at INTEGER NOT NULL
                );
            """.trimIndent())?.use { it.step() }
            db.prepare("CREATE INDEX IF NOT EXISTS idx_re_favorite ON reading_entries (is_favorite, favorited_at);")
                ?.use { it.step() }
            db.prepare("CREATE INDEX IF NOT EXISTS idx_sync_pending_op ON sync_pending (operation, queued_at);")
                ?.use { it.step() }
        }
    }

    // endregion

    // region CRUD

    fun upsertEntry(entry: ReadingEntry) {
        openRW().use { db ->
            db.prepare(
                """INSERT OR REPLACE INTO reading_entries
                   (book_id, last_content_id, last_opened_at, favorited_at,
                    position_updated_at, updated_at, is_favorite, ck_record_id)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?);"""
            )?.use { stmt ->
                stmt.bindInt(1, entry.bookId)
                if (entry.lastContentId != null) stmt.bindInt(2, entry.lastContentId)
                    else stmt.bindNull(2)
                if (entry.lastOpenedAt != null) stmt.bindLong(3, entry.lastOpenedAt)
                    else stmt.bindNull(3)
                if (entry.favoritedAt != null) stmt.bindLong(4, entry.favoritedAt)
                    else stmt.bindNull(4)
                if (entry.positionUpdatedAt != null) stmt.bindLong(5, entry.positionUpdatedAt)
                    else stmt.bindNull(5)
                stmt.bindLong(6, entry.updatedAt)
                stmt.bindInt(7, if (entry.isFavorite) 1 else 0)
                if (entry.ckRecordId != null) stmt.bindText(8, entry.ckRecordId)
                    else stmt.bindNull(8)
                stmt.step()
            }
        }
    }

    fun deleteEntry(bookId: Int) {
        openRW().use { db ->
            db.prepare("DELETE FROM reading_entries WHERE book_id = ?;")?.use { stmt ->
                stmt.bindInt(1, bookId)
                stmt.step()
            }
        }
    }

    fun clearAllData() {
        openRW().use { db ->
            db.prepare("BEGIN TRANSACTION;")?.use { it.step() }
            try {
                db.prepare("DELETE FROM reading_entries;")?.use { it.step() }
                db.prepare("DELETE FROM history_order;")?.use { it.step() }
                db.prepare("DELETE FROM sync_pending;")?.use { it.step() }
                db.prepare("COMMIT;")?.use { it.step() }
            } catch (e: Exception) {
                db.prepare("ROLLBACK;")?.use { it.step() }
                throw e
            }
        }
    }

    fun saveHistoryOrder(order: List<Int>) {
        openRW().use { db ->
            db.prepare("BEGIN TRANSACTION;")?.use { it.step() }
            try {
                insertHistoryOrder(db, order)
                db.prepare("COMMIT;")?.use { it.step() }
            } catch (e: Exception) {
                db.prepare("ROLLBACK;")?.use { it.step() }
                throw e
            }
        }
    }

    private fun insertHistoryOrder(db: SQLiteDB, order: List<Int>) {
        db.prepare("DELETE FROM history_order;")?.use { it.step() }
        if (order.isEmpty()) return
        var pos = 0
        for (chunk in order.chunked(450)) {
            val valuePlaceholders = chunk.joinToString(",") { "(?, ?)" }
            val sql = "INSERT INTO history_order (position, book_id) VALUES $valuePlaceholders;"
            db.prepare(sql)?.use { stmt ->
                chunk.forEachIndexed { index, bookId ->
                    val paramIndex = index * 2
                    stmt.bindInt(paramIndex + 1, pos + index)
                    stmt.bindInt(paramIndex + 2, bookId)
                }
                stmt.step()
            }
            pos += chunk.size
        }
    }

    fun loadFromDatabase(): Pair<List<ReadingEntry>, List<Int>> {
        val entries = mutableListOf<ReadingEntry>()
        val order = mutableListOf<Int>()

        openRO().use { db ->
            db.prepare(
                "SELECT book_id, last_content_id, last_opened_at, favorited_at, " +
                "position_updated_at, updated_at, is_favorite, ck_record_id FROM reading_entries;"
            )?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    entries.add(mapRowToEntry(stmt))
                }
            }
            db.prepare("SELECT book_id FROM history_order ORDER BY position;")?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    order.add(stmt.columnInt(0))
                }
            }
        }
        return Pair(entries, order)
    }

    private fun mapRowToEntry(stmt: SQLiteStmt) = ReadingEntry(
        bookId = stmt.columnInt(0),
        lastContentId = if (stmt.columnType(1) == SQLiteDB.SQLITE_NULL) null else stmt.columnInt(1),
        lastOpenedAt = if (stmt.columnType(2) == SQLiteDB.SQLITE_NULL) null else stmt.columnLong(2),
        favoritedAt = if (stmt.columnType(3) == SQLiteDB.SQLITE_NULL) null else stmt.columnLong(3),
        positionUpdatedAt = if (stmt.columnType(4) == SQLiteDB.SQLITE_NULL) null else stmt.columnLong(4),
        updatedAt = stmt.columnLong(5),
        isFavorite = stmt.columnInt(6) != 0,
        ckRecordId = stmt.columnText(7)
    )

    // endregion

    // region Sync Pending

    /**
     * Tambahkan entry ke antrian sync pending.
     * Logic: delete wins — jika sudah ada 'delete' untuk recordId yang sama, jangan overwrite.
     *        Jika sudah ada 'upload' dan operasi baru adalah 'delete', hapus upload lama.
     */
    fun addPendingSync(ckRecordId: String, operation: String) {
        openRW().use { db ->
            if (operation == "upload") {
                // Jangan tambah upload jika sudah ada delete untuk ID yang sama
                var hasDelete = false
                db.prepare(
                    "SELECT COUNT(*) FROM sync_pending WHERE ck_record_id = ? AND operation = 'delete';"
                )?.use { stmt ->
                    stmt.bindText(1, ckRecordId)
                    if (stmt.step() == SQLiteDB.SQLITE_ROW) hasDelete = stmt.columnInt(0) > 0
                }
                if (hasDelete) return
            } else if (operation == "delete") {
                // Hapus upload lama jika ada
                db.prepare(
                    "DELETE FROM sync_pending WHERE ck_record_id = ? AND operation = 'upload';"
                )?.use { stmt ->
                    stmt.bindText(1, ckRecordId)
                    stmt.step()
                }
            }
            db.prepare(
                "INSERT OR REPLACE INTO sync_pending (ck_record_id, operation, queued_at) VALUES (?, ?, ?);"
            )?.use { stmt ->
                stmt.bindText(1, ckRecordId)
                stmt.bindText(2, operation)
                stmt.bindLong(3, System.currentTimeMillis())
                stmt.step()
            }
        }
    }

    fun removePendingSync(ckRecordIds: List<String>) {
        if (ckRecordIds.isEmpty()) return
        openRW().use { db ->
            for (chunk in ckRecordIds.chunked(900)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val sql = "DELETE FROM sync_pending WHERE ck_record_id IN ($placeholders);"
                db.prepare(sql)?.use { stmt ->
                    chunk.forEachIndexed { index, id ->
                        stmt.bindText(index + 1, id)
                    }
                    stmt.step()
                }
            }
        }
    }

    fun fetchPendingSync(operation: String): List<String> {
        val result = mutableListOf<String>()
        openRO().use { db ->
            db.prepare(
                "SELECT ck_record_id FROM sync_pending WHERE operation = ? ORDER BY queued_at ASC;"
            )?.use { stmt ->
                stmt.bindText(1, operation)
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    stmt.columnText(0)?.let { result.add(it) }
                }
            }
        }
        return result
    }

    // endregion

    // region Batch (untuk applyCloudKitChanges)

    fun applyCloudKitBatch(
        upsertEntries: List<ReadingEntry>,
        deleteBookIds: List<Int>,
        newOrder: List<Int>
    ) {
        if (upsertEntries.isEmpty() && deleteBookIds.isEmpty() && newOrder.isEmpty()) return
        openRW().use { db ->
            db.prepare("BEGIN TRANSACTION;")?.use { it.step() }
            try {
                deleteBookIds.chunked(900).forEach { chunk ->
                    val placeholders = chunk.joinToString(",") { "?" }
                    db.prepare("DELETE FROM reading_entries WHERE book_id IN ($placeholders);")?.use { stmt ->
                        chunk.forEachIndexed { index, id ->
                            stmt.bindInt(index + 1, id)
                        }
                        stmt.step()
                    }
                }
                db.prepare(
                    """INSERT OR REPLACE INTO reading_entries
                       (book_id, last_content_id, last_opened_at, favorited_at,
                        position_updated_at, updated_at, is_favorite, ck_record_id)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?);"""
                )?.use { stmt ->
                    for (entry in upsertEntries) {
                        stmt.bindInt(1, entry.bookId)
                        if (entry.lastContentId != null) stmt.bindInt(2, entry.lastContentId)
                            else stmt.bindNull(2)
                        if (entry.lastOpenedAt != null) stmt.bindLong(3, entry.lastOpenedAt)
                            else stmt.bindNull(3)
                        if (entry.favoritedAt != null) stmt.bindLong(4, entry.favoritedAt)
                            else stmt.bindNull(4)
                        if (entry.positionUpdatedAt != null) stmt.bindLong(5, entry.positionUpdatedAt)
                            else stmt.bindNull(5)
                        stmt.bindLong(6, entry.updatedAt)
                        stmt.bindInt(7, if (entry.isFavorite) 1 else 0)
                        if (entry.ckRecordId != null) stmt.bindText(8, entry.ckRecordId)
                            else stmt.bindNull(8)
                        stmt.step()
                        stmt.reset()
                        stmt.clearBindings()
                    }
                }
                // Simpan order baru
                insertHistoryOrder(db, newOrder)
                db.prepare("COMMIT;")?.use { it.step() }
            } catch (e: Exception) {
                db.prepare("ROLLBACK;")?.use { it.step() }
                throw e
            }
        }
    }

    // endregion

    // region Migrasi dari JSON

    /**
     * One-shot migrasi dari user_data.json (format lama) ke SQLite.
     * Dipanggil sekali saat HistoryViewModel init.
     */
    fun migrateFromJsonIfNeeded(jsonFile: File, prefs: SharedPreferences) {
        if (prefs.getBoolean(MIGRATION_FLAG, false)) return
        if (!jsonFile.exists()) {
            prefs.edit { putBoolean(MIGRATION_FLAG, true) }
            return
        }
        try {
            val root = JSONObject(jsonFile.readText())
            val orderArray = root.optJSONArray("historyOrder")
            val entriesArray = root.optJSONArray("entries")

            val order = mutableListOf<Int>()
            if (orderArray != null) {
                for (i in 0 until orderArray.length()) order.add(orderArray.getInt(i))
            }

            val entries = mutableListOf<ReadingEntry>()
            if (entriesArray != null) {
                for (i in 0 until entriesArray.length()) {
                    val obj = entriesArray.getJSONObject(i)
                    entries.add(ReadingEntry(
                        bookId = obj.getInt("bookId"),
                        lastContentId = if (obj.has("lastContentId")) obj.getInt("lastContentId") else null,
                        lastOpenedAt = if (obj.has("lastOpenedAt")) obj.getLong("lastOpenedAt") else null,
                        favoritedAt = if (obj.has("favoritedAt")) obj.getLong("favoritedAt") else null,
                        positionUpdatedAt = if (obj.has("positionUpdatedAt")) obj.getLong("positionUpdatedAt") else null,
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                        isFavorite = obj.optBoolean("isFavorite", false),
                        ckRecordId = if (obj.has("ckRecordId")) obj.getString("ckRecordId") else null
                    ))
                }
            }

            openRW().use { db ->
                db.prepare("BEGIN TRANSACTION;")?.use { it.step() }
                try {
                    db.prepare(
                        """INSERT OR REPLACE INTO reading_entries
                           (book_id, last_content_id, last_opened_at, favorited_at,
                            position_updated_at, updated_at, is_favorite, ck_record_id)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?);"""
                    )?.use { stmt ->
                        for (entry in entries) {
                            stmt.bindInt(1, entry.bookId)
                            if (entry.lastContentId != null) stmt.bindInt(2, entry.lastContentId)
                                else stmt.bindNull(2)
                            if (entry.lastOpenedAt != null) stmt.bindLong(3, entry.lastOpenedAt)
                                else stmt.bindNull(3)
                            if (entry.favoritedAt != null) stmt.bindLong(4, entry.favoritedAt)
                                else stmt.bindNull(4)
                            if (entry.positionUpdatedAt != null) stmt.bindLong(5, entry.positionUpdatedAt)
                                else stmt.bindNull(5)
                            stmt.bindLong(6, entry.updatedAt)
                            stmt.bindInt(7, if (entry.isFavorite) 1 else 0)
                            if (entry.ckRecordId != null) stmt.bindText(8, entry.ckRecordId)
                                else stmt.bindNull(8)
                            stmt.step()
                            stmt.reset()
                            stmt.clearBindings()
                        }
                    }
                    insertHistoryOrder(db, order)
                    db.prepare("COMMIT;")?.use { it.step() }
                } catch (e: Exception) {
                    db.prepare("ROLLBACK;")?.use { it.step() }
                    throw e
                }
            }

            jsonFile.delete()
            prefs.edit { putBoolean(MIGRATION_FLAG, true)}
        } catch (e: Exception) {
            e.printStackTrace()
            // Tidak set migration flag — coba lagi di launch berikutnya
        }
    }

    // endregion

    // region Helpers

    private fun openRW(): SQLiteDB {
        ensureSetup()
        return SQLiteDB(
            dbFile.absolutePath,
            SQLiteDB.SQLITE_OPEN_READWRITE or SQLiteDB.SQLITE_OPEN_CREATE or SQLiteDB.SQLITE_OPEN_FULLMUTEX
        )
    }

    private fun openRO(): SQLiteDB {
        ensureSetup()
        return SQLiteDB(
            dbFile.absolutePath,
            SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX
        )
    }

    // endregion
}
