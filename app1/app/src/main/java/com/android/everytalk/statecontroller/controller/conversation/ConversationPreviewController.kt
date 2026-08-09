package com.android.everytalk.statecontroller.controller.conversation

import androidx.collection.LruCache
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.util.ConversationNameHelper

/** 负责同步生成并缓存会话预览标题。 */
class ConversationPreviewController(
    private val stateHolder: ViewModelStateHolder,
    private val defaultNameFactory: (Int, Boolean) -> String =
        ConversationNameHelper::getDefaultConversationName,
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
        cache.get(stableId)?.let { return it }
        val preview = generatePreview(conversation, isImageGeneration, index)
        if (hasCacheablePreview(conversation, isImageGeneration)) cache.put(stableId, preview)
        return preview
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
        }?.let { titleMessage ->
            val title = titleMessage.text.trim()
            val legacyIndex = legacyDefaultConversationIndex(title, isImageGeneration)
            return if (legacyIndex == null) title else defaultConversationName(legacyIndex, isImageGeneration)
        }

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
        defaultNameFactory(index, isImageGeneration)

    private fun hasCacheablePreview(
        conversation: List<Message>,
        isImageGeneration: Boolean,
    ): Boolean {
        val title = conversation.firstOrNull {
            it.sender == Sender.System && it.isPlaceholderName && it.text.isNotBlank()
        }?.text?.trim()
        if (title != null) return legacyDefaultConversationIndex(title, isImageGeneration) == null
        return conversation.any { it.sender == Sender.User && it.text.isNotBlank() }
    }
}

internal fun legacyDefaultConversationIndex(title: String, isImageGeneration: Boolean): Int? {
    val prefixes = if (isImageGeneration) {
        listOf("图像生成对话 ", "Image generation conversation ")
    } else {
        listOf("对话 ", "Conversation ")
    }
    val oneBasedIndex = prefixes.firstNotNullOfOrNull { prefix ->
        title.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.trim()
            ?.toIntOrNull()
    } ?: return null
    return (oneBasedIndex - 1).takeIf { it >= 0 }
}
