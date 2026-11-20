package com.maktabah.database.updater

import android.content.Context
import androidx.core.content.edit
import com.maktabah.config.AppConfig
import com.maktabah.database.BookUpdateManager
import com.maktabah.models.AuthIndexEntry
import com.maktabah.models.BookIndexEntry
import com.maktabah.models.BookUpdateItem
import com.maktabah.models.BookUpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BookUpdateRepository(
    private val context: Context,
    private val updateManager: BookUpdateManager = BookUpdateManager,
) {
    fun getCachedUpdateCount(): Int {
        val prefs = context.getSharedPreferences(AppConfig.Prefs.LIBRARY_PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(AppConfig.Prefs.KEY_CACHED_UPDATE_COUNT, 0)
    }

    fun getLastUpdateCheck(): Long {
        val prefs = context.getSharedPreferences(AppConfig.Prefs.LIBRARY_PREFS, Context.MODE_PRIVATE)
        return prefs.getLong(AppConfig.Prefs.KEY_LAST_UPDATE_CHECK, 0L)
    }

    fun saveUpdateCheck(now: Long, count: Int) {
        val prefs = context.getSharedPreferences(AppConfig.Prefs.LIBRARY_PREFS, Context.MODE_PRIVATE)
        prefs.edit {
            putLong(AppConfig.Prefs.KEY_LAST_UPDATE_CHECK, now)
            putInt(AppConfig.Prefs.KEY_CACHED_UPDATE_COUNT, count)
        }
    }

    suspend fun fetchAvailableUpdates(): List<BookUpdateItem> = withContext(Dispatchers.IO) {
        updateManager.fetchAvailableUpdates(context)
    }

    suspend fun fetchAuthIndexEntries(): List<AuthIndexEntry> = withContext(Dispatchers.IO) {
        updateManager.fetchAuthIndexEntries()
    }

    suspend fun stageBookDownload(
        entry: BookIndexEntry,
        authIndex: Map<Int, AuthIndexEntry>,
    ): BookUpdateManager.StagedBookUpdate = withContext(Dispatchers.IO) {
        updateManager.stageBookDownload(context, entry, authIndex)
    }

    suspend fun applyStagedBookUpdate(
        staged: BookUpdateManager.StagedBookUpdate,
    ): BookUpdateResult = withContext(Dispatchers.IO) {
        updateManager.applyStagedBookUpdate(context, staged)
    }

    fun cleanupWorkingDirectory() {
        updateManager.cleanupWorkingDirectory(context)
    }
}
