package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ApiConfig

object WebSearchSupport {
    data class WebSearchRouting(
        val useNativeWebSearch: Boolean,
        val externalProvider: ExternalWebSearchProvider? = null,
    )

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

    fun resolveWebSearchRouting(
        config: ApiConfig?,
        isWebSearchEnabled: Boolean,
        selectedExternalProvider: ExternalWebSearchProvider?,
        selectedExternalProviderApiKey: String,
    ): WebSearchRouting {
        if (!isWebSearchEnabled) {
            return WebSearchRouting(useNativeWebSearch = false)
        }
        if (supportsNativeWebSearch(config)) {
            return WebSearchRouting(useNativeWebSearch = true)
        }
        if (selectedExternalProvider != null && selectedExternalProviderApiKey.isNotBlank()) {
            return WebSearchRouting(
                useNativeWebSearch = false,
                externalProvider = selectedExternalProvider,
            )
        }
        return WebSearchRouting(useNativeWebSearch = false)
    }
}
