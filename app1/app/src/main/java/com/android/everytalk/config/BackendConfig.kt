package com.android.everytalk.config

import com.android.everytalk.BuildConfig

/**
 * 后端配置提供器（已废弃）
 *
 * ⚠️ 此类已废弃。所有功能已迁移到直连模式，不再需要后端代理。
 *
 * 保留此类仅为兼容旧代码引用。后续版本将完全移除。
 *
 * 直连模式说明：
 * - 文本对话：直接调用 OpenAI/Gemini 等 API
 * - 语音功能：使用 VoiceChatSession + AliyunRealtimeSttClient 直连
 * - 图像生成：使用 ImageGenerationDirectClient 直连
 */
@Deprecated(
    message = "后端代理模式已废弃，所有功能已迁移到直连模式",
    level = DeprecationLevel.WARNING
)
object BackendConfig {

    /**
     * 后端 URL 列表（已废弃，始终返回空列表）
     */
    @Deprecated("后端代理已废弃，此字段始终为空")
    val backendUrls: List<String> by lazy {
        android.util.Log.w("BackendConfig", "⚠️ backendUrls 已废弃，所有功能已使用直连模式")
        emptyList()
    }

    /**
     * 并发请求开关（已废弃，始终返回 false）
     */
    @Deprecated("后端代理已废弃，此字段始终为 false")
    val isConcurrentRequestEnabled: Boolean = false

    // 超时配置（保留供直连模式使用）
    const val TIMEOUT_MS: Long = 30000
    const val RACE_TIMEOUT_MS: Long = 10000
    const val FIRST_RESPONSE_TIMEOUT_MS: Long = 17_000
    const val IS_FALLBACK_ENABLED: Boolean = true
}