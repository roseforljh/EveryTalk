package com.android.everytalk.util.share

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话导出工具类，负责将会话导出为 Markdown 格式并分享
 */
object ConversationExporter {
    private const val TAG = "ConversationExporter"

    /**
     * 将会话转换为 Markdown 格式
     */
    fun convertToMarkdown(
        messages: List<Message>,
        title: String? = null,
        includeTimestamp: Boolean = true
    ): String {
        val sb = StringBuilder()

        // 标题
        val effectiveTitle = title ?: generateTitle(messages)
        sb.appendLine("# $effectiveTitle")
        sb.appendLine()

        // 导出时间
        if (includeTimestamp) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sb.appendLine("*导出时间: ${dateFormat.format(Date())}*")
            sb.appendLine()
        }

        sb.appendLine("---")
        sb.appendLine()

        // 过滤掉占位标题和系统提示
        val filteredMessages = messages.filter { msg ->
            when {
                msg.sender == Sender.System && msg.isPlaceholderName -> false
                else -> true
            }
        }

        // 消息内容
        for (message in filteredMessages) {
            val roleLabel = when (message.sender) {
                Sender.User -> "**用户**"
                Sender.AI -> "**AI**"
                Sender.System -> "**系统**"
                Sender.Tool -> "**工具**"
            }

            sb.appendLine("### $roleLabel")
            sb.appendLine()

            // 处理思考过程 (reasoning)
            if (!message.reasoning.isNullOrBlank()) {
                sb.appendLine("<details>")
                sb.appendLine("<summary>思考过程</summary>")
                sb.appendLine()
                sb.appendLine(message.reasoning.trim())
                sb.appendLine()
                sb.appendLine("</details>")
                sb.appendLine()
            }

            // 消息正文
            if (message.text.isNotBlank()) {
                sb.appendLine(message.text.trim())
                sb.appendLine()
            }

            // 图片 URL
            if (!message.imageUrls.isNullOrEmpty()) {
                sb.appendLine("**生成的图片:**")
                message.imageUrls.forEachIndexed { index, url ->
                    sb.appendLine("- 图片 ${index + 1}: $url")
                }
                sb.appendLine()
            }

            // 网络搜索结果
            if (!message.webSearchResults.isNullOrEmpty()) {
                sb.appendLine("**网络搜索结果:**")
                message.webSearchResults.forEach { result ->
                    sb.appendLine("- [${result.title}](${result.href})")
                }
                sb.appendLine()
            }

            sb.appendLine("---")
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * 从消息列表生成标题
     */
    private fun generateTitle(messages: List<Message>): String {
        // 优先使用占位标题
        val titleMessage = messages.firstOrNull { it.sender == Sender.System && it.isPlaceholderName }
        if (titleMessage != null && titleMessage.text.isNotBlank()) {
            return titleMessage.text.trim()
        }

        // 否则使用第一条用户消息
        val firstUserMessage = messages.firstOrNull { it.sender == Sender.User && it.text.isNotBlank() }
        if (firstUserMessage != null) {
            val text = firstUserMessage.text.trim()
            return if (text.length > 50) text.take(47) + "..." else text
        }

        return "对话记录"
    }

    /**
     * 导出会话为 Markdown 文件并分享
     */
    fun shareConversation(
        context: Context,
        messages: List<Message>,
        title: String? = null
    ) {
        try {
            val markdown = convertToMarkdown(messages, title)
            val effectiveTitle = title ?: generateTitle(messages)

            // 创建临时文件
            val cacheDir = File(context.cacheDir, "shared_conversations")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            // 清理文件名中的非法字符
            val safeFileName = effectiveTitle
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(50)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "${safeFileName}_$timestamp.md"

            val file = File(cacheDir, fileName)
            file.writeText(markdown, Charsets.UTF_8)

            Log.d(TAG, "Markdown file created: ${file.absolutePath}, size: ${file.length()}")

            // 使用 FileProvider 获取 URI
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            // 创建分享 Intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, effectiveTitle)
                putExtra(Intent.EXTRA_TEXT, "分享自 EveryTalk: $effectiveTitle")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooserIntent = Intent.createChooser(shareIntent, "分享会话")
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooserIntent)

            Log.d(TAG, "Share intent launched for conversation: $effectiveTitle")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share conversation", e)
        }
    }

    /**
     * 清理临时分享文件
     */
    fun cleanupSharedFiles(context: Context, maxAgeMs: Long = 24 * 60 * 60 * 1000) {
        try {
            val cacheDir = File(context.cacheDir, "shared_conversations")
            if (cacheDir.exists()) {
                val now = System.currentTimeMillis()
                cacheDir.listFiles()?.forEach { file ->
                    if (now - file.lastModified() > maxAgeMs) {
                        file.delete()
                        Log.d(TAG, "Cleaned up old shared file: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup shared files", e)
        }
    }
}
