package com.example.everytalk.util

import com.example.everytalk.data.DataClass.Message

// 放在 AppViewModel.kt 文件内，或者一个相关的工具类中
private fun areHistoriesEffectivelyModified(
    originalHistory: List<List<Message>>,
    processedHistory: List<List<Message>>
): Boolean {
    if (originalHistory.size != processedHistory.size) return true // 理论上大小应该一样
    for (i in originalHistory.indices) {
        val originalConversation = originalHistory[i]
        val processedConversation = processedHistory[i]
        if (originalConversation.size != processedConversation.size) return true // 同上
        for (j in originalConversation.indices) {
            // 主要关心 htmlContent 是否从 null 变成了非 null
            if (originalConversation[j].htmlContent == null && processedConversation[j].htmlContent != null) {
                return true
            }
            // 如果还需要比较其他字段是否在处理中可能被意外修改，也可以加入
            // if (originalConversation[j] != processedConversation[j]) return true // 这是一个更严格的比较
        }
    }
    return false
}