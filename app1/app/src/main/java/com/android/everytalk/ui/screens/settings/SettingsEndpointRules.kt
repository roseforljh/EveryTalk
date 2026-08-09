package com.android.everytalk.ui.screens.settings

object SettingsEndpointRules {
    private val nonDeletableProviders = setOf(
        "anthropic",
        "openai compatible",
        "google",
        "阿里云百炼",
        "火山引擎",
        "深度求索",
        "openrouter",
        "硅基流动",
        "siliconflow",
        "seedream",
        "gemini",
    )

    fun canDeleteProvider(provider: String): Boolean =
        provider.trim().lowercase() !in nonDeletableProviders

    fun isPinnedSettingsGroup(provider: String): Boolean =
        provider.trim().lowercase() in setOf("默认", "default")

    fun canExpandSettingsModels(provider: String): Boolean = !isPinnedSettingsGroup(provider)

    fun buildFullEndpointPreview(
        base: String,
        provider: String,
        channel: String?,
        isImageMode: Boolean = false,
    ): String {
        val raw = base.trim()
        if (raw.isEmpty()) return ""
        val noHash = raw.removeSuffix("#").trimEnd('/')
        if (raw.endsWith('#')) return noHash

        val providerName = provider.trim().lowercase()
        val channelName = channel?.trim()?.lowercase().orEmpty()
        val isGemini = providerName.contains("google") || channelName.contains("gemini")
        val isAnthropic = providerName.contains("anthropic") || channelName.contains("anthropic")

        if (isImageMode && !isGemini) {
            return noHash.removeKnownCompletionPath() + "/v1/images/generations"
        }
        if (isAnthropic) {
            return when {
                noHash.endsWith("/messages", ignoreCase = true) -> noHash
                noHash.endsWith("/v1", ignoreCase = true) -> "$noHash/messages"
                else -> "$noHash/v1/messages"
            }
        }
        if (isGemini) {
            if (hasPathAfterHost(noHash)) return noHash
            return "$noHash/v1beta/models:generateContent"
        }
        if (channelName.contains("codex")) {
            if (hasPathAfterHost(noHash)) return noHash
            return "$noHash/v1/responses"
        }
        if (hasPathAfterHost(noHash)) return noHash
        return "$noHash/v1/chat/completions"
    }

    fun maskApiKey(secret: String, notConfiguredLabel: String): String = when {
        secret.isBlank() -> notConfiguredLabel
        secret.length <= 8 -> "****"
        else -> secret.take(4) + "****" + secret.takeLast(4)
    }

    private fun String.removeKnownCompletionPath(): String {
        val paths = listOf(
            "/v1/images/generations",
            "/images/generations",
            "/v1/chat/completions",
            "/chat/completions",
            "/v1/completions",
            "/completions",
        )
        val path = paths.firstOrNull { endsWith(it, ignoreCase = true) } ?: return this
        return dropLast(path.length).trimEnd('/')
    }

    private fun hasPathAfterHost(url: String): Boolean {
        val schemeIndex = url.indexOf("://")
        return if (schemeIndex >= 0) {
            url.indexOf('/', schemeIndex + 3) >= 0
        } else {
            url.indexOf('/') >= 0
        }
    }
}
