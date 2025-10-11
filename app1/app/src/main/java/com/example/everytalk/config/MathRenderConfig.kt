package com.example.everytalk.config

/**
 * Math rendering fully disabled. Kept as a minimal stub to satisfy references.
 */
object MathRenderConfig {

    object Debug {
        const val ENABLE_PERFORMANCE_MONITORING = false
        const val ENABLE_RENDER_LOGGING = false
        const val LOG_TAG = "MathRenderer"
        const val ENABLE_ERROR_FALLBACK = false
    }

    /**
     * Minimal summary to avoid exposing any math/KaTeX related details.
     */
    fun summary(): String = "Math rendering disabled"
}