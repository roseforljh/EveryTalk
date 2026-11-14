package com.android.everytalk.data.agent

import androidx.room.withTransaction
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.executionFileChange
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.AgentRunSnapshotChunkEntity
import com.android.everytalk.data.database.entities.MessageDraftEntity
import com.android.everytalk.data.database.entities.SyncStatus
import com.android.everytalk.data.database.entities.SystemSettingEntity
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toMessage
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import com.android.everytalk.data.DataClass.WebSearchResult
import java.util.UUID

/** 云端快照直接投影既有 Message，所有引用先换成本机 ID，不复刻一套 UI 状态。 */
internal suspend fun CloudRunView.toMessage(base: Message, resolve: suspend (String) -> String): Message {
    val trace = snapshot.trace.map { part ->
        when (part.kind) {
            "content" -> ExecutionTraceEvent.Content(part.text, part.startedAt)
            "reasoning" -> ExecutionTraceEvent.Reasoning(part.text, part.startedAt)
            "user" -> {
                requireCloudUuid(part.messageUuid)
                ExecutionTraceEvent.UserMessageBoundary(resolve(part.messageUuid), part.startedAt)
            }
            "tool" -> {
                val completed = part.finishedAt != null && part.result != null
                val step = com.android.everytalk.statecontroller.executionStepForToolCall(
                    com.android.everytalk.data.network.AppStreamEvent.ToolCall(part.id, part.name,
                        part.arguments ?: kotlinx.serialization.json.JsonObject(emptyMap())),
                    reasoningBefore = "",
                ).copy(
                    completed = completed, started = true,
                    detail = part.arguments?.toString(),
                    isError = part.isError,
                    fileChange = if (part.isError) null else part.arguments?.let { executionFileChange(part.name, it) },
                    computerId = part.arguments?.get("computer_id")?.jsonPrimitive?.content,
                    resultPreview = part.result?.toString()?.take(4_000),
                    durationMs = part.finishedAt?.let { (it - part.startedAt).coerceAtLeast(0) },
                )
                ExecutionTraceEvent.Tool(step, part.startedAt, part.finishedAt)
            }
            else -> error("云端任务轨迹类型无效")
        }
    }
    val usage = snapshot.usage?.let {
        com.android.everytalk.data.network.TokenUsage(
            // 本地口径的输入包含缓存读写；Pi 的 input 只算未命中缓存的部分。
            inputTokens = it.input + it.cacheRead + it.cacheWrite, outputTokens = it.output,
            cachedInputTokens = it.cacheRead, cacheWriteTokens = it.cacheWrite, totalTokens = it.totalTokens,
            isFinal = terminal, source = when (it.api) {
                "openai-responses" -> com.android.everytalk.data.network.TokenUsageSource.OPENAI_RESPONSES
                "anthropic-messages" -> com.android.everytalk.data.network.TokenUsageSource.ANTHROPIC
                "google-generative-ai" -> com.android.everytalk.data.network.TokenUsageSource.GEMINI
                else -> com.android.everytalk.data.network.TokenUsageSource.OPENAI_CHAT
            },
        )
    }
    // 搜索来源与本机一致取自 web_search 的工具结果，按出现顺序编号。
    val sources = snapshot.trace.filter { it.kind == "tool" && it.name == "web_search" && !it.isError }
        .flatMap { part -> cloudSearchResults(part.result) }
        .plus(snapshot.sources.map { Triple(it.title, it.url, it.snippet) })
        .distinctBy { it.second }
        .mapIndexed { index, (title, href, snippet) -> WebSearchResult(index + 1, title, href, snippet) }
    return base.copy(
        modelName = snapshot.modelName ?: base.modelName,
        providerName = snapshot.providerName ?: base.providerName,
        tokenUsage = usage ?: base.tokenUsage,
        contextUsageSnapshot = (base.contextUsageSnapshot ?: snapshot.contextUsage?.copy(messageId = base.id))?.let { context ->
            usage?.let { context.copy(measuredInputTokens = it.inputTokens, measuredOutputTokens = it.outputTokens,
                measuredTotalTokens = it.totalTokens, measuredUsageSource = it.source) } ?: context
        },
        webSearchResults = sources.ifEmpty { base.webSearchResults },
        text = snapshot.text,
        reasoning = snapshot.reasoning.takeIf(String::isNotEmpty),
        parts = emptyList(),
        contentStarted = snapshot.text.isNotEmpty() || trace.isNotEmpty(),
        executionTrace = trace,
        executionSteps = trace.filterIsInstance<ExecutionTraceEvent.Tool>().map { it.step },
        executionStatus = if (status == "FAILED") snapshot.error else snapshot.executionStatus,
        executionFinishedAt = snapshot.finishedAt.takeIf { answered },
        isError = status == "FAILED",
        isIncomplete = terminal && status != "COMPLETED",
    )
}

/** 工具结果形如 {content:[{type:"text",text:"{\"results\":[...]}"}]}；解析失败按没有来源处理。 */
private fun cloudSearchResults(result: kotlinx.serialization.json.JsonElement?): List<Triple<String, String, String>> = runCatching {
    val text = (result as kotlinx.serialization.json.JsonObject).getValue("content").jsonArray
        .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
    cloudAgentJson.parseToJsonElement(text).jsonObject.getValue("results").jsonArray.mapNotNull { item ->
        val value = item.jsonObject
        val href = value["url"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        Triple(value["title"]?.jsonPrimitive?.content ?: href, href, value["snippet"]?.jsonPrimitive?.content.orEmpty())
    }
}.getOrDefault(emptyList())

/**
 * 云端任务在第一次 POST 前登记固定身份。快照分块复用已有表，避免大回复超出 CursorWindow；
 * 快照、消息和游标在一个事务提交。调用方只在返回非空后更新页面。
 */
internal class CloudAgentStore(private val db: AppDatabase) {
    suspend fun prepare(accountId: String, sessionId: String, userId: String, assistantId: String, configId: String?,
        beforePersist: suspend (AgentRunEntity) -> Unit = {},
    ): AgentRunEntity = db.withTransaction {
        requireCloudUuid(accountId)
        val dao = db.agentDao()
        dao.getRunByVisibleMessage(assistantId)?.let { existing ->
            check(existing.executionOwner == "CLOUD" && existing.cloudAccountId == accountId && existing.sessionId == sessionId) { "消息已有其他执行者" }
            return@withTransaction existing
        }
        val session = checkNotNull(db.chatDao().getSession(sessionId)) { "会话不存在" }
        check(!session.isImageGeneration) { "图像任务不使用文字执行器" }
        val user = checkNotNull(db.chatDao().getMessage(sessionId, userId)) { "用户消息尚未保存" }
        check(!user.deleted && user.sender == Sender.User && user.sessionId == sessionId) { "云端任务父问题无效" }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        AgentRunEntity(id, sessionId, userId, assistantId, configId, null, AgentRunStatus.CREATED.name,
            0, null, now, now, executionOwner = "CLOUD", cloudAccountId = accountId, cloudRunId = id,
            loopState = "SUBMITTING").also { beforePersist(it); dao.upsertRun(it) }
    }

    suspend fun read(runId: String): CloudRunView? = db.withTransaction {
        val dao = db.agentDao()
        val run = dao.getRun(runId)?.takeIf { it.executionOwner == "CLOUD" } ?: return@withTransaction null
        val raw = StringBuilder()
        var after = -1
        while (true) {
            val page = dao.getRunSnapshotChunkPage(run.id, after, 8)
            if (page.isEmpty()) break
            page.forEach { raw.append(it.payload) }
            after = page.last().chunkIndex
        }
        raw.takeIf { it.isNotEmpty() }?.let { cloudAgentJson.decodeFromString<CloudRunView>(it.toString()).validate() }
    }

    /** UUID 映射可能晚于事件到达。重新投影不推进游标，也不重写模型/工具事实。 */
    suspend fun project(accountId: String, incoming: CloudRunView): Message? = db.withTransaction {
        requireCloudUuid(accountId)
        val view = incoming.validate()
        val run = db.agentDao().getRun(view.id)?.takeIf {
            it.executionOwner == "CLOUD" && it.cloudAccountId == accountId && it.cloudRunId == view.id
        } ?: return@withTransaction null
        if (run.terminalReason == "SYNC_HISTORY_ONLY") return@withTransaction null
        val chat = db.chatDao()
        val session = chat.getSessionByUuid(view.sessionUuid)?.takeUnless { it.deleted || it.isImageGeneration }
            ?: return@withTransaction null
        check(session.id == run.sessionId) { "云端任务会话身份不匹配" }
        if (chat.getMessageByUuid(view.userMessageUuid)?.deleted == true ||
            chat.getMessageByUuid(view.assistantMessageUuid)?.deleted == true) return@withTransaction null
        val base = chat.getMessage(session.id, run.visibleAssistantMessageId)?.toMessage()
            ?: Message(id = run.visibleAssistantMessageId, text = "", sender = Sender.AI, timestamp = view.snapshot.startedAt)
        view.toMessage(base.copy(conversationId = session.id), chat::resolveLocalMessageReference)
    }

    suspend fun apply(accountId: String, incoming: CloudRunView): Message? = db.withTransaction {
        requireCloudUuid(accountId)
        val view = incoming.validate()
        val chat = db.chatDao()
        val dao = db.agentDao()
        val session = chat.getSessionByUuid(view.sessionUuid)?.takeUnless { it.deleted || it.isImageGeneration }
            ?: return@withTransaction null
        val previous = dao.getRun(view.id)
        if (previous != null) {
            if (previous.terminalReason == "SYNC_HISTORY_ONLY") return@withTransaction null
            check(previous.executionOwner == "CLOUD" && previous.cloudAccountId == accountId && previous.cloudRunId == view.id && previous.sessionId == session.id) { "云端任务归属不匹配" }
            val old = read(view.id)
            if (old != null) {
                check(old.sessionUuid == view.sessionUuid && old.assistantMessageUuid == view.assistantMessageUuid && old.userMessageUuid == view.userMessageUuid) { "云端任务消息身份变化" }
                if (view.cursor <= previous.cloudCursor || view.generation < previous.cloudGeneration || old.terminal) return@withTransaction null
            }
        }
        val user = chat.getMessageByUuid(view.userMessageUuid)
        if (user?.deleted == true) return@withTransaction null
        if (user != null) check(user.sessionId == session.id) { "云端用户消息归属不匹配" }
        val formal = chat.getMessageByUuid(view.assistantMessageUuid)
        if (formal?.deleted == true) return@withTransaction null
        if (formal != null) check(formal.sessionId == session.id) { "云端回复归属不匹配" }
        val localId = previous?.visibleAssistantMessageId ?: formal?.id ?: "cloud:${view.assistantMessageUuid}"
        val existing = chat.getMessage(session.id, localId)
        if (existing != null) check(existing.uuid == view.assistantMessageUuid) { "云端回复 UUID 不匹配" }
        // 同一个气泡不能被旧版本机 Run 与云端 Run 同时驱动。
        dao.getRunByVisibleMessage(localId)?.let { check(it.id == view.id) { "消息已有其他任务" } }
        val base = existing?.toMessage() ?: Message(id = localId, text = "", sender = Sender.AI, timestamp = view.snapshot.startedAt)
        val message = view.toMessage(base.copy(conversationId = session.id), chat::resolveLocalMessageReference)
        val entity = message.toEntity(session.id).copy(uuid = view.assistantMessageUuid,
            updatedAt = view.snapshot.finishedAt ?: view.snapshot.startedAt, syncStatus = SyncStatus.SYNCED, everSynced = true)
        if (view.terminal) {
            chat.writeMessages(listOf(entity))
            chat.deleteDrafts(session.id, listOf(localId))
            // 后端是唯一归档/正式消息写入者，客户端缓存不能被扫描当成新文件再上传。
            db.settingsDao().insertSetting(SystemSettingEntity("sync_resource_source:${entity.uuid}", "$accountId:${entity.storageFingerprint}"))
        } else {
            if (formal == null) chat.writeDrafts(listOf(MessageDraftEntity(entity.copy(syncStatus = SyncStatus.LOCAL_ONLY))))
        }
        val localStatus = when (view.status) {
            "COMPLETED", "FAILED", "CANCELLED" -> view.status
            "WAITING_APPROVAL", "WAITING_USER", "WAITING_DEVICE", "PAUSED" -> AgentRunStatus.WAITING_APPROVAL.name
            else -> AgentRunStatus.WAITING_MODEL.name
        }
        val run = (previous ?: AgentRunEntity(view.id, session.id, chat.resolveLocalMessageReference(view.userMessageUuid),
            localId, null, null, localStatus, 0, null, view.snapshot.startedAt, view.snapshot.startedAt)).copy(
            status = localStatus, updatedAt = view.snapshot.finishedAt ?: System.currentTimeMillis(),
            executionOwner = "CLOUD", cloudAccountId = accountId, cloudRunId = view.id,
            cloudCursor = view.cursor, cloudGeneration = view.generation, loopState = view.status,
        )
        dao.upsertRun(run)
        dao.deleteRunSnapshotChunks(run.id)
        dao.upsertRunSnapshotChunks(cloudAgentJson.encodeToString(CloudRunView.serializer(), view).chunked(128 * 1024)
            .mapIndexed { index, chunk -> AgentRunSnapshotChunkEntity(run.id, index, chunk) })
        message
    }
}
