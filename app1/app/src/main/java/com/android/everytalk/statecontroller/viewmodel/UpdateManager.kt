package com.android.everytalk.statecontroller.viewmodel

import android.app.Application
import android.util.Log
import com.android.everytalk.data.DataClass.GitHubRelease
import com.android.everytalk.data.DataClass.VersionUpdateInfo
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.util.VersionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 管理应用更新检查
 * 支持静默检查和强制更新逻辑
 */
class UpdateManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val showSnackbar: (String) -> Unit
) {
    private val _latestReleaseInfo = MutableStateFlow<GitHubRelease?>(null)
    val latestReleaseInfo: StateFlow<GitHubRelease?> = _latestReleaseInfo.asStateFlow()
    
    private val _updateInfo = MutableStateFlow<VersionUpdateInfo?>(null)
    val updateInfo: StateFlow<VersionUpdateInfo?> = _updateInfo.asStateFlow()

    // 防抖/互斥：避免并发或重复触发导致多个对话层叠
    private val isChecking = AtomicBoolean(false)
    
    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_RELEASES_URL = "https://github.com/roseforljh/EveryTalk/releases"
    }

    /**
     * 是否已有更新对话处于激活（依据 updateInfo 是否非空）
     */
    fun isUpdateDialogActive(): Boolean = _updateInfo.value != null
    
    /**
     * 手动检查更新（用户主动触发）
     * 会显示"当前已是最新版本"的提示
     */
    fun checkForUpdates() {
        scope.launch(Dispatchers.IO) {
            // 若已有更新信息在显示，复用现有对话，不再重复请求/弹第二个
            if (_updateInfo.value != null) {
                Log.d(TAG, "手动检查被忽略：更新对话已激活")
                withContext(Dispatchers.Main) {
                    showSnackbar("已检测到新版本，正在显示更新信息")
                }
                return@launch
            }
            if (!isChecking.compareAndSet(false, true)) {
                Log.d(TAG, "手动检查被忽略：已有检查进行中")
                return@launch
            }
            try {
                val latestRelease = ApiClient.getLatestRelease()
                val currentVersion = application.packageManager
                    .getPackageInfo(application.packageName, 0).versionName
                
                if (currentVersion != null &&
                    VersionChecker.isNewVersionAvailable(currentVersion, latestRelease.tagName)) {
                    _latestReleaseInfo.value = latestRelease
                    
                    // 构建更新信息
                    val updateInfo = buildUpdateInfo(currentVersion, latestRelease)
                    _updateInfo.value = updateInfo
                    
                    Log.d(TAG, "发现新版本: ${latestRelease.tagName}, 强制更新: ${updateInfo.isForceUpdate}")
                } else {
                    withContext(Dispatchers.Main) {
                        showSnackbar("当前已是最新版本")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
                withContext(Dispatchers.Main) {
                    showSnackbar("检查更新失败: ${e.message}")
                }
            } finally {
                isChecking.set(false)
            }
        }
    }
    
    /**
     * 静默检查更新（应用启动时自动触发）
     * 仅在有新版本时显示对话框，不显示"已是最新版本"提示
     */
    fun checkForUpdatesSilently() {
        scope.launch(Dispatchers.IO) {
            // 若已有更新信息在显示，则跳过静默显示，避免与手动触发叠加
            if (_updateInfo.value != null) {
                Log.d(TAG, "静默检查跳过：更新对话已激活")
                return@launch
            }
            if (!isChecking.compareAndSet(false, true)) {
                Log.d(TAG, "静默检查跳过：已有检查进行中")
                return@launch
            }
            try {
                val latestRelease = ApiClient.getLatestRelease()
                val currentVersion = application.packageManager
                    .getPackageInfo(application.packageName, 0).versionName
                
                if (currentVersion != null &&
                    VersionChecker.isNewVersionAvailable(currentVersion, latestRelease.tagName)) {
                    _latestReleaseInfo.value = latestRelease
                    
                    // 构建更新信息
                    val updateInfo = buildUpdateInfo(currentVersion, latestRelease)
                    _updateInfo.value = updateInfo
                    
                    Log.d(TAG, "静默检查发现新版本: ${latestRelease.tagName}, 强制更新: ${updateInfo.isForceUpdate}")
                } else {
                    Log.d(TAG, "静默检查: 当前已是最新版本")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Silent update check failed", e)
                // 静默检查失败不显示错误提示
            } finally {
                isChecking.set(false)
            }
        }
    }
    
    /**
     * 构建版本更新信息
     */
    private fun buildUpdateInfo(currentVersion: String, release: GitHubRelease): VersionUpdateInfo {
        val latestVersion = release.tagName.removePrefix("v")
        val isForceUpdate = VersionUpdateInfo.shouldForceUpdate(currentVersion, latestVersion)
        
        return VersionUpdateInfo(
            currentVersion = currentVersion,
            latestVersion = latestVersion,
            releaseUrl = release.htmlUrl,
            releaseNotes = release.body,
            isForceUpdate = isForceUpdate
        )
    }
    
    /**
     * 清除更新信息
     */
    fun clearUpdateInfo() {
        _latestReleaseInfo.value = null
        _updateInfo.value = null
    }
    
    /**
     * 获取GitHub Releases页面URL
     */
    fun getReleasesUrl(): String = GITHUB_RELEASES_URL
}
