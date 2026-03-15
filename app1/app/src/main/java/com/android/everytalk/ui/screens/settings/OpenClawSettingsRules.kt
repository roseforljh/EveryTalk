package com.android.everytalk.ui.screens.settings

object OpenClawSettingsRules {
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
        val isBridgeMode = accessMode?.equals("bridge", ignoreCase = true) == true

        if (isOpenClaw(provider, channel)) {
            return noHash
        }

        if (isBridgeMode) {
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
        val isBridgeMode = accessMode?.equals("bridge", ignoreCase = true) == true
        if (isOpenClaw(provider, channel) && isBridgeMode) {
            return "OpenClaw Bridge：按输入连接中转服务，不追加任何路径"
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

        if (endsWithSlash(noHash)) {
            return "末尾/：不要v1，添加/chat/completions"
        }

        if (hasPathAfterHost(noHash)) {
            return "地址已含路径→ 按输入直连，不追加路径"
        }

        return "仅域名→ 自动拼接默认路径 /v1/chat/completions"
    }
}

