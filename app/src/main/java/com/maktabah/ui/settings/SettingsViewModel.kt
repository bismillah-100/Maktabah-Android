package com.maktabah.ui.settings

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maktabah.cloudKit.CloudKitCoreManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    private val _hasToken = MutableStateFlow(false)
    val hasToken: StateFlow<Boolean> = _hasToken.asStateFlow()

    private val _userName = MutableStateFlow<String?>(null)

    private val _hideTabsOnScroll = MutableStateFlow(true)
    val hideTabsOnScroll: StateFlow<Boolean> = _hideTabsOnScroll.asStateFlow()

    fun initialize(context: Context) {
        val ckPrefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
        val token = ckPrefs.getString("ckWebAuthToken", null)
        _hasToken.value = token != null
        if (token != null) {
            _userName.value = "Connected"
            viewModelScope.launch {
                val result = CloudKitCoreManager.shared.fetchUserInfo(context)
                result.onSuccess { json ->
                    // CloudKit user response typically has a 'userRecordName' or similar
                    // The exact structure depends on the bridge, but let's try to find a display name
                    val userRecordName = json.optString("userRecordName")
                    if (userRecordName.isNotEmpty()) {
                        _userName.value = userRecordName
                    }
                }
            }
        } else {
            _userName.value = null
        }

        val mainPrefs = context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE)
        _hideTabsOnScroll.value = mainPrefs.getBoolean("hide_tabs_on_scroll", true)
    }

    fun setHideTabsOnScroll(context: Context, enabled: Boolean) {
        _hideTabsOnScroll.value = enabled
        val mainPrefs = context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE)
        mainPrefs.edit { putBoolean("hide_tabs_on_scroll", enabled) }
    }

    fun logoutCloudKit(context: Context) {
        val ckPrefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
        val keysToRemove = ckPrefs.all.keys.filter { it.startsWith("ckSyncToken_") || it == "ckWebAuthToken" }
        ckPrefs.edit {
            keysToRemove.forEach { remove(it) }
        }
        _hasToken.value = false
        _userName.value = null
    }

    fun resetDonationStatus(context: Context, onReset: () -> Unit) {
        val mainPrefs = context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE)
        mainPrefs.edit { putBoolean("has_donated", false).apply() }
        onReset()
    }
}
