package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.data.DataClass.GitHubRelease
import com.android.everytalk.data.DataClass.VersionUpdateInfo
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * 版本检查服务
 * 负责从GitHub API获取最新版本信息
 */
class VersionCheckService {
    
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                prettyPrint = true
            })
        }
        
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 15000
        }
        
        // 设置默认请求头
        defaultRequest {
            header("Accept", "application/vnd.github.v3+json")
            header("User-Agent", "EveryTalk-Android-App")
        }
    }
    
    companion object {
        private const val TAG = "VersionCheckService"
        private const val GITHUB_API_URL = "https://api.github.com/repos/roseforljh/EveryTalk/releases/latest"
        private const val GITHUB_RELEASES_URL = "https://github.com/roseforljh/EveryTalk/releases"
    }
    
    /**
     * 检查应用更新
     * @param currentVersion 当前应用版本号
     * @return VersionUpdateInfo 或 null（如果检查失败）
     */
    suspend fun checkForUpdate(currentVersion: String): VersionUpdateInfo? {
        return try {
            Log.d(TAG, "开始检查更新，当前版本: $currentVersion")
            
            val release: GitHubRelease = client.get(GITHUB_API_URL).body()
            
            Log.d(TAG, "获取到最新版本: ${release.tagName}")
            
            // 移除 tag_name 中的 'v' 前缀
            val latestVersion = release.tagName.removePrefix("v")
            
            // 判断是否需要强制更新
            val isForceUpdate = VersionUpdateInfo.shouldForceUpdate(currentVersion, latestVersion)
            
            val updateInfo = VersionUpdateInfo(
                currentVersion = currentVersion,
                latestVersion = latestVersion,
                releaseUrl = release.htmlUrl,
                releaseNotes = release.body,
                isForceUpdate = isForceUpdate
            )
            
            Log.d(TAG, "版本检查完成 - 当前: $currentVersion, 最新: $latestVersion, 强制更新: $isForceUpdate")
            
            updateInfo
            
        } catch (e: Exception) {
            Log.e(TAG, "检查更新失败", e)
            null
        }
    }
    
    /**
     * 获取GitHub Releases页面URL
     */
    fun getReleasesUrl(): String = GITHUB_RELEASES_URL
    
    /**
     * 关闭HTTP客户端
     */
    fun close() {
        client.close()
    }
}