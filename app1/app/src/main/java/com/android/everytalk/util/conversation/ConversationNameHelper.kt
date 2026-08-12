package com.android.everytalk.util

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender

/**
 * 对话名称辅助工具类 - 统一管理对话名称生成逻辑
 */
object ConversationNameHelper {
    
    // 预编译的正则表达式，避免重复编译
    private val WHITESPACE_REGEX = Regex("\\s+")
    
    /**
     * 获取默认对话名称
     */
    fun getDefaultConversationName(index: Int, isImageGeneration: Boolean): String {
        return if (isImageGeneration) {
            "图像生成对话 ${index + 1}"
        } else {
            "对话 ${index + 1}"
        }
    }
    
    /**
     * 获取空对话的默认名称
     */
    fun getEmptyConversationName(isImageGeneration: Boolean): String {
        return if (isImageGeneration) {
            "图像生成对话"
        } else {
            "新对话"
        }
    }
    
    /**
     * 获取无内容对话的默认名称
     */
    fun getNoContentConversationName(isImageGeneration: Boolean): String {
        return if (isImageGeneration) {
            "图像生成对话"
        } else {
            "对话"
        }
    }

    /**
     * 读取持久化在历史会话中的自定义名称。
     * 名称属于会话元数据，虽然旧数据结构使用 System 消息承载，但它不应进入聊天内容。
     */
    fun getStoredConversationTitle(conversation: List<Message>): String? {
        return conversation.firstOrNull(::isStoredConversationTitle)
            ?.text
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    /** 从历史会话中移除名称元数据，供聊天界面和模型上下文使用。 */
    fun withoutStoredConversationTitle(conversation: List<Message>): List<Message> {
        return conversation.filterNot(::isStoredConversationTitle)
    }

    /**
     * 保存已加载会话的新消息时，把历史中的名称元数据合并回来。
     * 历史列表中的名称是权威值，可防止聊天消息快照覆盖用户刚修改的名称。
     */
    fun preserveStoredConversationTitle(
        storedConversation: List<Message>,
        currentMessages: List<Message>,
    ): List<Message> {
        val storedTitle = storedConversation.firstOrNull(::isStoredConversationTitle)
            ?: currentMessages.firstOrNull(::isStoredConversationTitle)
        val chatMessages = withoutStoredConversationTitle(currentMessages)
        return if (storedTitle == null) chatMessages else listOf(storedTitle) + chatMessages
    }

    private fun isStoredConversationTitle(message: Message): Boolean {
        return message.sender == Sender.System && message.isPlaceholderName
    }
    
    /**
     * 清理和截断文本，用于生成对话预览
     * 优化：使用预编译正则和字符串方法替代正则
     */
    fun cleanAndTruncateText(text: String, maxLength: Int = 50): String {
        // 优化：直接用字符串方法替换换行，避免正则开销
        val cleanText = text
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(WHITESPACE_REGEX, " ") // 使用预编译的正则
            .trim()
        
        return if (cleanText.length <= maxLength) {
            cleanText
        } else {
            val truncateLength = maxLength - 3
            val truncated = cleanText.take(truncateLength)
            val lastSpace = truncated.lastIndexOf(' ')
            
            // 如果最后一个空格位置合理（不在开头附近），则在空格处截断
            if (lastSpace > truncateLength / 3) {
                truncated.take(lastSpace) + "..."
            } else {
                // 否则直接截断并添加省略号
                truncated + "..."
            }
        }
    }
    /**
     * 解析会话的稳定ID（用于置顶标识、缓存键等）
     * 优先使用首条User消息ID，其次非占位System消息ID，最后使用首条消息ID
     */
    fun resolveStableId(conversation: List<Message>?): String? {
        if (conversation.isNullOrEmpty()) return null
        return conversation.firstOrNull { it.sender == Sender.User }?.id
            ?: conversation.firstOrNull { it.sender == Sender.System && !it.isPlaceholderName }?.id
            ?: conversation.firstOrNull()?.id
    }
}
