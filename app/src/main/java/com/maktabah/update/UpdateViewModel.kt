package com.maktabah.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class UpdateViewModel(
    private val repository: UpdateRepository,
    private val manager: UpdateManager
) : ViewModel() {

    var updateState by mutableStateOf<UpdateUIState>(UpdateUIState.Idle)
        private set

    fun checkForUpdates() {
        if (updateState != UpdateUIState.Idle) return
        if (!manager.shouldCheckForUpdates()) return
        
        updateState = UpdateUIState.Checking
        viewModelScope.launch {
            val release = repository.getLatestRelease()
            if (release != null && manager.isUpdateAvailable(release.tagName)) {
                updateState = UpdateUIState.UpdateAvailable(release)
            } else {
                updateState = UpdateUIState.Idle
            }
        }
    }

    fun downloadAndInstall(release: GitHubRelease) {
        val apkAsset = release.assets.find { it.name.endsWith(".apk") }
        if (apkAsset == null) {
            updateState = UpdateUIState.Error("No APK found in release assets")
            return
        }

        updateState = UpdateUIState.Downloading(0)
        viewModelScope.launch {
            val file = manager.downloadApk(apkAsset.browserDownloadUrl) { progress ->
                updateState = UpdateUIState.Downloading(progress)
            }
            
            if (file != null) {
                updateState = UpdateUIState.Installing
                manager.installApk(file)
            } else {
                updateState = UpdateUIState.Error("Failed to download update")
            }
        }
    }

    fun dismiss() {
        if (updateState is UpdateUIState.UpdateAvailable) {
            manager.markUpdateSkipped()
        }
        updateState = UpdateUIState.Idle
    }
}
