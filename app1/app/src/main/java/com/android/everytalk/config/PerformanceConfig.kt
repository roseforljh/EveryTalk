package com.android.everytalk.config

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
    
    // ===== 流式渲染跳动修复配置 =====
    /**
     * 启用等高占位策略：流式期间为含代码块/表格的消息添加与完成态一致的占位高度
     * 目的：消除流式结束时从单一MarkdownRenderer切换到分段渲染（CodeBlock/TableRenderer）的高度突变
     */
    const val ENABLE_STREAMING_HEIGHT_PLACEHOLDER = true
    
    /**
     * 启用单次切换策略：等待解析完成后一次性替换，避免中间态回退导致的二次跳变
     * 目的：从"流式Markdown → 回退Markdown → 分段渲染"优化为"流式Markdown → 分段渲染"
     */
    const val ENABLE_SINGLE_SWAP_RENDERING = true
    
    /**
     * 代码块顶部工具条高度（dp）- 用于等高占位
     * 必须与CodeBlock实际工具条高度保持一致
     */
    const val CODE_BLOCK_TOOLBAR_HEIGHT_DP = 28f
    
    /**
     * 代码块额外垂直内边距（dp）- 用于等高占位
     * 匹配CodeBlock的padding策略
     */
    const val CODE_BLOCK_EXTRA_VERTICAL_PADDING_DP = 4f
    
    /**
     * 表格额外垂直外边距（dp）- 用于等高占位
     * 匹配TableRenderer的padding策略
     */
    const val TABLE_EXTRA_VERTICAL_MARGIN_DP = 8f
    
    /**
     * 启用渲染切换日志：记录isStreaming切换、解析完成、高度变化
     */
    const val ENABLE_RENDER_TRANSITION_LOGGING = true
    
    // ===== Markwon缓存配置 =====
    /**
     * 启用Markwon全局缓存：避免LazyColumn回收导致的重复初始化
     * 修复前：流式结束后4次初始化，累计200-400ms
     * 修复后：全局只初始化1-2次，后续<1ms命中缓存
     */
    const val ENABLE_MARKWON_GLOBAL_CACHE = true
    
    /**
     * Markwon缓存最大实例数：按主题+字号缓存
     * 推荐值：4（深色/浅色 × 2种常用字号）
     */
    const val MARKWON_CACHE_MAX_SIZE = 4

    // ===== 语音模式配置 =====
    /**
     * 语音录音采样率（Hz）
     * 当前端录音和服务端 STT 需要保持一致时，可以通过这个参数统一调整
     */
    const val VOICE_RECORD_SAMPLE_RATE_HZ = 16_000

    /**
     * 录音阶段音量回调的时间间隔（毫秒）
     * 数值越小波形越“丝滑”，但 CPU/主线程开销越大；推荐 50–80ms
     */
    const val VOICE_RECORD_VOLUME_UPDATE_INTERVAL_MS = 60L

    /**
     * 录音阶段音量计算的采样步长
     * 例如 4 表示每 4 个采样点取 1 个做 RMS，降低计算量
     */
    const val VOICE_RECORD_VOLUME_SAMPLE_STEP = 4

    /**
     * 短语音阈值（毫秒）
     * 录音时长低于该值时，前端直接走 WAV 上传，跳过 AAC 编码以降低首包延迟
     */
    const val VOICE_SHORT_UTTERANCE_MAX_DURATION_MS = 3_000L

    /**
     * 流式播放预缓冲时长（毫秒）
     * 从首次收到音频到开始播放之间的最小缓冲时间，用于平衡首音延迟和 Underrun 风险
     */
    const val VOICE_STREAM_PREBUFFER_MS = 50L

    /**
     * 流式播放在 close() 阶段的最大等待时间（毫秒）
     * 用于防止尾部等待过长导致协程阻塞
     */
    const val VOICE_STREAM_CLOSE_TIMEOUT_MS = 4_000L
    
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