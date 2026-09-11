package com.android.everytalk.data.computer

import android.content.Context
import com.android.everytalk.BuildConfig

/**
 * 新能力的本地发布开关。
 * 默认只开启 local_bash；Cloudflare 写操作保持关闭，待 OAuth 配置和联调完成后再逐步开启。
 * 关闭开关只停止新请求，不删除 Workspace、Computer 或云端资源。
 */
class ComputerFeatureFlags(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    val localBashEnabled: Boolean get() = preferences.getBoolean(KEY_LOCAL_BASH, true)
    // 没有 OAuth Client ID 时保持关闭，避免 UI 能保存半成品后 Agent 才失败；
    // 配置到本地安全构建参数后，读取能力可直接进入内测。
    val cloudflareEnabled: Boolean get() = preferences.getBoolean(KEY_CLOUDFLARE, BuildConfig.CLOUDFLARE_OAUTH_CLIENT_ID.isNotBlank())
    val cloudflareWorkerWriteEnabled: Boolean get() = preferences.getBoolean(KEY_WORKER_WRITE, false)
    val cloudflareResourceToolsEnabled: Boolean get() = preferences.getBoolean(KEY_RESOURCE_TOOLS, false)
    val temporaryWorkerEnabled: Boolean get() = preferences.getBoolean(KEY_TEMPORARY_WORKER, false)

    fun setLocalBashEnabled(enabled: Boolean) = preferences.edit().putBoolean(KEY_LOCAL_BASH, enabled).apply()
    fun setCloudflareEnabled(enabled: Boolean) = preferences.edit().putBoolean(KEY_CLOUDFLARE, enabled).apply()
    fun setCloudflareWorkerWriteEnabled(enabled: Boolean) = preferences.edit().putBoolean(KEY_WORKER_WRITE, enabled).apply()
    fun setCloudflareResourceToolsEnabled(enabled: Boolean) = preferences.edit().putBoolean(KEY_RESOURCE_TOOLS, enabled).apply()
    fun setTemporaryWorkerEnabled(enabled: Boolean) = preferences.edit().putBoolean(KEY_TEMPORARY_WORKER, enabled).apply()

    private companion object {
        const val FILE_NAME = "computer_feature_flags"
        const val KEY_LOCAL_BASH = "local_bash_enabled"
        const val KEY_CLOUDFLARE = "cloudflare_enabled"
        const val KEY_WORKER_WRITE = "cloudflare_worker_write_enabled"
        const val KEY_RESOURCE_TOOLS = "cloudflare_resource_tools_enabled"
        const val KEY_TEMPORARY_WORKER = "temporary_worker_enabled"
    }
}
