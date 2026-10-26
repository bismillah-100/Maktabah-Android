package com.maktabah.database.updater

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.maktabah.config.AppConfig
import com.maktabah.database.BookUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class BookUpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting periodic book update check...")
            val updates = BookUpdateManager.fetchAvailableUpdates(applicationContext)
            val count = updates.count { it.needsUpdate }
            val now = System.currentTimeMillis()

            val prefs = applicationContext.getSharedPreferences(
                AppConfig.Prefs.LIBRARY_PREFS,
                Context.MODE_PRIVATE,
            )
            prefs.edit {
                putLong(AppConfig.Prefs.KEY_LAST_UPDATE_CHECK, now)
                putInt(AppConfig.Prefs.KEY_CACHED_UPDATE_COUNT, count)
            }
            Log.d(TAG, "Periodic check completed. Found $count available updates.")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Periodic book update check failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "BookUpdateCheckWorker"

        fun enqueuePeriodicCheck(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<BookUpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                AppConfig.WorkManagerKeys.BOOK_UPDATE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
