package com.android.everytalk.util

/**
 * 对话名称辅助工具类 - 统一管理对话名称生成逻辑
 */
object ConversationNameHelper {
    
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
     */
    fun cleanAndTruncateText(text: String, maxLength: Int = 50): String {
        val cleanText = text
            .replace(Regex("\\s+"), " ") // 合并多个空白字符为单个空格
            .replace(Regex("[\\r\\n]+"), " ") // 将换行替换为空格
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