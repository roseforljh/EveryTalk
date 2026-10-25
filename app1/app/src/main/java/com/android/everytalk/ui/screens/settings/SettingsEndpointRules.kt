package com.android.everytalk.ui.screens.settings

import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.modelParameterProtocol
import com.android.everytalk.data.network.LlmEndpointResolver

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

    @Suppress("UNUSED_PARAMETER")
    fun buildFullEndpointPreview(
        base: String,
        provider: String,
        channel: String?,
        isImageMode: Boolean = false,
        model: String = "",
    ): String {
        val raw = base.trim()
        if (raw.isEmpty()) return ""

        val channelName = channel?.trim()?.lowercase().orEmpty()
        val protocol = modelParameterProtocol(channelName)

        if (isImageMode) {
            return LlmEndpointResolver.resolveImage(raw, model, protocol)
        }
        return LlmEndpointResolver.resolve(
            protocol = protocol,
            apiAddress = raw,
            model = model,
        )
    }

    fun maskApiKey(secret: String, notConfiguredLabel: String): String = when {
        secret.isBlank() -> notConfiguredLabel
        secret.length <= 8 -> "****"
        else -> secret.take(4) + "****" + secret.takeLast(4)
    }

}
