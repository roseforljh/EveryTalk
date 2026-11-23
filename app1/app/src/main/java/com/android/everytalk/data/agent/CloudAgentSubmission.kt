package com.android.everytalk.data.agent

import android.content.Context
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.computer.refreshComputerExecutionPolicy
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.memory.MemoryCoordinator
import com.android.everytalk.data.memory.MemoryPromptRenderer
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.models.SelectedMediaItem
import kotlinx.serialization.json.*
import java.util.TimeZone
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.SyncStatus
import com.android.everytalk.data.sync.*
import kotlinx.coroutines.sync.withLock
import com.android.everytalk.data.database.entities.toMessage
import com.android.everytalk.data.database.entities.toEntity

/**
 * 新任务复用已知 epoch 直接提交；只有代次未知时才经现有同步引擎完成首次核对。
 * 不另开同步器、不猜测初始 epoch，也不把 LOCAL_ONLY/被拒绝的问题当成可同步记录。
 * runOnce 自己持有 SyncRuntimeLock；调用方不能在外层持锁后再次进入引擎。
 */
internal suspend fun synchronizeCloudAgentParents(
    database: AppDatabase,
    api: SyncApi,
    store: SyncStore,
    state: SyncStateStore,
    sessionId: String,
    userMessageId: String,
    permitted: suspend () -> Boolean,
    beforePull: suspend () -> Unit = {},
    afterPull: suspend () -> Unit = {},
    onSecretsEpochChanged: suspend () -> Unit = {},
): Pair<Long, Long> {
    // 父会话和问题已有固定 UUID，后端不再要求它们先同步上去：不等整账号同步（附件积压时要等好几分钟），
    // 代次已知就直接提交，父记录由后台同步补上，后端收尾写回复时再核对。只有从未同步过、代次未知时才跑一轮同步。
    run {
        check(permitted() && state.isEnabled() && state.epochDecision() == null) { "云端任务账号授权已变更" }
        val session = checkNotNull(database.chatDao().getStoredSession(sessionId)) { "会话不存在" }
        val user = checkNotNull(database.chatDao().getMessage(sessionId, userMessageId)) { "用户问题尚未保存" }
        check(!session.deleted && !session.isImageGeneration && !user.deleted && user.sender == Sender.User) { "云端任务父记录无效" }
        check(session.syncStatus != SyncStatus.LOCAL_ONLY && user.syncStatus != SyncStatus.LOCAL_ONLY) { "会话或问题未获同步确认，请检查同步提示后重试" }
        val epoch = state.readCursor().epoch
        val secretsEpoch = state.readSecretsEpoch()
        if (epoch >= 0 && secretsEpoch >= 0) return epoch to secretsEpoch
    }
    val engine = SyncEngine(api, store, state, canSync = permitted, beforePull = beforePull,
        beforePullPage = beforePull, afterPull = afterPull, onSecretsEpochChanged = onSecretsEpochChanged)
    while (true) {
        check(permitted()) { "云端任务账号授权已变更" }
        val result = engine.runOnce()
        check(result == SyncRunResult.Completed || result == SyncRunResult.MoreWork) { "云端同步尚未确认，请完成账号数据选择后重试" }
        val ready = SyncRuntimeLock.mutex.withLock {
            check(permitted() && state.isEnabled() && state.epochDecision() == null) { "云端任务账号授权已变更" }
            val session = checkNotNull(database.chatDao().getStoredSession(sessionId)) { "会话不存在" }
            val user = checkNotNull(database.chatDao().getMessage(sessionId, userMessageId)) { "用户问题尚未保存" }
            check(!session.deleted && !session.isImageGeneration && !user.deleted && user.sender == Sender.User) { "云端任务父记录无效" }
            check(session.syncStatus != SyncStatus.LOCAL_ONLY && user.syncStatus != SyncStatus.LOCAL_ONLY) { "会话或问题未获同步确认，请检查同步提示后重试" }
            if (session.syncStatus != SyncStatus.SYNCED || user.syncStatus != SyncStatus.SYNCED) null
            else {
                val epoch = state.readCursor().epoch
                val secretsEpoch = state.readSecretsEpoch()
                check(epoch >= 0 && secretsEpoch >= 0) { "云端数据代次尚未确认" }
                epoch to secretsEpoch
            }
        }
        if (ready != null) return ready
        check(result == SyncRunResult.MoreWork) { "会话或问题尚未同步，任务未提交" }
    }
}

/**
 * 定向封存本轮必需的原始记录：会话、本会话待上云的原消息（含本轮完整问题）及其附件文件。
 * 服务器在创建任务的同一事务里写入它们，POST 成功即代表账号里已有完整原记录，原手机随即卸载也能在别处恢复；
 * 只上传这一会话的材料，不等整账号同步积压。
 */
internal suspend fun buildCloudAgentSubmission(context: Context, database: AppDatabase, api: SyncApi, store: RoomSyncStore,
    accountId: String, sessionId: String, userMessageId: String): JsonObject {
    val chat = database.chatDao()
    val user = checkNotNull(chat.getMessage(sessionId, userMessageId)) { "用户问题尚未保存" }
    // ponytail: 只带最近 500 条待上云消息（请求体与后端 2000 条上限内）；更早的积压留给常规同步，不影响本轮执行与恢复。
    val candidates = chat.getSubmissionMessageUuids(sessionId, userMessageId).takeLast(500)
        .let { if (user.uuid in it) it else it + user.uuid }
    // 定向上传与清理互斥，但不等待无关会话的后台同步。
    val (ready, attachmentIds) = SyncRuntimeLock.submissionFiles.withLock {
        SyncResourceSynchronizer(context, database, api, accountId).use { it.prepareSubmission(candidates, user.uuid) }
    }
    val changes = store.submissionChanges(sessionId, candidates.filter { it in ready }, attachmentIds, user.uuid)
    val files = attachmentIds.mapNotNull { database.syncAttachmentDao().get(it) }
        .mapNotNull { row -> row.fileId?.let { Triple(it, row.size, row.sha256) } }.distinctBy { it.first }
    return buildJsonObject {
        put("version", 1)
        put("changes", JsonArray(changes.map { syncJson.encodeToJsonElement(PushChange.serializer(), it) }))
        put("files", JsonArray(files.map { (id, size, sha256) -> buildJsonObject { put("id", id); put("size", size); put("sha256", sha256) } }))
    }
}

/**
 * 沿用权威账本、已有压缩检查点和当轮权限提示生成云输入，不在手机发起模型请求：
 * 超阈值或超窗的上下文由服务器在第一轮模型请求前用同一套摘要规则压缩，手机消失也不影响。
 * 能力没有云绑定时明确失败，不能静默删工具或退回本机执行。
 */
internal suspend fun prepareCloudAgentSubmission(
    context: Context,
    database: AppDatabase,
    runStore: AgentRunStore,
    skills: SkillRepository,
    memory: MemoryCoordinator,
    input: AgentLoopRequest,
    accountId: String,
    epochs: Pair<Long, Long>,
    sessionState: String?,
    permitted: suspend () -> Boolean,
    submission: JsonObject? = null,
    /** 设置级 MCP 工具：模型申请开启 MCP 时在提交时预冻结，批准后由服务器直接声明。 */
    mcpEnableRequest: com.android.everytalk.data.DataClass.ChatRequest? = null,
    /** 服务器声明的能力；缺项时退回旧版设备执行。 */
    serverFeatures: Set<String> = ALL_FEATURES,
    beforePersist: suspend (AgentRunEntity, JsonObject) -> Unit,
    submit: suspend (AgentRunEntity, JsonObject, com.android.everytalk.data.DataClass.ChatRequest) -> Unit,
): AgentRunEntity {
    val (frozen, request) = try {
        freezeCloudAgentInput(context, database, runStore, skills, memory, input, epochs, sessionState, permitted, mcpEnableRequest, serverFeatures)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
    } catch (error: Exception) { throw CloudAgentIneligible(error.message.orEmpty(), error) }
    // 清单与输入一起冻结：重试 POST 的字节完全相同，服务器按同一请求哈希幂等。
    fun withRunId(run: AgentRunEntity) = JsonObject(frozen + ("id" to JsonPrimitive(run.id)) +
        listOfNotNull(submission?.let { "submission" to it }))
    val run = CloudAgentStore(database).prepare(accountId, input.sessionId, input.userMessageId, input.visibleAssistantMessageId,
        request.contextManagement?.configId,
        request.localComputerRequestContext?.selectedTargets.orEmpty().map { AgentTaskTarget(it.computerId, it.displayName) }) { pending ->
        check(permitted()) { "云端任务账号授权已变更" }
        beforePersist(pending, withRunId(pending))
    }
    submit(run, withRunId(run), request)
    return run
}

/** 分支对话的会话 ID 前缀；分支的云端快照写进分支消息列表显示。 */
internal const val FORK_CONVERSATION_PREFIX = "new_chat_fork_"

/**
 * 云端任务仍在运行但没收下这条方向调整：调用方保留队列，不能当作“没有任务”另开一轮。
 * [unknown] 表示命令可能已被服务器接收（响应全部丢失）：不能自动当新消息发出，只能由用户重试，
 * 重试时先对账云端轨迹。
 */
internal class CloudSteerNotAccepted(message: String, val unknown: Boolean) : Exception(message)

/** 云端还接不了的请求：写下任何云端记录之前抛出，调用方改走本机，不会两边都跑。 */
/** 云端任务提交前失败：写下云端记录之前抛出，原因直接报给用户。 */
internal class CloudAgentIneligible(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 读本机数据生成冻结输入；需要压缩时先压好并保存检查点，此外不写任何持久记录。 */
private suspend fun freezeCloudAgentInput(
    context: Context,
    database: AppDatabase,
    runStore: AgentRunStore,
    skills: SkillRepository,
    memory: MemoryCoordinator,
    input: AgentLoopRequest,
    epochs: Pair<Long, Long>,
    sessionState: String?,
    permitted: suspend () -> Boolean,
    /** 设置级 MCP 工具：提交时一并冻结，批准后由服务器直接声明。 */
    mcpEnableRequest: com.android.everytalk.data.DataClass.ChatRequest? = null,
    serverFeatures: Set<String> = ALL_FEATURES,
): Pair<JsonObject, com.android.everytalk.data.DataClass.ChatRequest> {
    check(permitted()) { "云端任务账号授权已变更" }
    val calibrated = runStore.calibrateRequest(input.sessionId, input.request, input.tokenLimits)
    val preparedInput = memory.prepare(input.copy(request = calibrated), background = false)
    runStore.restoreLegacyCompaction(input.sessionId, preparedInput.request)
    val transcript = MemoryPromptRenderer.forHistory(runStore.expandTranscript(input.sessionId, preparedInput.request.messages))
    val request = refreshComputerExecutionPolicy(preparedInput.request.copy(messages = transcript), sessionState)
    // 新云任务尚未加载延迟工具：预算与后端第一轮声明一致，不能把全部授权 schema 都算进输入。
    // 原 request 仍冻结完整绑定供 tool_search 按需加载。
    val budgetRequest = request.copy(tools = McpToolDiscovery.project(
        AgentToolResultReadTool.withTool(request.tools), emptyList(),
    ))
    // 只套用已有的合法摘要检查点；仍超阈值的部分由服务器在首轮模型请求前压缩，手机不再先跑摘要模型。
    val prepared = runStore.compactions(input.sessionId).let { checkpoints ->
        AgentContextManager().prepare(
            requestId = "cloud-submission", request = budgetRequest, limits = preparedInput.tokenLimits,
            checkpoint = checkpoints.firstOrNull(), checkpointFallbacks = checkpoints,
        )
    }
    val modelRequest = request.copy(messages = MemoryPromptRenderer.inject(prepared.messages,
        request.localMemorySnapshot?.prompt.orEmpty()))
    val chat = database.chatDao()
    val attachments = chat.getMessagesForSession(input.sessionId).flatMap { it.attachments }
        .filterIsInstance<SelectedMediaItem.GenericFile>().distinctBy { it.id }
    val tools = cloudAgentTools(context, modelRequest, attachments, skills, mcpEnableRequest, serverFeatures)
    check(permitted()) { "云端任务账号授权已变更" }
    val session = checkNotNull(chat.getSession(input.sessionId)) { "会话不存在" }
    val user = checkNotNull(chat.getMessage(input.sessionId, input.userMessageId)) { "用户问题尚未保存" }
    // 回复气泡只在流式输出时才落盘，云端任务没有本机流式输出：库里还没有就先写一条空草稿，任务和定稿都用它的身份。
    val assistant = chat.getMessage(input.sessionId, input.visibleAssistantMessageId) ?: run {
        val placeholder = com.android.everytalk.data.DataClass.Message(id = input.visibleAssistantMessageId, text = "",
            sender = Sender.AI, timestamp = System.currentTimeMillis()).toEntity(input.sessionId)
        chat.writeDrafts(listOf(com.android.everytalk.data.database.entities.MessageDraftEntity(placeholder.copy(syncStatus = SyncStatus.LOCAL_ONLY))))
        checkNotNull(chat.getMessage(input.sessionId, input.visibleAssistantMessageId)) { "回复身份尚未保存" }
    }
    check(!user.deleted && user.sender == Sender.User && !assistant.deleted && assistant.sender == Sender.AI) { "云端任务消息身份无效" }
    // 地址、消息协议或附件转换失败不能留下没有冻结输入的 SUBMITTING 记录。
    val checkpoint = runStore.compactions(input.sessionId).firstOrNull { it.id == prepared.snapshot.compactionId }
    val coveredIds = checkpoint?.let { saved ->
        Json.parseToJsonElement(saved.retainedTailJson).jsonObject["source_prefix_ids"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
    }.orEmpty()
    val identities = chat.getMessageStorageStates(input.sessionId).associate { it.id to "cloud:${it.uuid}" }
    val workspaces = cloudAgentWorkspaces(context, modelRequest)
    val frozen = cloudAgentInput(modelRequest, java.util.UUID.randomUUID().toString(), session.uuid, user.uuid, assistant.uuid,
        epochs.first, epochs.second, assistant.timestamp, TimeZone.getDefault().id, tools,
        contextUsage = assistant.toMessage().contextUsageSnapshot, compactionSourceIds = coveredIds, messageIdReferences = identities,
        previousSummary = checkpoint?.summary).let { if (workspaces == null) it else JsonObject(it + ("workspaces" to workspaces)) }
    check(permitted()) { "云端任务账号授权已变更" }
    return frozen to request
}
