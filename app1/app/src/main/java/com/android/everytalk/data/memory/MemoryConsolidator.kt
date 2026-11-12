package com.android.everytalk.data.memory

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.MemoryDocumentEntity
import com.android.everytalk.data.network.SystemPromptModules
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 记忆写入流水线，参考 Codex memory v2：
 * 1. 第一阶段：闲置的会话各生成一份“任务经过”摘要（不是用户画像），每次最多处理 [PHASE1_LIMIT] 个；
 * 2. 第二阶段：把会话摘要和用户笔记（明确要求记住的内容、旧版记忆）整合成一份记忆总览。
 * 只在用户发送消息时用当前模型在后台运行；同一时间只跑一个，失败不影响聊天。
 */
class MemoryConsolidator(
    private val database: AppDatabase,
    private val generate: suspend (ChatRequest) -> MemoryModelReply,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val repository = MemoryRepository(database)
    private val dao = database.memoryDao()
    private val lock = Mutex()

    /** 已经在跑时直接返回；授权版本变化（关闭记忆、用户编辑）后不再写入。 */
    suspend fun runOnce(base: ChatRequest, currentConversationId: String, version: Long) {
        if (!lock.tryLock()) return
        try {
            if (!authorized(version)) return
            val deleted = database.chatDao().deletedConversationIds().toSet()
            // 会话被删除后，它的摘要也删除，第二阶段会据此从总览里去掉相关内容。会话还没同步到本机的摘要保留。
            dao.getDocuments(MemoryDocumentEntity.KIND_CONVERSATION).filter { it.id in deleted }.forEach { dao.deleteDocument(it.id) }
            summarizeIdleConversations(base, currentConversationId, version)
            consolidate(base, version)
            // 摘要变化投影到同步表，由同步流程上传给其他设备。
            com.android.everytalk.data.sync.SyncSettingsProjector(database).refresh()
            com.android.everytalk.data.sync.SyncLocalChanges.notifyChanged()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            // 后台整理失败不打扰聊天，也不把正文写进日志；下次发送时再试。
            android.util.Log.w("Memory", "consolidation_skipped")
        } finally { lock.unlock() }
    }

    /** 每次成功拿到回复都计入调用次数和 token，管理页据此展示记忆的开销。 */
    private suspend fun call(request: ChatRequest): String {
        val reply = withTimeout(TIMEOUT_MILLIS) { generate(request) }
        repository.recordModelCall(reply.tokens)
        return reply.text
    }

    private suspend fun authorized(version: Long): Boolean = repository.settings().let { it.enabled && it.version == version }

    private suspend fun summarizeIdleConversations(base: ChatRequest, currentConversationId: String, version: Long) {
        val time = now()
        val existing = dao.getDocuments(MemoryDocumentEntity.KIND_CONVERSATION).associateBy { it.id }
        val pending = database.chatDao().memoryConversationCandidates(currentConversationId, time - IDLE_MILLIS, time - WINDOW_MILLIS)
            .filter { it.messageCount >= 2 && (existing[it.conversationId]?.sourceUpdatedAt ?: Long.MIN_VALUE) < it.lastMessageAt }
            .take(PHASE1_LIMIT)
        for (candidate in pending) {
            val transcript = renderTranscript(database.chatDao().memoryConversationTranscript(candidate.conversationId))
            val output = call(request(base, SystemPromptModules.conversationMemory,
                "Conversation id: ${candidate.conversationId}\nTitle: ${candidate.title}\n\n$transcript", 1_500))
            // 解析失败也记下来源时间：同一份内容不再反复花费请求，有新消息时自然重写。
            val parsed = runCatching { parseConversationSummary(output) }.getOrNull() ?: ("" to "")
            if (!authorized(version)) return
            dao.upsertDocument(MemoryDocumentEntity(
                id = candidate.conversationId, kind = MemoryDocumentEntity.KIND_CONVERSATION,
                title = MemoryPolicy.redact(parsed.first.ifBlank { candidate.title }).take(120),
                content = MemoryPolicy.redact(parsed.second).takeMemoryCharacters(MAX_CONVERSATION_SUMMARY),
                sourceUpdatedAt = candidate.lastMessageAt, generatedAt = time,
            ))
        }
    }

    private suspend fun consolidate(base: ChatRequest, version: Long) {
        val summaries = dao.getDocuments(MemoryDocumentEntity.KIND_CONVERSATION).filter { it.content.isNotBlank() }.take(PHASE2_INPUTS)
        val notes = repository.state().entries.filter { it.enabled }
        val fingerprint = sha256(summaries.joinToString("\n") { "${it.id}:${it.generatedAt}" } + "\n--\n" +
            notes.joinToString("\n") { "${it.id}:${it.updatedAt}:${it.content}" })
        val overview = dao.getDocument(MemoryDocumentEntity.OVERVIEW_ID)
        if (overview?.inputFingerprint == fingerprint) return
        if (summaries.isEmpty() && notes.isEmpty()) {
            if (authorized(version)) dao.deleteDocument(MemoryDocumentEntity.OVERVIEW_ID)
            return
        }
        val zone = java.time.ZoneId.systemDefault()
        val input = buildString {
            append("Existing overview (may be empty or outdated):\n").append(overview?.content.orEmpty().ifBlank { "(none)" }).append("\n\n")
            append("Conversation summaries, newest first:\n")
            summaries.forEach { summary ->
                val date = java.time.Instant.ofEpochMilli(summary.sourceUpdatedAt).atZone(zone).toLocalDate()
                append("\n### conv:${summary.id} | $date | ${summary.title}\n").append(summary.content).append('\n')
            }
            append("\nUser notes:\n")
            if (notes.isEmpty()) append("(none)\n")
            notes.forEach { append("- (${it.category}) ").append(it.content.takeMemoryCharacters(600)).append('\n') }
        }
        val output = call(request(base, SystemPromptModules.memoryConsolidation, input, 3_000))
            .trim().removePrefix("```markdown").removePrefix("```").removeSuffix("```").trim()
        require(output.contains("## What's in memory")) { "总览格式无效" }
        if (!authorized(version)) return
        dao.upsertDocument(MemoryDocumentEntity(
            id = MemoryDocumentEntity.OVERVIEW_ID, kind = MemoryDocumentEntity.KIND_OVERVIEW, title = "",
            content = MemoryPolicy.redact(output).takeMemoryCharacters(MAX_OVERVIEW),
            sourceUpdatedAt = summaries.maxOfOrNull { it.sourceUpdatedAt } ?: 0L, generatedAt = now(), inputFingerprint = fingerprint,
        ))
    }

    companion object {
        const val PHASE1_LIMIT = 3
        const val PHASE2_INPUTS = 40
        const val MAX_CONVERSATION_SUMMARY = 2_000
        const val MAX_OVERVIEW = 6_000
        const val IDLE_MILLIS = 10 * 60_000L
        const val WINDOW_MILLIS = 30L * 24 * 60 * 60_000L
        private const val TIMEOUT_MILLIS = 90_000L
        private const val TRANSCRIPT_HEAD = 6_000
        private const val TRANSCRIPT_TAIL = 10_000
        private const val LINE_LIMIT = 2_000

        /** 会话太长时保留开头和结尾（与 Codex 相同），中间用省略标记代替。 */
        internal fun renderTranscript(lines: List<com.android.everytalk.data.database.daos.MemoryTranscriptLine>): String {
            val text = lines.joinToString("\n\n") { line ->
                "${if (line.sender == "User") "User" else "AI"}: ${line.text.trim().takeMemoryCharacters(LINE_LIMIT)}"
            }
            if (text.memoryCharacters() <= TRANSCRIPT_HEAD + TRANSCRIPT_TAIL) return text
            val tailStart = text.offsetByCodePoints(0, text.memoryCharacters() - TRANSCRIPT_TAIL)
            return text.takeMemoryCharacters(TRANSCRIPT_HEAD) + "\n\n[… middle of the conversation omitted …]\n\n" + text.substring(tailStart)
        }

        internal fun parseConversationSummary(output: String): Pair<String, String> {
            val json = output.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val root = Json.parseToJsonElement(json).jsonObject
            fun field(key: String) = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            return field("title") to field("summary")
        }

        /** 复用当前对话的模型配置，去掉工具和所有附加上下文，只发这一个任务。 */
        internal fun request(base: ChatRequest, system: String, user: String, maxOutputTokens: Int): ChatRequest = base.copy(
            messages = listOf(SimpleTextApiMessage(role = "system", content = system), SimpleTextApiMessage(role = "user", content = user)),
            tools = null, toolChoice = null, useWebSearch = false, qwenEnableSearch = false, enableCodeExecution = false,
            customModelParameters = null, customExtraBody = null, contextManagement = null,
            localComputerRequestContext = null, localSkillSnapshot = null, localMemorySnapshot = null, localProviderContinuation = null,
            generationConfig = GenerationConfig(maxOutputTokens = maxOutputTokens),
        )

        private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
