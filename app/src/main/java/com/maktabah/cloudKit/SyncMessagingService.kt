package com.maktabah.cloudKit

import android.util.Log
import androidx.core.content.edit
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.maktabah.database.AnnotationManager
import com.maktabah.ui.common.registerFcmToken
import com.maktabah.ui.history.HistoryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class SyncMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        if (remoteMessage.data["action"] == "SYNC_CLOUDKIT") {
            Log.d("SyncMessagingService", "Received SYNC_CLOUDKIT action")
            
            val annotationsDbFile = File(applicationContext.filesDir, "annotations.sqlite")
            val annotationManager = AnnotationManager(annotationsDbFile)
            val historyViewModel = HistoryViewModel()
            historyViewModel.initialize(applicationContext)
            val cloudKitSyncManager = CloudKitSyncManager()
            
            CoroutineScope(Dispatchers.IO).launch {
                val result = cloudKitSyncManager.fetchChanges(
                    applicationContext,
                    annotationManager,
                    historyViewModel
                )
                Log.d("SyncMessagingService", "Sync result: $result")
                HistoryViewModel.notifyRefresh()
            }
        } else if (remoteMessage.data["action"] == "DONATION_SUCCESS") {
            Log.d("SyncMessagingService", "Received DONATION_SUCCESS action")
            val prefs = applicationContext.getSharedPreferences("main_prefs", MODE_PRIVATE)
            prefs.edit { putBoolean("has_donated", true).apply() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onNewToken(token: String) {
        Log.d("SyncMessagingService", "Refreshed token: $token")
        registerFcmToken(applicationContext, token)
    }
}
