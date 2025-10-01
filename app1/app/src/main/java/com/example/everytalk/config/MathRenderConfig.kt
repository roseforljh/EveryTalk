package com.example.everytalk.config

/**
 * 🚀 专业数学公式渲染配置
 */
object MathRenderConfig {
    
    // 渲染模式配置
    const val ENABLE_PROFESSIONAL_MATH_RENDERER = true
    const val ENABLE_KATEX_CDN = true
    const val ENABLE_MATH_CACHE = true
    
    // 性能配置
    const val MATH_RENDER_TIMEOUT_MS = 5000L
    const val MAX_MATH_CACHE_SIZE = 100
    const val WEBVIEW_POOL_SIZE = 3
    
    // 内容检测阈值
    const val MATH_CONTENT_THRESHOLD = 0.15f  // 数学内容占比阈值
    const val COMPLEX_MATH_THRESHOLD = 2      // 复杂数学公式数量阈值
    
    // CDN配置
    const val KATEX_CDN_VERSION = "0.16.8"
    const val KATEX_CSS_URL = "https://cdn.jsdelivr.net/npm/katex@$KATEX_CDN_VERSION/dist/katex.min.css"
    const val KATEX_JS_URL = "https://cdn.jsdelivr.net/npm/katex@$KATEX_CDN_VERSION/dist/katex.min.js"
    const val KATEX_AUTO_RENDER_URL = "https://cdn.jsdelivr.net/npm/katex@$KATEX_CDN_VERSION/dist/contrib/auto-render.min.js"
    
    // 备用CDN配置
    val BACKUP_CDN_URLS = listOf(
        "https://cdnjs.cloudflare.com/ajax/libs/KaTeX/$KATEX_CDN_VERSION/",
        "https://unpkg.com/katex@$KATEX_CDN_VERSION/dist/",
        "https://cdn.bootcdn.net/ajax/libs/KaTeX/$KATEX_CDN_VERSION/"
    )
    
    // 渲染质量配置
    object Quality {
        const val ENABLE_HIGH_DPI = true
        const val ENABLE_ANTI_ALIASING = true
        const val TEXT_SCALE_FACTOR = 1.1f
        const val MIN_FONT_SIZE = 14
        const val MAX_FONT_SIZE = 24
    }
    
    // 主题配置
    object Theme {
        const val AUTO_DARK_MODE = true
        const val MATH_COLOR_ADAPTATION = true
        
        // 深色模式颜色
        const val DARK_TEXT_COLOR = "#FFFFFF"
        const val DARK_BACKGROUND_COLOR = "#1A1A1A"
        const val DARK_ACCENT_COLOR = "#64B5F6"
        
        // 浅色模式颜色
        const val LIGHT_TEXT_COLOR = "#000000"
        const val LIGHT_BACKGROUND_COLOR = "#FFFFFF"
        const val LIGHT_ACCENT_COLOR = "#1976D2"
    }
    
    // 调试配置
    object Debug {
        const val ENABLE_RENDER_LOGGING = true
        const val ENABLE_PERFORMANCE_MONITORING = true
        const val ENABLE_ERROR_FALLBACK = true
        const val LOG_TAG = "MathRenderer"
    }
    
    // 错误处理配置
    object ErrorHandling {
        const val MAX_RETRY_COUNT = 3
        const val RETRY_DELAY_MS = 1000L
        const val ENABLE_GRACEFUL_DEGRADATION = true
        const val FALLBACK_TO_TEXT = true
    }
    
    /**
     * 获取当前配置摘要
     */
    fun getConfigSummary(): String {
        return """
            MathRenderConfig Summary:
            - Professional Renderer: $ENABLE_PROFESSIONAL_MATH_RENDERER
            - KaTeX CDN: $ENABLE_KATEX_CDN
            - Cache Enabled: $ENABLE_MATH_CACHE
            - Max Cache Size: $MAX_MATH_CACHE_SIZE
            - Render Timeout: ${MATH_RENDER_TIMEOUT_MS}ms
            - WebView Pool Size: $WEBVIEW_POOL_SIZE
            - Math Threshold: $MATH_CONTENT_THRESHOLD
            - Debug Logging: ${Debug.ENABLE_RENDER_LOGGING}
        """.trimIndent()
    }
}