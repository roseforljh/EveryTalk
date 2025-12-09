package com.android.everytalk.util.cache

import android.content.Context
import androidx.collection.LruCache
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.ConversationNameHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * 增强的缓存管理器 - 优化应用性能
 */
class CacheManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "CacheManager"
        
        @Volatile
        private var INSTANCE: CacheManager? = null
        
        fun getInstance(context: Context): CacheManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CacheManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val logger = AppLogger.forComponent("CacheManager")
    private val cacheMutex = Mutex()
    
    // 多级缓存系统
    private val conversationPreviewCache = LruCache<String, String>(200)
    private val messageContentCache = LruCache<String, String>(500)
    private val processedMarkdownCache = LruCache<String, String>(300)
    private val apiResponseCache = LruCache<String, String>(100)
    private val imageMetadataCache = LruCache<String, ImageMetadata>(100)
    
    // 异步缓存刷新队列
    private val refreshQueue = ConcurrentHashMap<String, Job>()
    private val cacheScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    data class ImageMetadata(
        val width: Int,
        val height: Int,
        val size: Long,
        val format: String
    )
    
    /**
     * 获取会话预览文本（带缓存）
     */
    suspend fun getConversationPreview(
        conversationId: String,
        messages: List<Message>,
        isImageGeneration: Boolean = false
    ): String = withContext(Dispatchers.Default) {
        val cacheKey = "${if (isImageGeneration) "img" else "txt"}_${conversationId}_${messages.size}_${messages.hashCode()}"
        
        conversationPreviewCache.get(cacheKey)?.let { cached ->
            logger.debug("Cache hit for conversation preview: $conversationId")
            return@withContext cached
        }
        
        val preview = generatePreviewText(messages, isImageGeneration)
        conversationPreviewCache.put(cacheKey, preview)
        logger.debug("Cache miss, generated preview for: $conversationId")
        preview
    }
    
    /**
     * 获取处理后的消息内容（带缓存）
     */
    suspend fun getProcessedMessageContent(
        messageId: String,
        content: String,
        processor: suspend (String) -> String
    ): String = withContext(Dispatchers.Default) {
        val cacheKey = "${messageId}_${content.hashCode()}"
        
        messageContentCache.get(cacheKey)?.let { cached ->
            logger.debug("Cache hit for message content: $messageId")
            return@withContext cached
        }
        
        val processed = processor(content)
        messageContentCache.put(cacheKey, processed)
        logger.debug("Cache miss, processed content for: $messageId")
        processed
    }
    
    /**
     * 获取处理后的Markdown内容（带缓存）
     */
    suspend fun getProcessedMarkdown(
        content: String,
        processor: suspend (String) -> String
    ): String = withContext(Dispatchers.Default) {
        val cacheKey = content.hashCode().toString()
        
        processedMarkdownCache.get(cacheKey)?.let { cached ->
            logger.debug("Cache hit for markdown content")
            return@withContext cached
        }
        
        val processed = processor(content)
        processedMarkdownCache.put(cacheKey, processed)
        logger.debug("Cache miss, processed markdown content")
        processed
    }
    
    /**
     * 缓存API响应
     */
    suspend fun cacheApiResponse(
        requestKey: String,
        response: String,
        ttlMs: Long = 300_000L // 5分钟默认TTL
    ) {
        cacheMutex.withLock {
            apiResponseCache.put(requestKey, response)
            
            // 设置自动过期
            refreshQueue[requestKey]?.cancel()
            refreshQueue[requestKey] = cacheScope.launch {
                delay(ttlMs)
                apiResponseCache.remove(requestKey)
                refreshQueue.remove(requestKey)
            }
        }
    }
    
    /**
     * 获取缓存的API响应
     */
    fun getCachedApiResponse(requestKey: String): String? {
        return apiResponseCache.get(requestKey)
    }
    
    /**
     * 缓存图片元数据
     */
    fun cacheImageMetadata(url: String, metadata: ImageMetadata) {
        imageMetadataCache.put(url, metadata)
    }
    
    /**
     * 获取图片元数据
     */
    fun getImageMetadata(url: String): ImageMetadata? {
        return imageMetadataCache.get(url)
    }
    
    /**
     * 预热缓存 - 异步加载常用数据
     */
    fun warmupCache(conversations: List<List<Message>>) {
        cacheScope.launch {
            logger.info("Starting cache warmup for ${conversations.size} conversations")
            
            conversations.forEachIndexed { index, messages ->
                try {
                    // 预生成会话预览
                    val conversationId = "conv_$index"
                    getConversationPreview(conversationId, messages)
                    
                    // 预处理消息内容
                    messages.forEach { message ->
                        if (message.text.isNotBlank()) {
                            launch {
                                getProcessedMessageContent(message.id, message.text) { it }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Error warming up cache for conversation $index: ${e.message}")
                }
            }
            
            logger.info("Cache warmup completed")
        }
    }
    
    /**
     * 智能缓存清理 - 根据内存压力自动清理
     */
    suspend fun smartCleanup() = withContext(Dispatchers.Default) {
        cacheMutex.withLock {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsage = usedMemory.toDouble() / maxMemory.toDouble()
            
            if (memoryUsage > 0.8) { // 如果内存使用超过80%
                logger.warn("High memory usage detected (${"%.1f".format(memoryUsage * 100)}%), performing aggressive cache cleanup")
                
                // 清理最旧的缓存条目
                conversationPreviewCache.trimToSize(conversationPreviewCache.maxSize() / 2)
                messageContentCache.trimToSize(messageContentCache.maxSize() / 2)
                processedMarkdownCache.trimToSize(processedMarkdownCache.maxSize() / 2)
                apiResponseCache.evictAll() // API响应缓存完全清理
                
                System.gc() // 建议垃圾回收
            } else if (memoryUsage > 0.6) { // 如果内存使用超过60%
                logger.info("Moderate memory usage detected (${"%.1f".format(memoryUsage * 100)}%), performing light cache cleanup")
                
                // 轻度清理
                apiResponseCache.trimToSize(max(apiResponseCache.size() / 2, 10))
            }
        }
    }
    
    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            conversationPreviewHits = conversationPreviewCache.hitCount().toLong(),
            conversationPreviewMisses = conversationPreviewCache.missCount().toLong(),
            messageContentHits = messageContentCache.hitCount().toLong(),
            messageContentMisses = messageContentCache.missCount().toLong(),
            markdownHits = processedMarkdownCache.hitCount().toLong(),
            markdownMisses = processedMarkdownCache.missCount().toLong(),
            apiResponseSize = apiResponseCache.size().toLong(),
            totalCacheSize = (conversationPreviewCache.size() + 
                           messageContentCache.size() + 
                           processedMarkdownCache.size() + 
                           apiResponseCache.size()).toLong()
        )
    }
    
    /**
     * 清理所有缓存
     */
    suspend fun clearAllCaches() {
        cacheMutex.withLock {
            conversationPreviewCache.evictAll()
            messageContentCache.evictAll()
            processedMarkdownCache.evictAll()
            apiResponseCache.evictAll()
            imageMetadataCache.evictAll()
            
            // 取消所有刷新任务
            refreshQueue.values.forEach { it.cancel() }
            refreshQueue.clear()
            
            logger.info("All caches cleared")
        }
    }
    
    private fun generatePreviewText(messages: List<Message>, isImageGeneration: Boolean): String {
        if (messages.isEmpty()) {
            return ConversationNameHelper.getEmptyConversationName(isImageGeneration)
        }
        
        val firstUserMessage = messages.firstOrNull { 
            it.sender == com.android.everytalk.data.DataClass.Sender.User &&
            it.text.isNotBlank() 
        }
        
        val rawText = firstUserMessage?.text?.trim()
        if (rawText.isNullOrBlank()) {
            return ConversationNameHelper.getNoContentConversationName(isImageGeneration)
        }
        
        return ConversationNameHelper.cleanAndTruncateText(rawText, 50)
    }
    
    data class CacheStats(
        val conversationPreviewHits: Long,
        val conversationPreviewMisses: Long,
        val messageContentHits: Long,
        val messageContentMisses: Long,
        val markdownHits: Long,
        val markdownMisses: Long,
        val apiResponseSize: Long,
        val totalCacheSize: Long
    ) {
        val overallHitRate: Double
            get() {
                val totalHits = conversationPreviewHits + messageContentHits + markdownHits
                val totalRequests = totalHits + conversationPreviewMisses + messageContentMisses + markdownMisses
                return if (totalRequests > 0) totalHits.toDouble() / totalRequests.toDouble() else 0.0
            }
    }
    
    fun cleanup() {
        cacheScope.cancel()
        refreshQueue.values.forEach { it.cancel() }
        refreshQueue.clear()
    }
}