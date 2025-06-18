package com.example.everytalk.ui.screens.settings


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
    "openrouter" to "https://openrouter.ai/api"
)