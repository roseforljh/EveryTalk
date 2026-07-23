package com.android.everytalk.statecontroller.controller.conversation

import androidx.collection.LruCache
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.util.ConversationNameHelper

/** 负责同步生成并缓存会话预览标题。 */
class ConversationPreviewController(
    private val stateHolder: ViewModelStateHolder,
) {
    private val textConversationPreviewCache = LruCache<String, String>(100)
    private val imageConversationPreviewCache = LruCache<String, String>(100)

    fun clearAllCaches() {
        textConversationPreviewCache.evictAll()
        imageConversationPreviewCache.evictAll()
    }

    fun setCachedTitle(stableId: String, title: String, isImageGeneration: Boolean) {
        val cache = if (isImageGeneration) imageConversationPreviewCache else textConversationPreviewCache
        cache.put(stableId, title.trim())
    }

    fun getConversationPreviewText(
        stableId: String,
        index: Int,
        isImageGeneration: Boolean = false,
    ): String {
        val conversation = conversationList(isImageGeneration).getOrNull(index)
            ?: return defaultConversationName(index, isImageGeneration)
        val cache = if (isImageGeneration) imageConversationPreviewCache else textConversationPreviewCache
        return cache.get(stableId) ?: generatePreview(conversation, isImageGeneration, index).also {
            cache.put(stableId, it)
        }
    }

    fun getConversationPreviewText(index: Int, isImageGeneration: Boolean = false): String {
        val conversation = conversationList(isImageGeneration).getOrNull(index)
            ?: return defaultConversationName(index, isImageGeneration)
        val stableId = ConversationNameHelper.resolveStableId(conversation) ?: "unknown_$index"
        return getConversationPreviewText(stableId, index, isImageGeneration)
    }

    private fun conversationList(isImageGeneration: Boolean): List<List<Message>> =
        if (isImageGeneration) {
            stateHolder._imageGenerationHistoricalConversations.value
        } else {
            stateHolder._historicalConversations.value
        }

    private fun generatePreview(
        conversation: List<Message>,
        isImageGeneration: Boolean,
        index: Int,
    ): String {
        conversation.firstOrNull {
            it.sender == Sender.System && it.isPlaceholderName && it.text.isNotBlank()
        }?.let { return it.text.trim() }

        val rawText = conversation.firstOrNull {
            it.sender == Sender.User && it.text.isNotBlank()
        }?.text?.trim()
        return if (rawText.isNullOrBlank()) {
            defaultConversationName(index, isImageGeneration)
        } else {
            ConversationNameHelper.cleanAndTruncateText(rawText, 50)
        }
    }

    private fun defaultConversationName(index: Int, isImageGeneration: Boolean): String =
        ConversationNameHelper.getDefaultConversationName(index, isImageGeneration)
}
