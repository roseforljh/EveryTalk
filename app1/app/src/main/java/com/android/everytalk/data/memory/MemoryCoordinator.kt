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

/**
 * 应用级 Agent 记忆协调器；一次发送一份快照。
 * 写入不再按轮抽取零散“事实”，而是在发送时让 [MemoryConsolidator] 在后台整理闲置会话（参考 Codex memory v2）。
 */
class MemoryCoordinator(
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    generate: suspend (ChatRequest) -> MemoryModelReply = ::callMemoryModel,
) {
    private val repository = MemoryRepository(database)
    private val historySearch = ConversationHistorySearch(database)
    private val tool = MemoryTool(repository, historySearch)
    private val consolidator = MemoryConsolidator(database, generate)
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
        val overview = database.memoryDao().getDocument(com.android.everytalk.data.database.entities.MemoryDocumentEntity.OVERVIEW_ID)
        val context = MemoryWriteContext(state.settings.version, input.sessionId, input.userMessageId, MemoryOrigin.CHAT_EXPLICIT)
        val prompt = MemoryPromptRenderer.render(state.entries, user,
            minOf(MemoryPolicy.MAX_PROMPT_CHARACTERS, (input.tokenLimits.maxContextTokens - input.tokenLimits.maxOutputTokens) / 8),
            conversationId = input.sessionId, overview = overview) + recallPastConversations(user, context,
                maxCharacters = (input.tokenLimits.maxContextTokens - input.tokenLimits.maxOutputTokens) / 8)
        val reserve = if (prompt.isEmpty()) 0 else RequestTokenEstimator.estimate(
            listOf(SimpleTextApiMessage(role = "system", content = prompt)), tools = null,
        ).totalInputTokens.toInt()
        val snapshot = MemoryRequestSnapshot(context, user, prompt)
        // 记忆按发送时的原样固定在那条问题上，以后每轮原样重放：上一条问题不变，前缀缓存才接得上。只存本机。
        val settings = database.settingsDao()
        if (prompt.isNotEmpty()) settings.insertSetting(com.android.everytalk.data.database.entities.SystemSettingEntity("memory_prompt:${input.userMessageId}", prompt))
        val pinned = history.map { message ->
            if (message.id == input.userMessageId || message.role != "user" || message !is SimpleTextApiMessage && message !is PartsApiMessage) message
            else settings.getValue("memory_prompt:${message.id}")?.let { MemoryPromptRenderer.inject(listOf(message), it).single() } ?: message
        }
        // 后台整理其他闲置会话，不阻塞本次发送。
        scope.launch { consolidator.runOnce(input.request, input.sessionId, state.settings.version) }
        scope.launch {
            // 索引补建失败不影响聊天；搜索时会再补一次，仍失败则沿用逐条扫描的结果。
            try { historySearch.warmUp() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { android.util.Log.w("Memory", "history_index_skipped") }
        }
        return input.copy(
            request = input.request.copy(messages = pinned, tools = tools + MemoryTool.definition(), localMemorySnapshot = snapshot),
            tokenLimits = input.tokenLimits.copy(maxContextTokens = (input.tokenLimits.maxContextTokens - reserve).coerceAtLeast(input.tokenLimits.maxOutputTokens + 1)),
        )
    }

    /** 用户提到以前的聊天时先检索本机会话；超时或失败只跳过，不影响发送。 */
    private suspend fun recallPastConversations(user: String, context: MemoryWriteContext, maxCharacters: Int): String {
        if (!PastConversationRecall.triggered(user)) return ""
        val keywords = PastConversationRecall.keywords(user)
        if (keywords.isBlank()) return ""
        return try {
            kotlinx.coroutines.withTimeoutOrNull(RECALL_TIMEOUT_MILLIS) {
                val arguments = kotlinx.serialization.json.buildJsonObject {
                    put("action", kotlinx.serialization.json.JsonPrimitive("search_conversations"))
                    put("query", kotlinx.serialization.json.JsonPrimitive(keywords))
                }
                PastConversationRecall.render(keywords, historySearch.search(arguments, context), maxCharacters)
            }.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            android.util.Log.w("Memory", "auto_recall_skipped"); ""
        }
    }

    fun bind(runId: String, snapshot: MemoryRequestSnapshot?) {
        if (snapshot == null) snapshots.remove(runId) else snapshots[runId] = snapshot
    }

    suspend fun execute(runId: String, arguments: JsonObject): JsonElement = tool.execute(arguments, snapshots[runId])

    fun finished(run: AgentRunEntity) { snapshots.remove(run.id) }

    private fun withoutMemoryTool(request: ChatRequest): List<Map<String, Any>> = request.tools.orEmpty().filterNot { definition ->
        (definition["function"] as? Map<*, *>)?.get("name") == MemoryTool.NAME || definition["name"] == MemoryTool.NAME
    }
}

/** 记忆整理的一次模型回复和它消耗的 token（供应商没报用量时为 0）。 */
data class MemoryModelReply(val text: String, val tokens: Long = 0)

/** 直接复用供应商单轮传输，禁用工具，不进入 Agent 循环。 */
private suspend fun callMemoryModel(request: ChatRequest): MemoryModelReply {
    var tokens = 0L
    val text = collectMemoryExtraction(ApiClient.streamModelTurn(request)) { usage ->
        // 同一回复可能先报中间用量再报最终用量，取最大值，不重复累加。
        tokens = maxOf(tokens, usage.totalTokens ?: ((usage.inputTokens ?: 0) + (usage.outputTokens ?: 0)))
    }
    return MemoryModelReply(text, tokens)
}

/** 只接受完整成功的流式回复；即使截断片段恰好是合法 JSON，也不能当作可靠候选保存。 */
internal suspend fun collectMemoryExtraction(
    events: Flow<AppStreamEvent>,
    onUsage: (com.android.everytalk.data.network.TokenUsage) -> Unit = {},
): String {
    val result = StringBuilder()
    var final: String? = null
    var completed = false
    events.collect { event ->
        when (event) {
            is AppStreamEvent.Content -> result.append(event.text)
            is AppStreamEvent.Text -> if (result.isEmpty()) final = event.text
            is AppStreamEvent.ContentFinal -> final = event.text
            is AppStreamEvent.Usage -> onUsage(event.usage)
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

/** 自动回忆的检索上限；首次建索引较慢时宁可跳过，也不拖住发送。 */
private const val RECALL_TIMEOUT_MILLIS = 1_500L
