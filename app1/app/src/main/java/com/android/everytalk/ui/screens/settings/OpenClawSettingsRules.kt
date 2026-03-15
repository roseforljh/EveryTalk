package com.android.everytalk.ui.screens.settings

object OpenClawSettingsRules {
    private const val DEFAULT_ADDRESS_LABEL = "API接口地址"
    private const val DEFAULT_KEY_LABEL = "API密钥"
    private const val OPENCLAW_GATEWAY_ADDRESS_LABEL = "Gateway Address"
    private const val OPENCLAW_GATEWAY_KEY_LABEL = "Gateway Token"

    private val nonDeletableProviders = setOf(
        "openai compatible",
        "google",
        "openclaw",
        "openclaw remote",
        "阿里云百炼",
        "火山引擎",
        "深度求索",
        "openrouter",
        "硅基流动",
        "siliconflow",
        "seedream",
        "gemini"
    )

    fun addressLabelFor(provider: String, channel: String?): String =
        if (isOpenClaw(provider, channel)) OPENCLAW_GATEWAY_ADDRESS_LABEL else DEFAULT_ADDRESS_LABEL

    fun keyLabelFor(provider: String, channel: String?): String =
        if (isOpenClaw(provider, channel)) OPENCLAW_GATEWAY_KEY_LABEL else DEFAULT_KEY_LABEL

    fun shouldSaveWithoutModel(provider: String, channel: String?): Boolean =
        isOpenClaw(provider, channel)

    fun canDeleteProvider(provider: String): Boolean =
        provider.lowercase().trim() !in nonDeletableProviders

    fun isPinnedSettingsGroup(provider: String): Boolean {
        val normalizedProvider = provider.trim().lowercase()
        return normalizedProvider in setOf("默认", "default")
    }

    fun isSettingsGroupEditable(provider: String): Boolean =
        !isPinnedSettingsGroup(provider)

    fun canExpandSettingsModels(provider: String): Boolean =
        !isPinnedSettingsGroup(provider) && provider.trim().lowercase() !in setOf("openclaw", "openclaw remote")

    fun normalizeBaseUrlForPreview(url: String): String =
        url.trim().trimEnd('#')

    fun shouldBypassPath(url: String): Boolean =
        url.trim().endsWith("#")

    fun endsWithSlash(url: String): Boolean {
        val u = url.trim().trimEnd('#')
        return u.endsWith("/")
    }

    fun hasPathAfterHost(url: String): Boolean {
        val u = url.trim().trimEnd('#').trimEnd('/')
        val schemeIdx = u.indexOf("://")
        return if (schemeIdx >= 0) {
            u.indexOf('/', schemeIdx + 3) >= 0
        } else {
            u.indexOf('/') >= 0
        }
    }

    fun endpointPathFor(provider: String, channel: String?, withV1: Boolean): String {
        val p = provider.lowercase().trim()
        val ch = channel?.lowercase()?.trim().orEmpty()
        return if (p.contains("google") || ch.contains("gemini")) {
            if (withV1) "v1beta/models:generateContent" else "models:generateContent"
        } else {
            if (withV1) "v1/chat/completions" else "chat/completions"
        }
    }

    fun isOpenClaw(provider: String, channel: String?): Boolean {
        val p = provider.lowercase().trim()
        val ch = channel?.lowercase()?.trim().orEmpty()
        return p.contains("openclaw") || ch.contains("openclaw")
    }

    fun isValidRemoteGatewayAddress(address: String): Boolean {
        val value = address.trim()
        return value.startsWith("ws://", ignoreCase = true) || value.startsWith("wss://", ignoreCase = true)
    }

    fun validateRemoteConfigOrNull(provider: String, address: String): String? {
        return if (provider.trim().equals("OpenClaw Remote", ignoreCase = true) && !isValidRemoteGatewayAddress(address)) {
            "OpenClaw Remote 仅支持 ws:// 或 wss:// Gateway 地址"
        } else null
    }

    fun addSuccessMessageFor(provider: String): String {
        return if (provider.trim().equals("OpenClaw Remote", ignoreCase = true)) {
            "已添加远程龙虾连接"
        } else {
            "已添加模型配置"
        }
    }

    fun displayTitleForSettingsGroup(provider: String): String {
        return if (provider.trim().equals("OpenClaw Remote", ignoreCase = true)) {
            "远程龙虾连接"
        } else {
            provider.ifBlank { "综合平台" }
        }
    }

    fun displaySubtitleForSettingsGroup(provider: String): String? {
        return if (provider.trim().equals("OpenClaw Remote", ignoreCase = true)) {
            "通过 Gateway 远程控制部署在 VPS/电脑上的龙虾"
        } else {
            null
        }
    }

    fun connectionSummaryLabel(provider: String, address: String): String {
        return if (provider.trim().equals("OpenClaw Remote", ignoreCase = true)) {
            "Gateway: ${address.trim()}"
        } else {
            "地址: ${address.trim()}"
        }
    }

    fun secretSummaryLabel(provider: String, secret: String): String {
        return if (provider.trim().equals("OpenClaw Remote", ignoreCase = true)) {
            if (secret.isBlank()) "Token: 未配置" else "Token: 已配置"
        } else {
            "Key: ${maskApiKeyForPreview(secret)}"
        }
    }

    fun remoteTargetLabel(sessionKey: String): String? {
        val normalized = sessionKey.removePrefix("et:")
        return if (normalized.startsWith("remote:")) {
            "当前控制目标: ${normalized.removePrefix("remote:")}"
        } else null
    }

    private fun maskApiKeyForPreview(secret: String): String {
        if (secret.isBlank()) return "未配置"
        if (secret.length <= 8) return "****"
        return secret.take(4) + "****" + secret.takeLast(4)
    }

    fun buildFullEndpointPreview(
        base: String,
        provider: String,
        channel: String?,
        accessMode: String? = null
    ): String {
        val raw = base.trim()
        if (raw.isEmpty()) return ""
        val noHash = raw.trimEnd('#')

        val p = provider.lowercase().trim()
        val ch = channel?.lowercase()?.trim().orEmpty()
        val isGemini = p.contains("google") || ch.contains("gemini")

        if (isOpenClaw(provider, channel)) {
            return noHash
        }

        if (shouldBypassPath(raw)) {
            return noHash
        }

        if (isGemini) {
            if (hasPathAfterHost(noHash) || endsWithSlash(noHash)) {
                return noHash.trimEnd('/')
            }
            val path = endpointPathFor(provider, channel, true)
            return "$noHash/$path"
        }

        if (accessMode?.equals("bridge", ignoreCase = true) == true) {
            return noHash
        }

        if (endsWithSlash(noHash)) {
            val path = endpointPathFor(provider, channel, false)
            return noHash + path
        }

        if (hasPathAfterHost(noHash)) {
            return noHash
        }

        val path = endpointPathFor(provider, channel, true)
        return "$noHash/$path"
    }

    fun buildEndpointHintForPreview(
        base: String,
        provider: String,
        channel: String?,
        accessMode: String? = null
    ): String {
        val raw = base.trim()
        if (provider.trim().equals("OpenClaw Remote", ignoreCase = true)) {
            return "OpenClaw 远程控制：填写 ws:// 或 wss:// Gateway 地址，像聊天一样远程控制你的龙虾"
        }
        if (isOpenClaw(provider, channel)) {
            return "OpenClaw Gateway：按输入直连，不追加任何路径"
        }
        if (shouldBypassPath(raw)) {
            return "末尾#：直连，不追加任何路径（自动去掉#）"
        }

        val noHash = raw.trimEnd('#')
        val p = provider.lowercase().trim()
        val ch = channel?.lowercase()?.trim().orEmpty()
        val isGemini = p.contains("google") || ch.contains("gemini")

        if (isGemini) {
            if (hasPathAfterHost(noHash) || endsWithSlash(noHash)) {
                return "Gemini官方API：按输入直连（去掉末尾/）"
            }
            return "仅域名→ 自动拼接Gemini固定路径 /v1beta/models:generateContent"
        }

        if (accessMode?.equals("bridge", ignoreCase = true) == true) {
            return "Bridge 兼容模式：按输入直连，不追加任何路径"
        }

        if (endsWithSlash(noHash)) {
            return "末尾/：不要v1，添加/chat/completions"
        }

        if (hasPathAfterHost(noHash)) {
            return "地址已含路径→ 按输入直连，不追加路径"
        }

        return "仅域名→ 自动拼接默认路径 /v1/chat/completions"
    }
}

