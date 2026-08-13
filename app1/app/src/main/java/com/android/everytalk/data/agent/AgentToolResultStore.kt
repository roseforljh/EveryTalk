package com.android.everytalk.data.agent

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

private const val AGENT_TOOL_RESULTS_DIRECTORY = "agent_tool_results"
private const val MAX_TOOL_RESULT_FILE_BYTES = 8L * 1024L * 1024L

/** 大型工具结果的 App 私有归档引用。路径始终相对 filesDir，禁止保存任意绝对路径。 */
data class AgentToolResultArchive(
    val relativePath: String,
    val byteCount: Long,
    val sha256: String,
)

/**
 * 在模型截断前保存完整工具结果。归档失败不阻断 Tool Result，调用方仍发送受控内容。
 * 文件名只使用 Run ID 和 Tool Call ID 的摘要，避免路径穿越和泄露模型提供的标识。
 */
class AgentToolResultStore(context: Context) {
    private val filesDirectory = context.applicationContext.filesDir
    private val root = File(filesDirectory, AGENT_TOOL_RESULTS_DIRECTORY)

    suspend fun archive(runId: String, toolCallId: String, result: JsonElement): AgentToolResultArchive? =
        withContext(Dispatchers.IO) {
            val bytes = result.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size.toLong() > MAX_TOOL_RESULT_FILE_BYTES) return@withContext null
            runCatching {
                val sessionDirectory = File(root, sha256(runId)).apply { mkdirs() }
                val file = File(sessionDirectory, "${sha256(toolCallId)}.json")
                val temporary = File(sessionDirectory, "${file.name}.tmp")
                temporary.outputStream().buffered().use { output ->
                    output.write(bytes)
                    output.flush()
                }
                if (file.exists() && !file.delete()) error("无法替换旧工具结果")
                if (!temporary.renameTo(file)) {
                    temporary.delete()
                    error("无法提交工具结果")
                }
                AgentToolResultArchive(
                    relativePath = file.relativeTo(filesDirectory).invariantSeparatorsPath,
                    byteCount = bytes.size.toLong(),
                    sha256 = sha256(bytes),
                )
            }.getOrNull()
        }

    suspend fun deleteRun(runId: String) {
        withContext(Dispatchers.IO) {
            File(root, sha256(runId)).deleteRecursively()
        }
    }

    private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
