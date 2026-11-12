package com.android.everytalk.data.database.daos

/** 记忆第一阶段使用的轻量查询结果，不加载附件和执行轨迹。 */
data class MemoryConversationCandidate(
    val conversationId: String,
    val title: String,
    val lastMessageAt: Long,
    val messageCount: Int,
)

data class MemoryTranscriptLine(val sender: String, val text: String, val timestamp: Long)

data class MemoryDocumentStats(val conversations: Int, val lastGeneratedAt: Long)
