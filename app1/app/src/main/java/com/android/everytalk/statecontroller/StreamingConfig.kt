package com.android.everytalk.statecontroller

/**
 * 流式输出配置
 * 用于控制AI输出的流式显示效果
 */
object StreamingConfig {
    /**
     * UI更新间隔(毫秒)
     * 值越小,更新越频繁,效果越流畅,但性能消耗越大
     * 推荐值: 50-150ms
     */
    const val UI_UPDATE_INTERVAL_MS = 100L
    
    /**
     * 最小字符累积数
     * 累积至少这么多字符后才触发UI更新
     * 推荐值: 5-20
     */
    const val MIN_CHARS_TO_UPDATE = 10
    
    /**
     * 是否启用平滑流式输出
     * true: 逐步显示; false: 批量更新
     */
    const val ENABLE_SMOOTH_STREAMING = true
    
    /**
     * 批量更新模式下的采样率(毫秒)
     * 仅在 ENABLE_SMOOTH_STREAMING = false 时生效
     */
    const val BATCH_SAMPLE_RATE_MS = 100L
}