package com.android.everytalk.util.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 会话历史清理聚合器：
 * - 删除单会话：按消息ID前缀删除相关附件
 * - 清空所有会话历史：清空附件目录
 * - 统计占用：附件大小
 */
class CleanupManager(private val context: Context) {

    private val fileManager by lazy { FileManager(context) }

    /**
     * 用户删除"单个会话"时调用：
     * - 定向移除该会话产生的附件（图片/文档/视频/音频等）
     *
     * @param messageIdHints 该会话内所有消息的可识别前缀/ID
     * @return 实际删除文件数量
     */
    suspend fun onDeleteSingleConversation(
        messageIdHints: List<String>
    ): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        runCatching { deleted += fileManager.deleteAttachmentsByMessageHints(messageIdHints) }
        deleted
    }

    /**
     * 用户"清空所有会话历史"时调用：
     * - 一键清空附件目录
     * - 返回估算释放的字节数（ROM/权限可能影响统计准确性）
     */
    suspend fun onClearAllConversations(): Long {
        return fileManager.clearAllConversationStorage()
    }

    /**
     * 统计当前占用：附件占用字节
     */
    suspend fun reportStorageUsage(): Long = withContext(Dispatchers.IO) {
        runCatching { fileManager.getChatAttachmentsSizeBytes() }.getOrElse { 0L }
    }
}