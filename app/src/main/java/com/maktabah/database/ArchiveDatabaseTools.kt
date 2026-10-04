package com.maktabah.database

object ArchiveDatabaseTools {
    fun hasUnifiedFTS(db: SQLiteDB, schemaName: String = "main"): Boolean {
        var hasFts = false
        val sql = "SELECT count(*) FROM $schemaName.sqlite_master WHERE type='table' AND name='archive_fts';"
        try {
            db.prepare(sql)?.use { stmt ->
                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    hasFts = stmt.columnInt(0) > 0
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return hasFts
    }
}
