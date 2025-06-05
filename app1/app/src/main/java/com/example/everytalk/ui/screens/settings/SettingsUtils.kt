package com.example.everytalk.ui.screens.settings

/**
 * 包含设置屏幕相关的通用辅助函数。
 */

/**
 * 掩码 API 密钥以进行安全显示。
 *
 * @param key 要掩码的 API 密钥字符串。
 * @return 掩码后的 API 密钥字符串。
 *         如果密钥为空，则返回 "(未设置)"。
 *         如果密钥长度小于等于8，则所有字符替换为 '*'。
 *         否则，显示前4个和后4个字符，中间用 '****' 替换。
 */
internal fun maskApiKey(key: String): String {
    return when {
        key.isBlank() -> "(未设置)"
        key.length <= 8 -> key.map { '*' }.joinToString("")
        else -> "${key.take(4)}****${key.takeLast(4)}"
    }
}


internal val defaultApiAddresses: Map<String, String> = mapOf(
    "google" to "https://generativelanguage.googleapis.com",
    "硅基流动" to "https://api.siliconflow.cn",
    "阿里云百炼" to "https://dashscope.aliyuncs.com/compatible-mode",
    "火山引擎" to "https://ark.cn-beijing.volces.com/api/v3/bots/",
    "深度求索" to "https://api.deepseek.com",
    "openrouter" to "https://openrouter.ai/api" // 新增 OpenRouter
)