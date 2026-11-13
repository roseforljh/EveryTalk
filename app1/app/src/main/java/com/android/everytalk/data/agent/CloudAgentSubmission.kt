package com.android.everytalk.data.agent

import android.content.Context
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.computer.refreshComputerExecutionPolicy
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.memory.MemoryCoordinator
import com.android.everytalk.data.memory.MemoryPromptRenderer
import com.android.everytalk.data.skill.SkillRepository
import com.android.everytalk.models.SelectedMediaItem
import kotlinx.serialization.json.JsonObject
import java.util.TimeZone
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.SyncStatus
import com.android.everytalk.data.sync.*
import kotlinx.coroutines.sync.withLock
import com.android.everytalk.data.database.entities.toMessage

/**
 * 新任务先经现有同步引擎核对 epoch 并上传父会话、用户问题，再取得提交代次。
 * 不另开同步器、不猜测初始 epoch，也不把 LOCAL_ONLY/被拒绝的问题当成已上传。
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
 * 沿用权威账本、已有压缩检查点和当轮权限提示生成云输入，不在手机发起模型请求。
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
    beforePersist: suspend (AgentRunEntity, JsonObject) -> Unit,
    submit: suspend (AgentRunEntity, JsonObject) -> Unit,
): AgentRunEntity {
    val (frozen, request) = try {
        freezeCloudAgentInput(context, database, runStore, skills, memory, input, epochs, sessionState, permitted)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
    } catch (error: Exception) { throw CloudAgentIneligible(error.message.orEmpty(), error) }
    fun withRunId(run: AgentRunEntity) = JsonObject(frozen + ("id" to kotlinx.serialization.json.JsonPrimitive(run.id)))
    val run = CloudAgentStore(database).prepare(accountId, input.sessionId, input.userMessageId, input.visibleAssistantMessageId,
        request.contextManagement?.configId) { pending ->
        check(permitted()) { "云端任务账号授权已变更" }
        beforePersist(pending, withRunId(pending))
    }
    submit(run, withRunId(run))
    return run
}

/** 分支对话的会话 ID 前缀；分支依附父页面显示，不交给云端执行。 */
internal const val FORK_CONVERSATION_PREFIX = "new_chat_fork_"

/**
 * 云端任务仍在运行但没收下这条方向调整：调用方保留队列，不能当作“没有任务”另开一轮。
 * [unknown] 表示命令可能已被服务器接收（响应全部丢失）：不能自动当新消息发出，只能由用户重试，
 * 重试时先对账云端轨迹。
 */
internal class CloudSteerNotAccepted(message: String, val unknown: Boolean) : Exception(message)

/** 云端还接不了的请求：写下任何云端记录之前抛出，调用方改走本机，不会两边都跑。 */
internal class CloudAgentIneligible(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 只读本机数据生成冻结输入，不写任何持久记录。 */
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
): Pair<JsonObject, com.android.everytalk.data.DataClass.ChatRequest> {
    check(permitted()) { "云端任务账号授权已变更" }
    val calibrated = runStore.calibrateRequest(input.sessionId, input.request, input.tokenLimits)
    val preparedInput = memory.prepare(input.copy(request = calibrated))
    runStore.restoreLegacyCompaction(input.sessionId, preparedInput.request)
    val transcript = MemoryPromptRenderer.forHistory(runStore.expandTranscript(input.sessionId, preparedInput.request.messages))
    val request = refreshComputerExecutionPolicy(preparedInput.request.copy(messages = transcript), sessionState)
    val checkpoints = runStore.compactions(input.sessionId)
    val prepared = AgentContextManager().prepare(
        requestId = "cloud-submission", request = request, limits = preparedInput.tokenLimits,
        checkpoint = checkpoints.firstOrNull(), checkpointFallbacks = checkpoints,
    )
    // 后端还不会压缩：需要压缩的请求留在本机跑，本机压完的检查点下一轮云端照样能用。
    check(prepared.compactionPlan == null) { "上下文需要先压缩" }
    val modelRequest = request.copy(messages = MemoryPromptRenderer.inject(prepared.messages,
        request.localMemorySnapshot?.prompt.orEmpty()))
    val chat = database.chatDao()
    val attachments = chat.getMessagesForSession(input.sessionId).flatMap { it.attachments }
        .filterIsInstance<SelectedMediaItem.GenericFile>().distinctBy { it.id }
    val tools = cloudAgentTools(context, modelRequest, attachments, skills)
    check(permitted()) { "云端任务账号授权已变更" }
    val session = checkNotNull(chat.getSession(input.sessionId)) { "会话不存在" }
    val user = checkNotNull(chat.getMessage(input.sessionId, input.userMessageId)) { "用户问题尚未保存" }
    val assistant = checkNotNull(chat.getMessage(input.sessionId, input.visibleAssistantMessageId)) { "回复身份尚未保存" }
    check(!user.deleted && user.sender == Sender.User && !assistant.deleted && assistant.sender == Sender.AI) { "云端任务消息身份无效" }
    // 地址、消息协议或附件转换失败不能留下没有冻结输入的 SUBMITTING 记录。
    val frozen = cloudAgentInput(modelRequest, java.util.UUID.randomUUID().toString(), session.uuid, user.uuid, assistant.uuid,
        epochs.first, epochs.second, assistant.timestamp, TimeZone.getDefault().id, tools,
        contextUsage = assistant.toMessage().contextUsageSnapshot)
    check(permitted()) { "云端任务账号授权已变更" }
    return frozen to request
}
