package com.example.everytalk.config

import com.example.everytalk.BuildConfig

/**
 * Modern, build-aware backend configuration provider.
 *
 * This object safely retrieves backend settings directly from the generated
 * BuildConfig class. This approach eliminates the need for asset-based JSON
 * files, enhancing security and simplifying build variant management.
 */
object BackendConfig {

    /**
     * A list of backend URLs, populated from the build configuration.
     * The URLs are parsed from a comma-separated string.
     */
    val backendUrls: List<String> by lazy {
        val configUrls = BuildConfig.BACKEND_URLS
        // 避免日志泄露完整后端地址，仅记录数量
        // android.util.Log.d("BackendConfig", "原始配置URLs: '$configUrls'")
        
        if (configUrls.isBlank()) {
            android.util.Log.e("BackendConfig", "BuildConfig.BACKEND_URLS 为空或空白!")
            emptyList()
        } else {
            val urlList = configUrls
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            
            android.util.Log.d("BackendConfig", "解析出的后端URL数量: ${urlList.size}")
            urlList
        }
    }

    /**
     * Determines if concurrent requests to multiple backends are enabled.
     * This value is sourced directly from the build configuration.
     */
    val isConcurrentRequestEnabled: Boolean by lazy {
        BuildConfig.CONCURRENT_REQUEST_ENABLED
    }

    // Default values for other configurations, which were previously in JSON.
    // These can be moved to BuildConfig fields as well if they need to vary by build type.
    const val TIMEOUT_MS: Long = 30000
    const val RACE_TIMEOUT_MS: Long = 10000
    const val FIRST_RESPONSE_TIMEOUT_MS: Long = 17_000
    const val IS_FALLBACK_ENABLED: Boolean = true


}