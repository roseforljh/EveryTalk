package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ApiConfig

object WebSearchSupport {
    data class WebSearchRouting(
        val useNativeWebSearch: Boolean,
        val externalProvider: ExternalWebSearchProvider? = null,
        val useJinaSearch: Boolean = false,
    )

    fun supportsNativeWebSearch(config: ApiConfig?): Boolean {
        if (config == null) return false
        return isGeminiNativeSearch(config) || isQwenNativeSearch(config)
    }

    fun isGeminiModel(config: ApiConfig?): Boolean {
        if (config == null) return false
        return isGeminiModelName(config.model)
    }

    fun isGeminiModelName(model: String): Boolean {
        val normalized = model.substringAfterLast('/').lowercase()
        return normalized.startsWith("gemini")
    }

    fun isGeminiNativeSearch(config: ApiConfig?): Boolean {
        if (config == null) return false
        return config.channel.contains("gemini", ignoreCase = true) &&
            isGeminiModel(config)
    }

    fun isQwenNativeSearch(config: ApiConfig?): Boolean {
        if (config == null) return false
        return config.model.contains("qwen", ignoreCase = true)
    }

    fun shouldEnableQwenNativeSearch(config: ApiConfig?, isWebSearchEnabled: Boolean): Boolean {
        return isWebSearchEnabled && isQwenNativeSearch(config)
    }

    fun canUseJinaSearch(): Boolean = JinaSearchService.isAvailable

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
        if (canUseJinaSearch()) {
            return WebSearchRouting(
                useNativeWebSearch = false,
                useJinaSearch = true,
            )
        }
        return WebSearchRouting(useNativeWebSearch = false)
    }
}
