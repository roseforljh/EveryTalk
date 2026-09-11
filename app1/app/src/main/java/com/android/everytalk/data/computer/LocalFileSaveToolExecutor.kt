package com.android.everytalk.data.computer

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 已完成原子保存的产物；由 Android 适配层生成 URI，不从模型参数接收宿主路径。 */
data class LocalSavedFile(val file: File, val path: String, val sha256: String, val bytes: Long)

/**
 * 审批仅冻结路径、字节数、前后版本，不写磁盘。执行时核对可信审批和当前文件版本，
 * 因此提示注入、伪造 approved 参数、审批后换内容都不能越过保存边界。
 */
class LocalFileSaveToolExecutor(
    private val workspaceRoot: (String) -> File,
    private val bridge: LocalWorkspaceFileBridge = LocalWorkspaceFileBridge(),
    private val enabled: () -> Boolean = { true },
) {
    suspend fun approval(arguments: JsonObject, toolCallId: String, context: ComputerRequestContext): ComputerToolApprovalRequest.LocalFileWrite =
        withContext(Dispatchers.IO) {
            check(enabled()) { "本地文件工具已关闭" }
            require(context.workspaceId.isNotBlank() && context.conversationId.isNotBlank()) { "缺少本地 Workspace 上下文" }
            val (path, content) = parse(arguments)
            val target = bridge.target(workspaceRoot(context.workspaceId), path)
            val previous = if (target.exists()) hash(bridge.read(workspaceRoot(context.workspaceId), path)) else null
            ComputerToolApprovalRequest.LocalFileWrite(toolCallId, context, path, hash(content.toByteArray()), content.toByteArray().size.toLong(), previous)
        }

    suspend fun execute(arguments: JsonObject, toolCallId: String, context: ComputerRequestContext?): LocalSavedFile = withContext(Dispatchers.IO) {
        check(enabled()) { "本地文件工具已关闭" }
        requireNotNull(context) { "缺少本地 Workspace 上下文" }
        val approved = requireNotNull(context.approvedLocalFileWrite) { "CONFIRMATION_REQUIRED" }
        require(context.runId != null && approved.context.runId == context.runId) { "APPROVAL_RUN_MISMATCH" }
        val (path, content) = parse(arguments)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val digest = hash(bytes)
        require(context.approvedToolCallId == toolCallId && approved.toolCallId == toolCallId &&
            approved.context.workspaceId == context.workspaceId && approved.context.computerId == context.computerId &&
            approved.context.conversationId == context.conversationId &&
            approved.path == path && approved.contentSha256 == digest && approved.bytes == bytes.size.toLong()) { "APPROVAL_MISMATCH" }
        // 写入由单一 App 进程持有；同一份已批准工具被恢复或并发调度时只替换一次。
        saveMutex.withLock {
            val root = workspaceRoot(context.workspaceId)
            val target = bridge.target(root, path)
            val current = if (target.exists()) hash(bridge.read(root, path)) else null
            require(current == approved.previousSha256 || current == digest) { "RESOURCE_CHANGED" }
            if (current != digest) bridge.saveText(root, path, content)
            LocalSavedFile(target, path, digest, bytes.size.toLong())
        }
    }

    private fun parse(arguments: JsonObject): Pair<String, String> {
        val path = (arguments["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("path 无效")
        val content = (arguments["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("content 无效")
        require(content.toByteArray().size <= 8 * 1024 * 1024) { "文件超过 8 MiB 限制" }
        return path to content
    }

    companion object {
        private val saveMutex = Mutex()
        internal fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
