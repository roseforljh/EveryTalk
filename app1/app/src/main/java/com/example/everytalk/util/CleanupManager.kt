package com.example.everytalk.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 会话历史清理聚合器：
 * - 删除单会话：按消息ID前缀删除相关附件 + 可选清理 WebView（如有嵌入渲染）
 * - 清空所有会话历史：清空附件目录 + WebView 缓存/Cookie
 * - 统计占用：附件大小 + WebView 缓存大小
 */
class CleanupManager(private val context: Context) {

    private val fileManager by lazy { FileManager(context) }

    /**
     * 用户删除“单个会话”时调用：
     * - 定向移除该会话产生的附件（图片/文档/视频/音频等）
     * - 如你的会话内使用过 WebView 渲染，可选清理 WebView 缓存（一般保留即可）
     *
     * @param messageIdHints 该会话内所有消息的可识别前缀/ID（用于匹配命名规则中的 _{messageIdHint}_{attachmentIndex}_）
     * @param alsoClearWebView 是否同时清理 WebView 缓存（默认 false，避免影响其他会话网页登陆态）
     * @return 实际删除文件数量
     */
    suspend fun onDeleteSingleConversation(
        messageIdHints: List<String>,
        alsoClearWebView: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        runCatching { deleted += fileManager.deleteAttachmentsByMessageHints(messageIdHints) }
        if (alsoClearWebView) {
            runCatching { fileManager.clearWebViewCaches() }
        }
        deleted
    }

    /**
     * 用户“清空所有会话历史”时调用：
     * - 一键清空附件目录 + WebView 缓存/Cookie
     * - 返回估算释放的字节数（ROM/权限可能影响统计准确性）
     */
    suspend fun onClearAllConversations(): Long {
        return fileManager.clearAllConversationStorage()
    }

    /**
     * 统计当前占用：
     * @return Pair(附件占用字节, WebView 缓存占用字节)
     */
    suspend fun reportStorageUsage(): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val attachments = runCatching { fileManager.getChatAttachmentsSizeBytes() }.getOrElse { 0L }
        val webview = runCatching { fileManager.getWebViewCacheSizeBytes() }.getOrElse { 0L }
        attachments to webview
    }
}