package com.example.everytalk.util

import android.util.Log
import com.example.everytalk.config.MathRenderConfig
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 🚀 数学渲染性能监控器
 * 
 * 功能：
 * - 渲染性能统计
 * - 错误率监控
 * - 缓存命中率分析
 * - 自动优化建议
 */
object MathRenderMonitor {
    
    private const val TAG = "MathRenderMonitor"
    
    // 性能统计
    private val renderCount = AtomicInteger(0)
    private val successCount = AtomicInteger(0)
    private val failureCount = AtomicInteger(0)
    private val totalRenderTime = AtomicLong(0)
    private val cacheHits = AtomicInteger(0)
    private val cacheMisses = AtomicInteger(0)
    
    // 渲染时间记录
    private val renderTimes = ConcurrentHashMap<String, Long>()
    
    // 错误统计
    private val errorTypes = ConcurrentHashMap<String, AtomicInteger>()
    
    // 内容复杂度统计
    private val complexityStats = ConcurrentHashMap<String, AtomicInteger>()
    
    /**
     * 开始渲染计时
     */
    fun startRender(renderId: String): Long {
        val startTime = System.currentTimeMillis()
        renderTimes[renderId] = startTime
        renderCount.incrementAndGet()
        
        if (MathRenderConfig.Debug.ENABLE_PERFORMANCE_MONITORING) {
            Log.d(TAG, "Started render: $renderId")
        }
        
        return startTime
    }
    
    /**
     * 结束渲染计时
     */
    fun endRender(renderId: String, success: Boolean, complexity: RenderComplexity = RenderComplexity.MEDIUM) {
        val endTime = System.currentTimeMillis()
        val startTime = renderTimes.remove(renderId)
        
        if (startTime != null) {
            val duration = endTime - startTime
            totalRenderTime.addAndGet(duration)
            
            if (success) {
                successCount.incrementAndGet()
            } else {
                failureCount.incrementAndGet()
            }
            
            // 记录复杂度统计
            complexityStats.getOrPut(complexity.name) { AtomicInteger(0) }.incrementAndGet()
            
            if (MathRenderConfig.Debug.ENABLE_PERFORMANCE_MONITORING) {
                Log.d(TAG, "Render completed: $renderId, duration: ${duration}ms, success: $success, complexity: $complexity")
                
                // 性能警告
                if (duration > MathRenderConfig.MATH_RENDER_TIMEOUT_MS / 2) {
                    Log.w(TAG, "Slow render detected: $renderId took ${duration}ms")
                }
            }
        }
    }
    
    /**
     * 记录缓存命中
     */
    fun recordCacheHit() {
        cacheHits.incrementAndGet()
        if (MathRenderConfig.Debug.ENABLE_PERFORMANCE_MONITORING) {
            Log.d(TAG, "Cache hit recorded")
        }
    }
    
    /**
     * 记录缓存未命中
     */
    fun recordCacheMiss() {
        cacheMisses.incrementAndGet()
        if (MathRenderConfig.Debug.ENABLE_PERFORMANCE_MONITORING) {
            Log.d(TAG, "Cache miss recorded")
        }
    }
    
    /**
     * 记录错误
     */
    fun recordError(errorType: String, details: String? = null) {
        errorTypes.getOrPut(errorType) { AtomicInteger(0) }.incrementAndGet()
        
        if (MathRenderConfig.Debug.ENABLE_RENDER_LOGGING) {
            Log.e(TAG, "Render error: $errorType${details?.let { " - $it" } ?: ""}")
        }
    }
    
    /**
     * 获取性能统计
     */
    fun getPerformanceStats(): PerformanceStats {
        val total = renderCount.get()
        val success = successCount.get()
        val failure = failureCount.get()
        val totalTime = totalRenderTime.get()
        val hits = cacheHits.get()
        val misses = cacheMisses.get()
        
        return PerformanceStats(
            totalRenders = total,
            successfulRenders = success,
            failedRenders = failure,
            successRate = if (total > 0) success.toFloat() / total else 0f,
            averageRenderTime = if (success > 0) totalTime.toFloat() / success else 0f,
            cacheHitRate = if (hits + misses > 0) hits.toFloat() / (hits + misses) else 0f,
            errorBreakdown = errorTypes.mapValues { it.value.get() },
            complexityBreakdown = complexityStats.mapValues { it.value.get() }
        )
    }
    
    /**
     * 生成性能报告
     */
    fun generatePerformanceReport(): String {
        val stats = getPerformanceStats()
        
        return """
            📊 数学渲染性能报告
            ========================
            总渲染次数: ${stats.totalRenders}
            成功渲染: ${stats.successfulRenders}
            失败渲染: ${stats.failedRenders}
            成功率: ${"%.1f".format(stats.successRate * 100)}%
            平均渲染时间: ${"%.1f".format(stats.averageRenderTime)}ms
            缓存命中率: ${"%.1f".format(stats.cacheHitRate * 100)}%
            
            复杂度分布:
            ${stats.complexityBreakdown.entries.joinToString("\n") { "  ${it.key}: ${it.value}" }}
            
            错误分布:
            ${if (stats.errorBreakdown.isEmpty()) "  无错误记录" else stats.errorBreakdown.entries.joinToString("\n") { "  ${it.key}: ${it.value}" }}
            
            优化建议:
            ${generateOptimizationSuggestions(stats)}
        """.trimIndent()
    }
    
    /**
     * 生成优化建议
     */
    private fun generateOptimizationSuggestions(stats: PerformanceStats): String {
        val suggestions = mutableListOf<String>()
        
        if (stats.successRate < 0.95f) {
            suggestions.add("• 成功率较低，建议检查错误处理逻辑")
        }
        
        if (stats.averageRenderTime > 1000f) {
            suggestions.add("• 平均渲染时间较长，建议优化渲染算法或增加缓存")
        }
        
        if (stats.cacheHitRate < 0.7f) {
            suggestions.add("• 缓存命中率较低，建议优化缓存策略或增加缓存容量")
        }
        
        val complexTotal = stats.complexityBreakdown.values.sum()
        val highComplexityRatio = stats.complexityBreakdown[RenderComplexity.HIGH.name]?.toFloat() ?: 0f
        if (complexTotal > 0 && highComplexityRatio / complexTotal > 0.3f) {
            suggestions.add("• 高复杂度内容较多，建议优化内容分析算法")
        }
        
        return if (suggestions.isEmpty()) {
            "  性能表现良好，暂无优化建议"
        } else {
            suggestions.joinToString("\n")
        }
    }
    
    /**
     * 重置统计数据
     */
    fun resetStats() {
        renderCount.set(0)
        successCount.set(0)
        failureCount.set(0)
        totalRenderTime.set(0)
        cacheHits.set(0)
        cacheMisses.set(0)
        renderTimes.clear()
        errorTypes.clear()
        complexityStats.clear()
        
        Log.i(TAG, "Performance stats reset")
    }
    
    /**
     * 启动性能监控定时任务
     */
    fun startPerformanceMonitoring(scope: CoroutineScope) {
        if (!MathRenderConfig.Debug.ENABLE_PERFORMANCE_MONITORING) return
        
        scope.launch {
            while (isActive) {
                delay(60_000) // 每分钟记录一次
                
                val stats = getPerformanceStats()
                if (stats.totalRenders > 0) {
                    Log.i(TAG, "Performance summary: ${stats.totalRenders} renders, " +
                            "${"%.1f".format(stats.successRate * 100)}% success rate, " +
                            "${"%.1f".format(stats.averageRenderTime)}ms avg time")
                }
            }
        }
    }
}

/**
 * 渲染复杂度枚举
 */
enum class RenderComplexity {
    LOW,     // 简单数学公式
    MEDIUM,  // 中等复杂度
    HIGH,    // 复杂数学表达式
    EXTREME  // 极复杂内容
}

/**
 * 性能统计数据类
 */
data class PerformanceStats(
    val totalRenders: Int,
    val successfulRenders: Int,
    val failedRenders: Int,
    val successRate: Float,
    val averageRenderTime: Float,
    val cacheHitRate: Float,
    val errorBreakdown: Map<String, Int>,
    val complexityBreakdown: Map<String, Int>
)