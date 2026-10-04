package com.maktabah.database

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File

object FtsMigrationManager {
    private const val TAG = "FtsMigrationManager"
    const val CURRENT_FTS_VERSION = 5
    const val WORK_NAME = "FtsMigration"

    fun getArchiveFtsVersion(ftsFile: File): Int {
        if (!ftsFile.exists()) return 0
        var version = 0
        var db: SQLiteDB? = null
        try {
            db = SQLiteDB(ftsFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY)
            db.prepare("SELECT value FROM metadata WHERE key='fts_version' LIMIT 1;")?.use { stmt ->
                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    version = stmt.columnInt(0)
                }
            }
        } catch (e: Exception) {
            version = 0
        } finally {
            db?.close()
        }
        return version
    }

    fun getOutdatedArchives(context: Context): List<Int> {
        val filesDir = context.filesDir
        val outdated = mutableListOf<Int>()
        for (i in 1..20) {
            val archiveFile = File(filesDir, "$i.sqlite")
            val ftsFile = File(filesDir, "${i}_fts.sqlite")
            if (archiveFile.exists() && archiveFile.length() > 4096) {
                val version = getArchiveFtsVersion(ftsFile)
                if (version < CURRENT_FTS_VERSION) {
                    outdated.add(i)
                }
            }
        }
        Log.d(TAG, "Scanned archives. Outdated archives: $outdated")
        return outdated
    }

    fun startMigration(context: Context) {
        val migrationWork = OneTimeWorkRequestBuilder<FtsMigrationWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            migrationWork
        )
        Log.i(TAG, "Enqueued FtsMigrationWorker via WorkManager")
    }
}
