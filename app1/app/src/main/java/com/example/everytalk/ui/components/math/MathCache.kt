package com.example.everytalk.ui.components.math

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

/**
 * 高性能数学公式缓存管理器
 * 使用LRU策略和内存监控，避免内存泄漏
 */
class MathCache private constructor() : DefaultLifecycleObserver {
    
    companion object {
        @Volatile
        private var INSTANCE: MathCache? = null
        private const val MAX_CACHE_SIZE = 200
        private const val MAX_MEMORY_SIZE = 50 * 1024 * 1024 // 50MB
        
        fun getInstance(): MathCache {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MathCache().also { INSTANCE = it }
            }
        }
    }
    
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val accessOrder = ConcurrentHashMap<String, Long>()
    private val accessCounter = AtomicInteger(0)
    private val currentMemoryUsage = AtomicInteger(0)
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    data class CacheEntry(
        val bitmap: Bitmap,
        val width: Float,
        val height: Float,
        val memorySize: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * 生成缓存键
     */
    fun generateCacheKey(
        latex: String,
        textSize: Float,
        color: Color,
        isDisplay: Boolean
    ): String {
        return "${latex.hashCode()}_${textSize}_${color.value}_$isDisplay"
    }
    
    /**
     * 获取缓存的数学公式位图
     */
    fun getCachedMath(cacheKey: String): CacheEntry? {
        val entry = cache[cacheKey]
        if (entry != null) {
            // 更新访问时间
            accessOrder[cacheKey] = accessCounter.incrementAndGet().toLong()
        }
        return entry
    }
    
    /**
     * 缓存数学公式位图
     */
    fun cacheMath(
        cacheKey: String,
        latex: String,
        textSize: Float,
        color: Color,
        isDisplay: Boolean
    ): CacheEntry? {
        return try {
            // 预估位图尺寸
            val estimatedWidth = (latex.length * textSize * 0.8f).toInt().coerceAtLeast(50)
            val estimatedHeight = (if (isDisplay) textSize * 1.5f else textSize * 1.2f).toInt().coerceAtLeast(30)
            
            // 创建合适大小的位图
            val bitmap = Bitmap.createBitmap(
                estimatedWidth,
                estimatedHeight,
                Bitmap.Config.ARGB_8888
            )
            
            val canvas = Canvas(bitmap)
            val mathRenderer = MathRenderer()
            
            // 渲染数学公式到位图
            val result = mathRenderer.renderMath(
                canvas = canvas,
                latex = latex,
                x = 0f,
                y = estimatedHeight * 0.7f,
                textSize = textSize,
                color = color,
                isDisplay = isDisplay
            )
            
            val memorySize = bitmap.byteCount
            val entry = CacheEntry(
                bitmap = bitmap,
                width = result.width,
                height = result.height,
                memorySize = memorySize
            )
            
            // 检查内存使用量
            if (currentMemoryUsage.get() + memorySize > MAX_MEMORY_SIZE) {
                cleanOldEntries()
            }
            
            // 添加到缓存
            cache[cacheKey] = entry
            accessOrder[cacheKey] = accessCounter.incrementAndGet().toLong()
            currentMemoryUsage.addAndGet(memorySize)
            
            // 异步清理过期缓存
            if (cache.size > MAX_CACHE_SIZE) {
                coroutineScope.launch {
                    cleanLRUEntries()
                }
            }
            
            entry
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 清理最近最少使用的缓存项
     */
    private suspend fun cleanLRUEntries() = withContext(Dispatchers.Default) {
        if (cache.size <= MAX_CACHE_SIZE) return@withContext
        
        val sortedEntries = accessOrder.toList().sortedBy { it.second }
        val toRemove = sortedEntries.take(cache.size - MAX_CACHE_SIZE + 20) // 多清理一些
        
        toRemove.forEach { (key, _) ->
            removeEntry(key)
        }
    }
    
    /**
     * 清理过期的缓存项
     */
    private fun cleanOldEntries() {
        val currentTime = System.currentTimeMillis()
        val expiredKeys = cache.entries
            .filter { currentTime - it.value.timestamp > 300_000 } // 5分钟过期
            .map { it.key }
        
        expiredKeys.forEach { key ->
            removeEntry(key)
        }
    }
    
    /**
     * 移除缓存项
     */
    private fun removeEntry(key: String) {
        val entry = cache.remove(key)
        accessOrder.remove(key)
        
        entry?.let {
            currentMemoryUsage.addAndGet(-it.memorySize)
            if (!it.bitmap.isRecycled) {
                it.bitmap.recycle()
            }
        }
    }
    
    /**
     * 清空所有缓存
     */
    fun clearCache() {
        cache.values.forEach { entry ->
            if (!entry.bitmap.isRecycled) {
                entry.bitmap.recycle()
            }
        }
        cache.clear()
        accessOrder.clear()
        currentMemoryUsage.set(0)
    }
    
    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            size = cache.size,
            memoryUsage = currentMemoryUsage.get(),
            maxMemorySize = MAX_MEMORY_SIZE,
            hitRate = calculateHitRate()
        )
    }
    
    private var totalRequests = AtomicInteger(0)
    private var cacheHits = AtomicInteger(0)
    
    private fun calculateHitRate(): Float {
        val total = totalRequests.get()
        return if (total > 0) cacheHits.get().toFloat() / total else 0f
    }
    
    fun recordRequest(isHit: Boolean) {
        totalRequests.incrementAndGet()
        if (isHit) {
            cacheHits.incrementAndGet()
        }
    }
    
    override fun onDestroy(owner: LifecycleOwner) {
        clearCache()
        coroutineScope.cancel()
        super.onDestroy(owner)
    }
    
    data class CacheStats(
        val size: Int,
        val memoryUsage: Int,
        val maxMemorySize: Int,
        val hitRate: Float
    )
}

/**
 * 预加载常用数学符号和公式
 */
class MathPreloader {
    companion object {
        private val commonMathExpressions = listOf(
            "\\pi", "\\alpha", "\\beta", "\\gamma", "\\delta", "\\theta", 
            "\\lambda", "\\mu", "\\sigma", "\\phi", "\\omega", "\\infty",
            "\\pm", "\\times", "\\div", "\\leq", "\\geq", "\\neq", "\\approx",
            "\\frac{1}{2}", "\\frac{a}{b}", "\\sqrt{x}", "\\sqrt{2}",
            "x^2", "x^n", "a_1", "x_i", "\\sum", "\\int"
        )
        
        fun preloadCommonExpressions(
            textSize: Float,
            color: Color,
            isDisplay: Boolean = false
        ) {
            val cache = MathCache.getInstance()
            
            commonMathExpressions.forEach { latex ->
                val cacheKey = cache.generateCacheKey(latex, textSize, color, isDisplay)
                if (cache.getCachedMath(cacheKey) == null) {
                    cache.cacheMath(cacheKey, latex, textSize, color, isDisplay)
                }
            }
        }
    }
}