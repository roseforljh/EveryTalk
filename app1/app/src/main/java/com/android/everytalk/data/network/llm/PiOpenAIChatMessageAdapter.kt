package com.android.everytalk.data.network

/** Pi OpenAI Chat Completions 的工具 ID 兼容规则。 */
internal object PiOpenAIChatMessageAdapter {
    const val UPSTREAM_COMMIT = PiMessageTransformer.UPSTREAM_COMMIT
    private val INVALID_TOOL_CALL_ID_CHARACTER = Regex("[^a-zA-Z0-9_-]")

    fun normalizeToolCallId(id: String, provider: String): String {
        if ('|' in id) {
            val callId = id.substringBefore('|').replace(INVALID_TOOL_CALL_ID_CHARACTER, "_")
            val itemId = id.substringAfter('|').replace(INVALID_TOOL_CALL_ID_CHARACTER, "_")
            val combined = if (itemId.isNotEmpty()) "${callId}_$itemId" else callId
            if (combined.length <= 40) return combined
            val hash = shortHash(id).take(8)
            val prefix = callId.take((40 - hash.length - 1).coerceAtLeast(1))
            return "${prefix}_$hash"
        }
        return if (provider.equals("openai", ignoreCase = true) && id.length > 40) id.take(40) else id
    }

    /** Pi `detectCompat()` 的 maxTokensField 判定。 */
    fun maxTokensField(provider: String, baseUrl: String): String {
        val providerId = provider.lowercase()
        val endpoint = baseUrl.lowercase()
        val isZai = providerId in setOf("zai", "zai-coding-cn") ||
            "api.z.ai" in endpoint || "open.bigmodel.cn" in endpoint
        val isTogether = providerId == "together" ||
            "api.together.ai" in endpoint || "api.together.xyz" in endpoint
        val isMoonshot = providerId in setOf("moonshotai", "moonshotai-cn") ||
            "api.moonshot." in endpoint
        val isCloudflareAiGateway = providerId == "cloudflare-ai-gateway" ||
            "gateway.ai.cloudflare.com" in endpoint
        val isNvidia = providerId == "nvidia" || "integrate.api.nvidia.com" in endpoint
        val isAntLing = providerId == "ant-ling" || "api.ant-ling.com" in endpoint
        val isDeepSeek = providerId == "deepseek" || "deepseek.com" in endpoint
        val useLegacyField = "chutes.ai" in endpoint || isDeepSeek || isMoonshot ||
            isCloudflareAiGateway || isTogether || isNvidia || isAntLing || isZai
        return if (useLegacyField) "max_tokens" else "max_completion_tokens"
    }

    /** 与 Pi utils/hash.ts 的两个 32 位状态算法等价。 */
    internal fun shortHash(value: String): String {
        var h1 = 0xdeadbeefu.toInt()
        var h2 = 0x41c6ce57
        value.forEach { character ->
            val code = character.code
            h1 = (h1 xor code) * 0x9e3779b1u.toInt()
            h2 = (h2 xor code) * 0x5f356495
        }
        h1 = ((h1 xor (h1 ushr 16)) * 0x85ebca6bu.toInt()) xor
            ((h2 xor (h2 ushr 13)) * 0xc2b2ae35u.toInt())
        h2 = ((h2 xor (h2 ushr 16)) * 0x85ebca6bu.toInt()) xor
            ((h1 xor (h1 ushr 13)) * 0xc2b2ae35u.toInt())
        return Integer.toUnsignedString(h2, 36) + Integer.toUnsignedString(h1, 36)
    }
}
