package com.android.everytalk.util

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
}