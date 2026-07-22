package com.maktabah.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.maktabah.MainActivity
import com.maktabah.R
import com.maktabah.models.BookDownloadState
import com.maktabah.models.IntegratePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class BookDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val activeJobs = ConcurrentHashMap<String, Job>()

    companion object {
        private var instance: BookDownloadService? = null
        private const val CHANNEL_ID = "book_download_channel"
        private const val NOTIFICATION_ID = 1001
        private const val COMPLETED_NOTIFICATION_ID = 1002

        const val ACTION_START_DOWNLOAD = "com.maktabah.action.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.maktabah.action.CANCEL_DOWNLOAD"
        const val EXTRA_STATE_ID = "extra_state_id"

        private val _activeDownloadStates = MutableStateFlow<List<BookDownloadState>>(emptyList())
        val activeDownloadStates: StateFlow<List<BookDownloadState>> = _activeDownloadStates.asStateFlow()

        fun updateStates(transform: (List<BookDownloadState>) -> List<BookDownloadState>) {
            _activeDownloadStates.update(transform)
        }

        fun getState(id: String): BookDownloadState? {
            return _activeDownloadStates.value.find { it.id == id }
        }

        fun startDownload(context: Context, stateId: String) {
            val intent = Intent(context, BookDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_STATE_ID, stateId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancelDownload(stateId: String) {
            instance?.cancelDownloadInternal(stateId) ?: run {
                updateStates { states -> states.filter { it.id != stateId } }
            }
        }

        fun cancelDownload(context: Context, stateId: String) {
            val intent = Intent(context, BookDownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_STATE_ID, stateId)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val stateId = intent?.getStringExtra(EXTRA_STATE_ID)

        when (action) {
            ACTION_START_DOWNLOAD -> {
                if (stateId != null) {
                    startDownloadInternal(stateId)
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                if (stateId != null) {
                    cancelDownloadInternal(stateId)
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startDownloadInternal(stateId: String) {
        val currentState = getState(stateId) ?: return

        updateStates { states ->
            states.map {
                if (it.id == stateId) it.copy(isDownloading = true, progress = 0, error = null) else it
            }
        }

        updateNotification()

        val job = serviceScope.launch {
            val manager = BookDownloadManager(applicationContext)
            val index = try { manager.fetchIndex() } catch (e: Exception) { emptyList() }

            if (currentState.isBulk) {
                val toDownload = currentState.bulkBookIds
                var successCount = 0

                for ((idx, bookId) in toDownload.withIndex()) {
                    val entry = index.find { it.bkid == bookId }
                    if (entry != null) {
                        val overallStartProgress = (idx * 100) / toDownload.size
                        updateStates { states ->
                            states.map {
                                if (it.id == stateId) it.copy(progress = overallStartProgress) else it
                            }
                        }
                        updateNotification()

                        val success = manager.downloadBook(
                            entry,
                            onPhaseChanged = { phase ->
                                updateStates { states ->
                                    states.map {
                                        if (it.id == stateId) it.copy(phase = phase) else it
                                    }
                                }
                                updateNotification()
                            },
                            onProgress = { progress ->
                                val overallProgress = (idx * 100 + progress) / toDownload.size
                                updateStates { states ->
                                    states.map {
                                        if (it.id == stateId) it.copy(progress = overallProgress) else it
                                    }
                                }
                                updateNotification()
                            }
                        )

                        if (success) {
                            markBookAsDownloaded(bookId)
                            successCount++
                        }
                    }
                }

                updateStates { states -> states.filter { it.id != stateId } }
                showCompletedNotification(
                    applicationContext.getString(R.string.download_notification_completed_title),
                    applicationContext.getString(R.string.library_selected_books_count, successCount)
                )
            } else {
                val entry = index.find { it.bkid == currentState.bookId }
                if (entry != null) {
                    val success = manager.downloadBook(
                        entry,
                        onPhaseChanged = { phase ->
                            updateStates { states ->
                                states.map {
                                    if (it.id == stateId) it.copy(phase = phase) else it
                                }
                            }
                            updateNotification()
                        },
                        onProgress = { progress ->
                            updateStates { states ->
                                states.map {
                                    if (it.id == stateId) it.copy(progress = progress) else it
                                }
                            }
                            updateNotification()
                        }
                    )

                    if (success) {
                        markBookAsDownloaded(currentState.bookId)
                        updateStates { states -> states.filter { it.id != stateId } }
                        showCompletedNotification(
                            applicationContext.getString(R.string.download_notification_completed_title),
                            currentState.bookName
                        )
                    } else {
                        updateStates { states ->
                            states.map {
                                if (it.id == stateId) it.copy(
                                    isDownloading = false,
                                    error = applicationContext.getString(R.string.library_download_failed)
                                ) else it
                            }
                        }
                    }
                } else {
                    updateStates { states ->
                        states.map {
                            if (it.id == stateId) it.copy(
                                isDownloading = false,
                                error = applicationContext.getString(R.string.library_book_not_found_index)
                            ) else it
                        }
                    }
                }
            }

            activeJobs.remove(stateId)
            checkStopService()
        }

        activeJobs[stateId] = job
    }

    private fun cancelDownloadInternal(stateId: String) {
        activeJobs[stateId]?.cancel()
        activeJobs.remove(stateId)
        updateStates { states -> states.filter { it.id != stateId } }
        checkStopService()
    }

    private fun markBookAsDownloaded(bookId: Int) {
        BookDownloadNotifier.notifyBookDownloaded(bookId)
    }

    private fun checkStopService() {
        val hasActiveDownloads = _activeDownloadStates.value.any { it.isDownloading }
        if (!hasActiveDownloads) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification()
        }
    }

    private fun updateNotification() {
        val activeDownloading = _activeDownloadStates.value.filter { it.isDownloading }
        if (activeDownloading.isEmpty()) {
            return
        }

        val firstState = activeDownloading.first()
        val title = if (activeDownloading.size > 1) {
            applicationContext.getString(R.string.download_overlay_bulk_title)
        } else {
            firstState.bookName
        }

        val contentText = when (firstState.phase) {
            IntegratePhase.DOWNLOAD -> applicationContext.getString(R.string.download_overlay_downloading, firstState.progress)
            IntegratePhase.DATA -> applicationContext.getString(R.string.download_overlay_integrating_data)
            IntegratePhase.FTS -> applicationContext.getString(R.string.download_overlay_integrating_fts)
        }

        val notification = createNotification(title, contentText, firstState.progress, firstState.phase == IntegratePhase.DOWNLOAD)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(
        title: String,
        contentText: String,
        progress: Int,
        isProgressDeterminate: Boolean
    ) = NotificationCompat.Builder(this, CHANNEL_ID).apply {
        setContentTitle(title)
        setContentText(contentText)
        setSmallIcon(R.drawable.ic_cloud)
        setOngoing(true)
        setOnlyAlertOnce(true)
        if (isProgressDeterminate) {
            setProgress(100, progress, false)
        } else {
            setProgress(0, 0, true)
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setContentIntent(pendingIntent)
    }.build()

    private fun showCompletedNotification(title: String, message: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_cloud)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(COMPLETED_NOTIFICATION_ID + (System.currentTimeMillis() % 1000).toInt(), notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.download_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = applicationContext.getString(R.string.download_notification_channel_desc)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
