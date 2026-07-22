package com.maktabah

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.google.firebase.messaging.FirebaseMessaging
import com.maktabah.cloudKit.CloudKitSyncManager
import com.maktabah.database.AnnotationManager
import com.maktabah.models.AnnotationChange
import com.maktabah.downloader.ConnectivityMonitor
import com.maktabah.downloader.CoreDatabaseDownloader
import com.maktabah.manager.LibraryDataManager
import com.maktabah.ui.common.BootstrapScreen
import com.maktabah.ui.common.MainScreen
import com.maktabah.ui.common.UpdateDialog
import com.maktabah.ui.common.registerFcmToken
import com.maktabah.ui.library.LibraryViewModel
import com.maktabah.update.UpdateManager
import com.maktabah.update.UpdateRepository
import com.maktabah.update.UpdateViewModel
import okhttp3.OkHttpClient
import java.io.File

private val SepiaLightColorScheme = lightColorScheme(
    primary = Color(0xFF9C7A4E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5D9C2),
    onPrimaryContainer = Color(0xFF26231D),
    secondary = Color(0xFF9C7A4E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5D9C2),
    onSecondaryContainer = Color(0xFF26231D),
    tertiary = Color(0xFFB55F05),
    onTertiary = Color.White,
    background = Color(0xFFEDD9B8),
    onBackground = Color.Black,
    surface = Color(0xFFEFE3CC),
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE5D9C2),
    onSurfaceVariant = Color.DarkGray,
    outline = Color(0xFF9C7A4E)
)

private val SepiaDarkColorScheme = darkColorScheme(
    primary = Color(0xFFE9C099),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF36322A),
    onPrimaryContainer = Color(0xFFEDD9B8),
    secondary = Color(0xFFE9C099),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF4C4538),
    onSecondaryContainer = Color(0xFFEDD9B8),
    tertiary = Color(0xFFFF9300),
    onTertiary = Color.Black,
    background = Color(0xFF26231D),
    onBackground = Color.White,
    surface = Color(0xFF332F27),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF36322A),
    onSurfaceVariant = Color.LightGray,
    outline = Color(0xFFE9C099)
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Subscribe to FCM topic for global sync
        FirebaseMessaging.getInstance().subscribeToTopic("global_sync")
        registerFcmToken(this)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = if (darkTheme) SepiaDarkColorScheme else SepiaLightColorScheme
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var isBootstrapped by remember {
                        mutableStateOf(CoreDatabaseDownloader(this@MainActivity).areCoreFilesReady())
                    }

                    if (isBootstrapped) {
                        val client = remember { OkHttpClient() }
                        val updateRepository = remember { UpdateRepository(client) }
                        val updateManager = remember { UpdateManager(this@MainActivity, client) }
                        val updateViewModel = remember { UpdateViewModel(updateRepository, updateManager) }

                        LaunchedEffect(Unit) {
                            updateViewModel.checkForUpdates()
                        }

                        UpdateDialog(updateViewModel)

                        val mainDbFile = File(this@MainActivity.filesDir, "main.sqlite")
                        val dataManager = remember { LibraryDataManager(mainDbFile) }
                        val libraryViewModel: LibraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                            factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                                    return LibraryViewModel(dataManager) as T
                                }
                            }
                        )
                        LaunchedEffect(libraryViewModel) {
                            libraryViewModel.loadData(this@MainActivity)
                        }
                        val annotationsDbFile = File(this@MainActivity.filesDir, "annotations.sqlite")
                        val annotationManager = remember { AnnotationManager(annotationsDbFile) }
                        val cloudKitSyncManager = remember { CloudKitSyncManager() }

                        val connectivityMonitor = remember { ConnectivityMonitor(applicationContext) }
                        DisposableEffect(connectivityMonitor) {
                            connectivityMonitor.startMonitoring()
                            onDispose {
                                connectivityMonitor.stopMonitoring()
                            }
                        }

                        LaunchedEffect(connectivityMonitor.isOnline) {
                            var wasOffline = false
                            connectivityMonitor.isOnline.collect { isOnline ->
                                if (isOnline && wasOffline) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        cloudKitSyncManager.syncAnnotations(this@MainActivity, annotationManager)
                                    }
                                }
                                wasOffline = !isOnline
                            }
                        }
                        LaunchedEffect(annotationManager) {
                            AnnotationManager.updates.collect { change ->
                                val isLocal = when (change) {
                                    is AnnotationChange.Upsert -> !change.fromSync
                                    is AnnotationChange.Delete -> !change.fromSync
                                    is AnnotationChange.ReloadAll -> false
                                }
                                if (isLocal) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        cloudKitSyncManager.syncAnnotations(this@MainActivity, annotationManager)
                                    }
                                }
                            }
                        }

                        MainScreen(
                            libraryViewModel = libraryViewModel,
                            annotationManager = annotationManager,
                            cloudKitSyncManager = cloudKitSyncManager,
                            onCheckForUpdates = {
                                updateViewModel.checkForUpdates(
                                    force = true,
                                    onNoUpdate = {
                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            R.string.history_settings_no_update_found,
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            }
                        )
                    } else {
                        BootstrapScreen(onFinished = {
                            isBootstrapped = true
                        })
                    }
                }
            }
        }
    }
}
