package com.example.everytalk.config

/**
 * 性能优化配置 - 集中管理所有性能相关参数
 */
object PerformanceConfig {
    
    // Compose 重组阈值
    const val RECOMPOSITION_WARNING_THRESHOLD = 10
    const val RECOMPOSITION_LOG_INTERVAL_MS = 1000L
    
    // 状态管理配置
    const val STATE_DEBOUNCE_DELAY_MS = 300L
    const val BATCH_UPDATE_DELAY_MS = 16L // 一帧时间
    
    // 缓存配置
    const val TEXT_CACHE_SIZE = 50
    const val IMAGE_CACHE_SIZE = 20
    const val OPTIMIZED_LIST_CACHE_SIZE = 100
    
    // 滚动配置
    const val SCROLL_THRESHOLD_PX = 5f
    const val SCROLL_BUTTON_HIDE_DELAY_MS = 3000L
    const val AUTO_SCROLL_ANIMATION_DURATION_MS = 300
    
    // 内存管理
    const val LONG_TEXT_THRESHOLD = 1000
    const val TEXT_CHUNK_SIZE = 500
    
    // 消息处理配置
    const val FORCE_MESSAGE_PROCESSING = true // 强制处理所有AI消息，确保parts不丢失
    const val MESSAGE_VALIDATION_ENABLED = true // 启用消息验证
    const val AUTO_REPAIR_INVALID_MESSAGES = true // 自动修复无效消息
    const val STREAMING_PARTS_UPDATE_ENABLED = true //  启用流式过程中的parts实时更新
    const val FALLBACK_TO_TEXT_ON_PARTS_FAILURE = true //  parts解析失败时回退到原文本
    const val ENABLE_AGGRESSIVE_PARTS_PROCESSING = true // 启用积极的parts处理，确保UI正确显示

    // 开关：是否在流式期间用 StateFlow 渲染（完成后仍同步到 message.text）
    const val USE_STREAMING_STATEFLOW_RENDERING = true
    
    // UI渲染配置
    const val UI_REFRESH_DEBOUNCE_MS = 50L // UI刷新防抖
    const val COMPOSE_RECOMPOSITION_THRESHOLD = 5 // Compose重组阈值
    
    // 动画配置
    const val REDUCED_ANIMATION_DURATION_MS = 150L // 减少原来的动画时间
    const val POPUP_ANIMATION_DURATION_MS = 200L
    
    // 日志配置
    const val ENABLE_PERFORMANCE_LOGGING = true
    const val LOG_TAG_PERFORMANCE = "AppPerformance"
    
    // 主线程监控
    const val MAIN_THREAD_BLOCK_THRESHOLD_MS = 100L
    
    // ===== 格式渲染配置 =====
    // LaTeX公式渲染
    const val LATEX_BITMAP_CACHE_SIZE = 100 // 缓存100个公式Bitmap
    const val LATEX_RENDER_TIMEOUT_MS = 5000L // 单个公式渲染超时

    // 数学渲染（KaTeX + WebView）
    // 说明：不走任何网络；仅 file:///android_asset/katex/index.html
    // 失败/超时/过长一律回退原文。
    const val MATH_RENDER_MODE = "webview" // 当前仅支持 webview
    const val MATH_RENDER_TIMEOUT_MS = 1200L // 单个公式渲染超时（毫秒）
    const val MATH_MAX_FORMULA_LEN = 4096 // 超长直接回退原文
    const val MATH_STREAMING_RENDER_SAFEPOINTS = true // 流式仅在数学安全闭合点提交

    // Markdown解析
    const val MARKDOWN_PARSE_CACHE_SIZE = 50 // 缓存50个已解析Markdown
    
    // 流式解析
    const val PARSE_BUFFER_SIZE = 200 // 流式解析缓冲区（字符数）
    const val PARSE_DEBOUNCE_MS = 300L // 解析防抖时间
    
    // 代码块配置
    const val CODE_BLOCK_SCROLL_THRESHOLD = 80 // 超过80字符启用水平滚动
    
    /**
     * 根据设备性能动态调整配置
     */
    fun adjustForDevicePerformance(context: android.content.Context) {
        // 可以根据设备内存、CPU等信息动态调整配置
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        // 低内存设备的优化配置
        if (memoryInfo.lowMemory) {
            android.util.Log.i(LOG_TAG_PERFORMANCE, "检测到低内存设备，启用性能优化配置")
            // 可以在这里调整各种缓存大小等参数
        }
    }
}