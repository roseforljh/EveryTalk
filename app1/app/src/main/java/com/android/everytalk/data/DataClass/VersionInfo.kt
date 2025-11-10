package com.android.everytalk.data.DataClass

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GitHub Release API 响应数据模型
 * 用于解析从 https://api.github.com/repos/roseforljh/EveryTalk/releases/latest 返回的JSON
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String,  // 例如: "v1.6.4"
    
    @SerialName("name")
    val name: String?,  // Release 名称
    
    @SerialName("body")
    val body: String?,  // Release 说明
    
    @SerialName("html_url")
    val htmlUrl: String,  // GitHub Release 页面 URL
    
    @SerialName("published_at")
    val publishedAt: String?,  // 发布时间
    
    @SerialName("prerelease")
    val prerelease: Boolean = false  // 是否为预发布版本
)

/**
 * 版本更新信息
 * 用于在应用内部传递和处理版本更新逻辑
 */
data class VersionUpdateInfo(
    val currentVersion: String,  // 当前应用版本，例如: "1.6.3"
    val latestVersion: String,   // 最新版本，例如: "1.6.4"
    val releaseUrl: String,      // GitHub Release 页面 URL
    val releaseNotes: String?,   // 更新说明
    val isForceUpdate: Boolean   // 是否强制更新（相差3个或以上版本）
) {
    /**
     * 检查是否有新版本
     */
    fun hasUpdate(): Boolean {
        return compareVersions(currentVersion, latestVersion) < 0
    }
    
    companion object {
        /**
         * 比较两个版本号
         * @return 负数表示 v1 < v2，0表示相等，正数表示 v1 > v2
         */
        fun compareVersions(v1: String, v2: String): Int {
            // 移除 'v' 前缀（如果有）
            val version1 = v1.removePrefix("v")
            val version2 = v2.removePrefix("v")
            
            val parts1 = version1.split(".").map { it.toIntOrNull() ?: 0 }
            val parts2 = version2.split(".").map { it.toIntOrNull() ?: 0 }
            
            val maxLength = maxOf(parts1.size, parts2.size)
            
            for (i in 0 until maxLength) {
                val part1 = parts1.getOrNull(i) ?: 0
                val part2 = parts2.getOrNull(i) ?: 0
                
                if (part1 != part2) {
                    return part1 - part2
                }
            }
            
            return 0
        }
        
        /**
         * 计算两个版本之间的差距（主要版本号差异）
         * 例如: 1.6.3 和 1.9.5 差距为 3
         */
        fun getVersionGap(v1: String, v2: String): Int {
            val version1 = v1.removePrefix("v")
            val version2 = v2.removePrefix("v")
            
            val parts1 = version1.split(".").map { it.toIntOrNull() ?: 0 }
            val parts2 = version2.split(".").map { it.toIntOrNull() ?: 0 }
            
            // 计算次版本号的差距（第二位）
            val minor1 = parts1.getOrNull(1) ?: 0
            val minor2 = parts2.getOrNull(1) ?: 0
            
            return kotlin.math.abs(minor2 - minor1)
        }
        
        /**
         * 判断是否需要强制更新
         * 如果版本差距 >= 3，则需要强制更新
         */
        fun shouldForceUpdate(currentVersion: String, latestVersion: String): Boolean {
            return getVersionGap(currentVersion, latestVersion) >= 3
        }
    }
}