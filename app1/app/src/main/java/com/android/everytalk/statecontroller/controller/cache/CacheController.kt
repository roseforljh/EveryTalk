package com.android.everytalk.statecontroller.controller.cache

import android.util.Log
import androidx.collection.LruCache
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.util.cache.CacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 缓存控制器
 * 从 AppViewModel 中提取，负责管理会话预览缓存和缓存预热
 */
class CacheController(
    private val cacheManager: CacheManager,
    private val scope: CoroutineScope
) {
    private val TAG = "CacheController"
    
    // 基于可用内存动态计算缓存大小
    private val dynamicCacheSize: Int by lazy {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / 1024 / 1024 // MB
        when {
            maxMemory >= 512 -> 200  // 高内存设备
            maxMemory >= 256 -> 100  // 中等内存设备
            else -> 50               // 低内存设备
        }.also {
            Log.d(TAG, "Dynamic cache size: $it (maxMemory: ${maxMemory}MB)")
        }
    }
    
    // 会话预览缓存
    private val textConversationPreviewCache = LruCache<String, String>(dynamicCacheSize)
    private val imageConversationPreviewCache = LruCache<String, String>(dynamicCacheSize)
    
    /**
     * 获取文本会话预览缓存
     */
    fun getTextPreviewCache(): LruCache<String, String> = textConversationPreviewCache
    
    /**
     * 获取图像会话预览缓存
     */
    fun getImagePreviewCache(): LruCache<String, String> = imageConversationPreviewCache
    
    /**
     * 清空所有预览缓存
     */
    fun evictAllPreviews() {
        textConversationPreviewCache.evictAll()
        imageConversationPreviewCache.evictAll()
        Log.d(TAG, "Evicted all preview caches")
    }
    
    /**
     * 初始化缓存预热
     */
    fun initializeCacheWarmup(
        textConversations: List<List<Message>>,
        imageConversations: List<List<Message>>
    ) {
        scope.launch(Dispatchers.Default) {
            delay(1000) // 延迟预热，避免影响启动性能
            
            Log.d(TAG, "Starting cache warmup...")
            
            // 预热文本会话缓存
            cacheManager.warmupCache(textConversations.take(20))
            
            // 预热图像会话缓存
            cacheManager.warmupCache(imageConversations.take(10))
            
            Log.d(TAG, "Cache warmup completed")
        }
    }
    
    /**
     * 智能缓存清理
     */
    fun performSmartCleanup() {
        scope.launch(Dispatchers.Default) {
            cacheManager.smartCleanup()
        }
    }
    
    /**
     * 启动定期缓存维护任务
     */
    fun startCacheMaintenanceTask() {
        scope.launch {
            while (true) {
                delay(60_000) // 每分钟检查一次
                cacheManager.smartCleanup()
            }
        }
    }
    
    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): CacheStats {
        val managerStats = cacheManager.getCacheStats()
        return CacheStats(
            textPreviewCacheSize = textConversationPreviewCache.size(),
            textPreviewCacheHitRate = textConversationPreviewCache.hitCount().toFloat() / 
                (textConversationPreviewCache.hitCount() + textConversationPreviewCache.missCount()).coerceAtLeast(1),
            imagePreviewCacheSize = imageConversationPreviewCache.size(),
            imagePreviewCacheHitRate = imageConversationPreviewCache.hitCount().toFloat() /
                (imageConversationPreviewCache.hitCount() + imageConversationPreviewCache.missCount()).coerceAtLeast(1),
            overallHitRate = managerStats.overallHitRate,
            totalCacheSize = managerStats.totalCacheSize
        )
    }
    
    /**
     * 清理所有缓存
     */
    suspend fun clearAllCaches() {
        evictAllPreviews()
        cacheManager.clearAllCaches()
        Log.d(TAG, "All caches cleared")
    }
    
    data class CacheStats(
        val textPreviewCacheSize: Int,
        val textPreviewCacheHitRate: Float,
        val imagePreviewCacheSize: Int,
        val imagePreviewCacheHitRate: Float,
        val overallHitRate: Double,
        val totalCacheSize: Long
    )
}
