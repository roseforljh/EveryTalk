package com.example.everytalk.statecontroller.viewmodel

import android.app.Application
import android.util.Log
import com.example.everytalk.data.DataClass.GithubRelease
import com.example.everytalk.data.network.ApiClient
import com.example.everytalk.util.VersionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 管理应用更新检查
 */
class UpdateManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val showSnackbar: (String) -> Unit
) {
    private val _latestReleaseInfo = MutableStateFlow<GithubRelease?>(null)
    val latestReleaseInfo: StateFlow<GithubRelease?> = _latestReleaseInfo.asStateFlow()
    
    fun checkForUpdates() {
        scope.launch(Dispatchers.IO) {
            try {
                val latestRelease = ApiClient.getLatestRelease()
                val currentVersion = application.packageManager
                    .getPackageInfo(application.packageName, 0).versionName
                
                if (currentVersion != null && 
                    VersionChecker.isNewVersionAvailable(currentVersion, latestRelease.tagName)) {
                    _latestReleaseInfo.value = latestRelease
                } else {
                    withContext(Dispatchers.Main) {
                        showSnackbar("当前已是最新版本")
                    }
                }
            } catch (e: Exception) {
                Log.e("UpdateManager", "Failed to check for updates", e)
                withContext(Dispatchers.Main) {
                    showSnackbar("检查更新失败: ${e.message}")
                }
            }
        }
    }
    
    fun clearUpdateInfo() {
        _latestReleaseInfo.value = null
    }
}
