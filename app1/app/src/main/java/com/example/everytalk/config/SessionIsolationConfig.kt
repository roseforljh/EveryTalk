package com.example.everytalk.config

/**
 * 🎯 会话隔离配置 - 确保AI输出不会在会话间串流
 */
object SessionIsolationConfig {
    
    // 🎯 启用严格的会话隔离模式
    const val ENABLE_STRICT_SESSION_ISOLATION = true
    
    // 🎯 强制会话切换时的资源清理
    const val FORCE_RESOURCE_CLEANUP_ON_SESSION_SWITCH = true
    
    // 🎯 启用会话级别的消息处理器映射
    const val USE_SESSION_LEVEL_PROCESSOR_MAPPING = true
    
    // 🎯 启用流完整性检查
    const val ENABLE_STREAM_INTEGRITY_CHECK = true
    
    // 🎯 最大会话处理器缓存数量（防止内存泄漏）
    const val MAX_SESSION_PROCESSOR_CACHE_SIZE = 10
    
    // 🎯 会话切换的超时时间（毫秒）
    const val SESSION_SWITCH_TIMEOUT_MS = 5000L
    
    // 🎯 强制最终化处理的等待时间（毫秒）
    const val FORCE_FINALIZATION_DELAY_MS = 100L
    
    // 🎯 启用会话级别的事件通道隔离
    const val USE_SESSION_LEVEL_EVENT_CHANNELS = true
    
    // 🎯 AI输出完整性验证
    const val VALIDATE_AI_OUTPUT_COMPLETENESS = true
    
    // 🎯 启用会话生命周期日志
    const val ENABLE_SESSION_LIFECYCLE_LOGGING = true
    
    /**
     * 获取会话清理策略
     */
    enum class SessionCleanupStrategy {
        IMMEDIATE,    // 立即清理
        DELAYED,      // 延迟清理
        ON_DEMAND     // 按需清理
    }
    
    /**
     * 默认会话清理策略
     */
    const val DEFAULT_CLEANUP_STRATEGY = "IMMEDIATE"
    
    /**
     * 验证会话隔离配置
     */
    fun validateConfig(): Boolean {
        return ENABLE_STRICT_SESSION_ISOLATION && USE_SESSION_LEVEL_PROCESSOR_MAPPING
    }
    
    /**
     * 获取配置摘要
     */
    fun getConfigSummary(): String {
        return """
            会话隔离配置摘要:
            - 严格会话隔离: $ENABLE_STRICT_SESSION_ISOLATION
            - 强制资源清理: $FORCE_RESOURCE_CLEANUP_ON_SESSION_SWITCH
            - 会话级处理器映射: $USE_SESSION_LEVEL_PROCESSOR_MAPPING
            - 流完整性检查: $ENABLE_STREAM_INTEGRITY_CHECK
            - 最大缓存大小: $MAX_SESSION_PROCESSOR_CACHE_SIZE
            - 会话切换超时: ${SESSION_SWITCH_TIMEOUT_MS}ms
            - AI输出完整性验证: $VALIDATE_AI_OUTPUT_COMPLETENESS
        """.trimIndent()
    }
}