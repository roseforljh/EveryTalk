package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ApiConfig

object WebSearchSupport {
    fun supportsNativeWebSearch(config: ApiConfig?): Boolean {
        if (config == null) return false
        return isGeminiNativeSearch(config) || isQwenNativeSearch(config)
    }

    fun isGeminiNativeSearch(config: ApiConfig?): Boolean {
        if (config == null) return false
        return config.channel.contains("gemini", ignoreCase = true) &&
            config.model.contains("gemini", ignoreCase = true)
    }

    fun isQwenNativeSearch(config: ApiConfig?): Boolean {
        if (config == null) return false
        return config.model.contains("qwen", ignoreCase = true)
    }

    fun shouldEnableQwenNativeSearch(config: ApiConfig?, isWebSearchEnabled: Boolean): Boolean {
        return isWebSearchEnabled && isQwenNativeSearch(config)
    }
}
