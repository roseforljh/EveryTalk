package com.android.everytalk.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 记忆系统的派生文档（参考 Codex memory v2），只存在本机，可随时从会话重新生成：
 * - kind = conversation：单个会话的任务经过摘要，id 为会话 ID；
 * - kind = overview：全局记忆总览，id 固定为 [MemoryDocumentEntity.OVERVIEW_ID]。
 */
@Entity(tableName = "memory_documents")
data class MemoryDocumentEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val content: String,
    /** 生成时来源的最后更新时间；会话有新消息后据此判断需要重写。 */
    val sourceUpdatedAt: Long,
    val generatedAt: Long,
    /** 总览的输入指纹；输入没变就不重复整合。 */
    val inputFingerprint: String = "",
) {
    companion object {
        const val KIND_CONVERSATION = "conversation"
        const val KIND_OVERVIEW = "overview"
        const val OVERVIEW_ID = "memory-overview"
    }
}
