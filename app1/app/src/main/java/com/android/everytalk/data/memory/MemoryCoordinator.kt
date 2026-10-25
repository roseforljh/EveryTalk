package com.android.everytalk.data.memory

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.agent.AgentLoopRequest
import com.android.everytalk.data.agent.AgentRunStatus
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.data.network.SystemPromptModules
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.statecontroller.RequestTokenEstimator
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 应用级 Agent 记忆协调器；一次发送一份快照，提取任务与已成功的回复互相隔离。 */
class MemoryCoordinator(
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    private val extraction: suspend (ChatRequest) -> String = ::extractMemoryJson,
) {
    private val repository = MemoryRepository(database)
    private val tool = MemoryTool(repository, ConversationHistorySearch(database))
    private val snapshots = ConcurrentHashMap<String, MemoryRequestSnapshot>()

    /** 先预留 token，再由现有 ContextManager 裁剪历史；记忆正文不放进可持久化消息。 */
    suspend fun prepare(input: AgentLoopRequest): AgentLoopRequest = try {
        prepareAvailable(input)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) {
        // 可选记忆读取失败不能让普通聊天失效；撤掉记忆工具，禁止降级为未校验写入。
        android.util.Log.w("Memory", "read_skipped")
        input.copy(request = input.request.copy(messages = MemoryPromptRenderer.forHistory(input.request.messages),
            tools = withoutMemoryTool(input.request), localMemorySnapshot = null))
    }

    private suspend fun prepareAvailable(input: AgentLoopRequest): AgentLoopRequest {
        val state = repository.state()
        val history = MemoryPromptRenderer.forHistory(input.request.messages)
        val tools = withoutMemoryTool(input.request)
        if (!state.settings.enabled) return input.copy(request = input.request.copy(messages = history, tools = tools, localMemorySnapshot = null))
        val user = database.chatDao().getMessage(input.sessionId, input.userMessageId)?.text
            ?: input.request.messages.lastOrNull { it.role == "user" }?.let { message -> when (message) {
                is SimpleTextApiMessage -> message.content
                is PartsApiMessage -> message.parts.filterIsInstance<ApiContentPart.Text>().joinToString("\n") { it.text }
                else -> ""
            } }.orEmpty()
        val prompt = MemoryPromptRenderer.render(state.entries, user,
            minOf(MemoryPolicy.MAX_PROMPT_CHARACTERS, (input.tokenLimits.maxContextTokens - input.tokenLimits.maxOutputTokens) / 8),
            conversationId = input.sessionId)
        val reserve = if (prompt.isEmpty()) 0 else RequestTokenEstimator.estimate(
            listOf(SimpleTextApiMessage(role = "system", content = prompt)), tools = null,
        ).totalInputTokens.toInt()
        val snapshot = MemoryRequestSnapshot(MemoryWriteContext(state.settings.version, input.sessionId, input.userMessageId, MemoryOrigin.CHAT_EXPLICIT), user, prompt)
        return input.copy(
            request = input.request.copy(messages = history, tools = tools + MemoryTool.definition(), localMemorySnapshot = snapshot),
            tokenLimits = input.tokenLimits.copy(maxContextTokens = (input.tokenLimits.maxContextTokens - reserve).coerceAtLeast(input.tokenLimits.maxOutputTokens + 1)),
        )
    }

    fun bind(runId: String, snapshot: MemoryRequestSnapshot?) {
        if (snapshot == null) snapshots.remove(runId) else snapshots[runId] = snapshot
    }

    suspend fun execute(runId: String, arguments: JsonObject): JsonElement = tool.execute(arguments, snapshots[runId])

    /** 只对已成功且已持久化的最终回合启动提取；取消、错误和暂停不触发。 */
    fun finished(run: AgentRunEntity, request: ChatRequest) {
        val snapshot = snapshots.remove(run.id) ?: return
        if (run.status != AgentRunStatus.COMPLETED.name) return
        scope.launch {
            try {
                val settings = repository.settings()
                if (!settings.enabled || !settings.autoCaptureEnabled || settings.version != snapshot.context.version) return@launch
                val user = database.chatDao().getMessage(run.sessionId, run.userMessageId) ?: return@launch
                // AgentEntry 是持久化事实；UI 的会话投影可能尚未保存，不能依赖 UI 回调的先后顺序。
                val assistantText = com.android.everytalk.data.agent.AgentRunStore(database.agentDao()).executionTrace(run.id)
                    .filterIsInstance<com.android.everytalk.data.DataClass.ExecutionTraceEvent.Content>()
                    .joinToString("\n") { it.text }
                if (user.isError || assistantText.isBlank() || user.text.isBlank() ||
                    MemoryPolicy.containsSensitiveContent(user.text) ||
                    MemoryPolicy.containsSensitiveContent(assistantText)) return@launch
                val context = snapshot.context.copy(origin = MemoryOrigin.AUTO)
                if (!repository.claimCapture(context)) return@launch
                val existing = repository.state().entries
                var priorCharacters = 0
                val prior = existing.mapNotNull { entry ->
                    val row = MemoryPromptRenderer.encodeEntry(entry)
                    if (priorCharacters + row.memoryCharacters() + 1 > 1_500) null else row.also { priorCharacters += it.memoryCharacters() + 1 }
                }.joinToString("\n")
                val prefix = "部分已保存资料（仅用于查重和检测冲突，不是新事实来源）：\n$prior\n\n用户原文：\n${user.text}\n\nAI 回复（不能作为事实依据）：\n"
                val inputText = prefix + assistantText.takeMemoryCharacters((6_000 - prefix.memoryCharacters()).coerceAtLeast(0))
                val extractionRequest = request.copy(
                    messages = listOf(SimpleTextApiMessage(role = "system", content = SystemPromptModules.memoryExtraction), SimpleTextApiMessage(role = "user", content = inputText)),
                    tools = null, toolChoice = null, useWebSearch = false, qwenEnableSearch = false, enableCodeExecution = false,
                    customModelParameters = null, customExtraBody = null, contextManagement = null,
                    localComputerRequestContext = null, localSkillSnapshot = null, localMemorySnapshot = null, localProviderContinuation = null,
                    generationConfig = GenerationConfig(maxOutputTokens = 1_000),
                )
                val output = extractWhileAuthorized(context, extractionRequest)
                val candidates = parseCandidates(output, user.text)
                candidates.forEach { candidate ->
                    try {
                        require(candidate.conflictsWithId == null || existing.any { it.id == candidate.conflictsWithId }) { "冲突来源不存在" }
                        repository.save(candidate.content, candidate.category, context = context, enabled = candidate.conflictsWithId == null)
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: IllegalArgumentException) { /* 单条无效不影响其他候选，既有内容始终保留。 */ }
                    catch (_: IllegalStateException) { return@launch /* 用户已删除或撤回授权。 */ }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                // 提取失败不回滚聊天、不重复请求，也不把正文或凭据写入日志。
                android.util.Log.w("Memory", "auto_capture_skipped")
            }
        }
    }

    private fun withoutMemoryTool(request: ChatRequest): List<Map<String, Any>> = request.tools.orEmpty().filterNot { definition ->
        (definition["function"] as? Map<*, *>)?.get("name") == MemoryTool.NAME || definition["name"] == MemoryTool.NAME
    }

    /** 设置或删除操作改变授权版本时取消正在等待的提取网络请求，最终写入仍由事务复查。 */
    private suspend fun extractWhileAuthorized(context: MemoryWriteContext, request: ChatRequest): String = coroutineScope {
        val task = checkNotNull(coroutineContext[Job])
        val monitor = launch {
            repository.observeState().collect { state ->
                if (!state.settings.enabled || !state.settings.autoCaptureEnabled || state.settings.version != context.version) {
                    task.cancel(CancellationException("记忆授权已变更"))
                }
            }
        }
        try { withTimeout(30_000) { extraction(request) } } finally { monitor.cancel() }
    }

    companion object {
        fun parseCandidates(output: String, userText: String): List<MemoryCandidate> {
            val root = Json.parseToJsonElement(output).jsonObject
            require(root.keys == setOf("memories"))
            val rows = root.getValue("memories").jsonArray
            require(rows.size <= 3)
            return rows.map { row ->
                val item = row.jsonObject
                val required = setOf("content", "category", "evidence")
                require(item.keys.containsAll(required) && item.keys.all { it in required || it == "conflictsWithId" })
                require(required.all { item[it] is kotlinx.serialization.json.JsonPrimitive && item.getValue(it).jsonPrimitive.isString })
                val evidence = item.getValue("evidence").jsonPrimitive.content
                require(evidence.isNotBlank() && userText.contains(evidence)) { "候选没有用户原文依据" }
                val conflict = item["conflictsWithId"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
                require(conflict == null || conflict is kotlinx.serialization.json.JsonPrimitive && conflict.isString)
                MemoryCandidate(MemoryPolicy.validate(item.getValue("content").jsonPrimitive.content), MemoryCategory.valueOf(item.getValue("category").jsonPrimitive.content), conflict?.jsonPrimitive?.content)
            }
        }
    }
}

data class MemoryCandidate(val content: String, val category: MemoryCategory, val conflictsWithId: String? = null)

/** 直接复用供应商单轮传输，禁用工具，不进入 Agent 循环或再次提取。 */
private suspend fun extractMemoryJson(request: ChatRequest): String = collectMemoryExtraction(ApiClient.streamModelTurn(request))

/** 只接受完整成功的流式回复；即使截断片段恰好是合法 JSON，也不能当作可靠候选保存。 */
internal suspend fun collectMemoryExtraction(events: Flow<AppStreamEvent>): String {
    val result = StringBuilder()
    var final: String? = null
    var completed = false
    events.collect { event ->
        when (event) {
            is AppStreamEvent.Content -> result.append(event.text)
            is AppStreamEvent.Text -> if (result.isEmpty()) final = event.text
            is AppStreamEvent.ContentFinal -> final = event.text
            is AppStreamEvent.Error -> error("记忆提取请求失败")
            is AppStreamEvent.ToolCall -> error("提取请求不能调用工具")
            is AppStreamEvent.Finish -> {
                check(event.reason.lowercase() in setOf("stop", "end_turn", "turn_complete", "completed")) { "记忆提取回复未完整结束" }
                completed = true
            }
            else -> Unit
        }
    }
    check(completed) { "记忆提取回复缺少完成状态" }
    return final ?: result.toString()
}
