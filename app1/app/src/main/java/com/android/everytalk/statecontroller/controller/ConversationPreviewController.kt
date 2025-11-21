package com.android.everytalk.statecontroller.controller

import android.util.Log
import androidx.collection.LruCache
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.util.CacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 负责生成和缓存会话预览标题：
 * - 快速预览（同步）：用于首帧快速显示
 * - 高质量预览（异步）：利用 CacheManager 进行更完整的摘要生成后回填缓存
 *
 * 复用 AppViewModel 现有的两个 LruCache，以保持内存占用与原实现一致。
 */
class ConversationPreviewController(
    private val stateHolder: ViewModelStateHolder,
    private val cacheManager: CacheManager,
    private val scope: CoroutineScope,
    // 由外部传入以沿用原有缓存对象
    private val textConversationPreviewCache: LruCache<String, String>,
    private val imageConversationPreviewCache: LruCache<String, String>
) {

    /**
     * 清空本地预览缓存（文本与图像）。
     * 用于：清空历史、低内存回调、显式缓存清理等场景。
     */
    fun clearAllCaches() {
        textConversationPreviewCache.evictAll()
        imageConversationPreviewCache.evictAll()
    }

    /**
     * 设置/覆盖指定会话索引的预览标题缓存。
     * 用于重命名后立即更新本地缓存，避免 UI 延迟。
     */
    fun setCachedTitle(stableId: String, title: String, isImageGeneration: Boolean) {
        val cache = if (isImageGeneration) imageConversationPreviewCache else textConversationPreviewCache
        cache.remove(stableId)
        cache.put(stableId, title.trim())
    }

    /**
     * 获取某个会话的预览文本，带本地 LruCache 与异步高质量回填。
     */
    fun getConversationPreviewText(stableId: String, index: Int, isImageGeneration: Boolean = false): String {
        val conversationList = if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value
        } else {
            stateHolder._historicalConversations.value
        }

        val conversation = conversationList.getOrNull(index) ?: return getDefaultConversationName(index, isImageGeneration)

        val cache = if (isImageGeneration) imageConversationPreviewCache else textConversationPreviewCache

        // 命中缓存立即返回
        cache.get(stableId)?.let { cached -> return cached }

        // 生成快速预览并写入缓存
        val preview = generateQuickPreview(conversation, isImageGeneration, index)
        cache.put(stableId, preview)

        // 异步生成高质量预览，若不同则更新缓存
        val cacheKey = "${if (isImageGeneration) "img" else "txt"}_$stableId"
        scope.launch {
            try {
                val highQualityPreview = cacheManager.getConversationPreview(cacheKey, conversation, isImageGeneration)
                if (highQualityPreview != preview) {
                    cache.put(stableId, highQualityPreview)
                }
            } catch (e: Exception) {
                // 静默处理，避免影响 UI
                Log.w("ConversationPreview", "高质量预览生成失败 index=$index", e)
            }
        }

        return preview
    }
    
    // 兼容旧方法，但建议迁移到带 stableId 的版本
    fun getConversationPreviewText(index: Int, isImageGeneration: Boolean = false): String {
        // 尝试从会话列表中获取 stableId
        val conversationList = if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value
        } else {
            stateHolder._historicalConversations.value
        }
        val conversation = conversationList.getOrNull(index) ?: return getDefaultConversationName(index, isImageGeneration)
        
        val stableId = com.android.everytalk.util.ConversationNameHelper.resolveStableId(conversation) ?: "unknown_$index"
            
        return getConversationPreviewText(stableId, index, isImageGeneration)
    }

    private fun generateQuickPreview(conversation: List<Message>, isImageGeneration: Boolean, index: Int): String {
        val firstUserMessage = conversation.firstOrNull {
            it.sender == com.android.everytalk.data.DataClass.Sender.User && it.text.isNotBlank()
        }
        val rawText = firstUserMessage?.text?.trim()
        if (rawText.isNullOrBlank()) {
            return getDefaultConversationName(index, isImageGeneration)
        }
        return com.android.everytalk.util.ConversationNameHelper.cleanAndTruncateText(rawText, 40)
    }

    private fun getDefaultConversationName(index: Int, isImageGeneration: Boolean): String {
        return com.android.everytalk.util.ConversationNameHelper.getDefaultConversationName(index, isImageGeneration)
    }
}