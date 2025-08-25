package com.example.everytalk.config

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

@Serializable
data class BackendConfiguration(
    val backend_urls: List<String>,
    val primary_url: String,
    val fallback_enabled: Boolean = true,
    val timeout_ms: Long = 60000,
    val concurrent_request_enabled: Boolean = true,
    val race_timeout_ms: Long = 10000
)

object BackendConfig {
    private var config: BackendConfiguration? = null
    private val json = Json { ignoreUnknownKeys = true }
    
    fun initialize(context: Context, isDebugMode: Boolean = false) {
        if (config != null) return
        
        val configFileName = if (isDebugMode) "backend_config_debug.json" else "backend_config.json"
        try {
            val configJson = context.assets.open(configFileName).bufferedReader().use { it.readText() }
            config = json.decodeFromString(BackendConfiguration.serializer(), configJson)
            android.util.Log.d("BackendConfig", "Backend config loaded successfully from $configFileName")
        } catch (e: IOException) {
            android.util.Log.e("BackendConfig", "Failed to load backend config from $configFileName", e)
            // 使用空的默认配置，避免暴露接口地址
            config = BackendConfiguration(
                backend_urls = emptyList(),
                primary_url = "",
                fallback_enabled = true,
                timeout_ms = 30000,
                concurrent_request_enabled = true,
                race_timeout_ms = 10000
            )
        } catch (e: Exception) {
            android.util.Log.e("BackendConfig", "Failed to parse backend config from $configFileName", e)
            // 使用空的默认配置，避免暴露接口地址
            config = BackendConfiguration(
                backend_urls = emptyList(),
                primary_url = "",
                fallback_enabled = true,
                timeout_ms = 30000,
                concurrent_request_enabled = true,
                race_timeout_ms = 10000
            )
        }
    }
    
    fun getBackendUrls(): List<String> {
        return config?.backend_urls ?: emptyList()
    }
    
    fun getPrimaryUrl(): String {
        return config?.primary_url ?: ""
    }
    
    fun isFallbackEnabled(): Boolean {
        return config?.fallback_enabled ?: true
    }
    
    fun getTimeoutMs(): Long {
        return config?.timeout_ms ?: 30000
    }
    
    fun isConcurrentRequestEnabled(): Boolean {
        return config?.concurrent_request_enabled ?: true
    }
    
    fun getRaceTimeoutMs(): Long {
        return config?.race_timeout_ms ?: 10000
    }
    
    /**
     * 初始化调试模式配置
     * 使用本地调试配置文件 backend_config_debug.json
     */
    fun initializeDebugMode(context: Context) {
        initialize(context, isDebugMode = true)
    }
}