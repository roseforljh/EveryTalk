package com.android.everytalk.data.computer

import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.ComputerExecutionEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** Runtime 查询的结果，不把网络失败误判为 VPS 命令失败。 */
sealed interface ComputerRemoteExecutionQuery {
    data class State(val payload: String) : ComputerRemoteExecutionQuery

    /** 远端明确返回协议冲突或损坏，不能按网络暂时不可用处理。 */
    data class Invalid(
        val message: String,
        val code: String = ComputerErrorCodes.EXECUTION_STATE_INVALID,
    ) : ComputerRemoteExecutionQuery

    /** Helper 已确认目标目录不存在。 */
    object Missing : ComputerRemoteExecutionQuery

    /** SSH/网络暂时不可用，本轮不修改远端状态。 */
    data class Unavailable(
        val message: String? = null,
        /** 只有网络或 SSH Transport 故障才能显示“SSH 连接断开”。 */
        val connectionFailure: Boolean = false,
    ) : ComputerRemoteExecutionQuery
}

/**
 * Runtime 查询适配层。
 *
 * ComputerRuntimeEnvelope 负责具体 SSH Channel、Helper 命令和日志读取；它只需把固定状态
 * 响应接入这里，协调器即可复用严格解析、幂等更新和恢复查询，不需要把 AgentLoop 绑到 SSH 细节。
 */
fun interface ComputerRemoteExecutionGateway {
    suspend fun queryState(execution: ComputerExecutionEntity): ComputerRemoteExecutionQuery
}

enum class ComputerExecutionReconciliationOutcome {
    UPDATED,
    STILL_UNAVAILABLE,
    MISSING,
    INVALID,
}

data class ComputerExecutionReconciliation(
    val outcome: ComputerExecutionReconciliationOutcome,
    val execution: ComputerExecutionEntity?,
    val state: ParsedRemoteExecutionState? = null,
    val message: String? = null,
    val connectionFailure: Boolean = false,
)

private val ACTIVE_LOCAL_EXECUTION_STATUSES = setOf("QUEUED", "STARTING", "RUNNING")
private val RETRYABLE_REMOTE_ERROR_CODES = setOf(
    ComputerErrorCodes.EXECUTION_CANCEL_FAILED,
    ComputerErrorCodes.EXECUTION_CANCEL_REQUESTED,
    ComputerErrorCodes.EXECUTION_RESULT_UNAVAILABLE,
    ComputerErrorCodes.EXECUTION_UNKNOWN,
)

/** 只有本地仍在等待，或后台句柄仍交给 VPS 托管时，才需要继续查询远端状态。 */
internal fun ComputerExecutionEntity.shouldReconcileRemote(): Boolean {
    val remoteActive = remoteStatus == ComputerRemoteStatus.STARTING.name ||
        remoteStatus == ComputerRemoteStatus.RUNNING.name
    val retryableUnknown = remoteStatus == ComputerRemoteStatus.UNKNOWN.name &&
        errorCode in RETRYABLE_REMOTE_ERROR_CODES
    if (remoteActive) {
        // 本地停止按钮可能先把 Tool 标为 CANCELLED，远端取消请求随后才完成；
        // 这段窗口仍必须持续对账，避免 App 被杀后留下无人管理的 VPS 进程。
        return toolName == ComputerToolNames.EXEC && (
            status in ACTIVE_LOCAL_EXECUTION_STATUSES ||
                status == ComputerExecutionStatus.CANCELLED.name ||
                (completionMode == "RETURN_HANDLE" && status == ComputerExecutionStatus.SUCCEEDED.name) ||
                (status == ComputerExecutionStatus.UNKNOWN.name && errorCode in RETRYABLE_REMOTE_ERROR_CODES)
            )
    }
    return toolName == ComputerToolNames.EXEC &&
        ((remoteStatus == null && status in ACTIVE_LOCAL_EXECUTION_STATUSES) ||
            (remoteStatus == null && status == ComputerExecutionStatus.CANCELLED.name &&
                errorCode == ComputerErrorCodes.EXECUTION_CANCEL_REQUESTED) ||
            retryableUnknown)
}

/**
 * 将 VPS 的状态事实对账回 Room。
 *
 * 对账只更新 ComputerExecution，不创建 AgentEntry，也不触发模型请求。相同 Execution 使用
 * 独立 Mutex 串行化，防止启动确认、轮询和恢复扫描互相覆盖远端引用。
 */
class ComputerExecutionReconciler(
    private val dao: ComputerDao,
    private val gateway: ComputerRemoteExecutionGateway,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val executionLocks = ConcurrentHashMap<String, Mutex>()

    /** 根据 ID 查询并对账，执行不存在时返回 null。 */
    suspend fun reconcile(executionId: String): ComputerExecutionReconciliation? {
        val execution = dao.getExecutionById(executionId) ?: return null
        return reconcile(execution)
    }

    /** 对账单条执行，调用方可以把结果交给 AgentRun 恢复逻辑。 */
    suspend fun reconcile(execution: ComputerExecutionEntity): ComputerExecutionReconciliation {
        val lock = executionLocks.getOrPut(execution.id) { Mutex() }
        return lock.withLock {
            // 启动确认、轮询和恢复扫描可能同时持有旧快照；锁内重新取一次，避免旧本地状态覆盖新观察结果。
            reconcileLocked(dao.getExecutionById(execution.id) ?: execution)
        }
    }

    /** 查询全部活动执行；网络失败只影响对应条目，其余 VPS 继续对账。 */
    suspend fun reconcileActive(): List<ComputerExecutionReconciliation> =
        dao.getActiveRemoteExecutions().mapNotNull { execution -> reconcile(execution) }

    /** 恢复 AgentRun 时只查询仍等待结果的前台执行，后台句柄按会话进入时再刷新。 */
    suspend fun reconcileForegroundActive(): List<ComputerExecutionReconciliation> =
        dao.getActiveForegroundRemoteExecutions().mapNotNull { execution -> reconcile(execution) }

    /** 只恢复指定会话的前台任务，避免启动时唤醒其他会话的历史 VPS。 */
    suspend fun reconcileForegroundActiveForConversations(
        conversationIds: Set<String>,
    ): List<ComputerExecutionReconciliation> =
        if (conversationIds.isEmpty()) {
            emptyList()
        } else {
            dao.getActiveForegroundRemoteExecutionsForConversations(conversationIds.toList())
                .mapNotNull { execution -> reconcile(execution) }
        }

    suspend fun reconcileActiveForComputer(computerId: String): List<ComputerExecutionReconciliation> =
        dao.getActiveRemoteExecutionsForComputer(computerId).mapNotNull { execution -> reconcile(execution) }

    suspend fun reconcileActiveForWorkspace(workspaceId: String): List<ComputerExecutionReconciliation> =
        dao.getRemoteExecutionsForWorkspace(workspaceId)
            .filter(ComputerExecutionEntity::shouldReconcileRemote)
            .mapNotNull { execution -> reconcile(execution) }

    private suspend fun reconcileLocked(execution: ComputerExecutionEntity): ComputerExecutionReconciliation {
        val query = try {
            gateway.queryState(execution)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // 网络异常不等于远端失败，保留原状态等待下次恢复。
            return ComputerExecutionReconciliation(
                outcome = ComputerExecutionReconciliationOutcome.STILL_UNAVAILABLE,
                execution = execution,
                message = error.message,
                connectionFailure = isComputerConnectionFailure(error) ||
                    (error is ComputerException && (
                        error.code == ComputerErrorCodes.SSH_TIMEOUT ||
                            error.code == ComputerErrorCodes.HOST_RESOLUTION_FAILED
                        )),
            )
        }

        when (query) {
            ComputerRemoteExecutionQuery.Missing -> {
                val updated = updateMissing(execution)
                return ComputerExecutionReconciliation(
                    outcome = ComputerExecutionReconciliationOutcome.MISSING,
                    execution = updated,
                    message = "远端没有找到受管 Execution",
                )
            }

            is ComputerRemoteExecutionQuery.Unavailable -> {
                return ComputerExecutionReconciliation(
                    outcome = ComputerExecutionReconciliationOutcome.STILL_UNAVAILABLE,
                    execution = execution,
                    message = query.message,
                    connectionFailure = query.connectionFailure,
                )
            }

            is ComputerRemoteExecutionQuery.Invalid -> {
                val observedAt = nowMillis()
                val backgroundCompleted = execution.completionMode ==
                    ComputerExecutionCompletionMode.RETURN_HANDLE.name &&
                    execution.status in TOOL_TERMINAL_STATUSES
                // request_hash 冲突代表同一个 Execution ID 被另一组参数复用。
                // 这不是“状态未知”，不能进入 UNKNOWN 重试卡，否则用户可能在远端
                // 仍有另一条命令时再次发起副作用。
                val localStatus = when {
                    backgroundCompleted -> null
                    query.code == ComputerErrorCodes.EXECUTION_REQUEST_HASH_CONFLICT ->
                        ComputerExecutionStatus.FAILED.name
                    else -> ComputerExecutionStatus.UNKNOWN.name
                }
                dao.markRemoteExecutionUnknown(
                    executionId = execution.id,
                    errorCode = query.code,
                    observedAt = observedAt,
                    localStatus = localStatus,
                    finishedAt = if (backgroundCompleted) null else observedAt,
                )
                return ComputerExecutionReconciliation(
                    outcome = ComputerExecutionReconciliationOutcome.INVALID,
                    execution = dao.getExecutionById(execution.id),
                    message = query.message,
                )
            }

            is ComputerRemoteExecutionQuery.State -> Unit
        }

        val statePayload = query.payload
        val parsed = try {
            ComputerRemoteExecutionParser.parseState(
                payload = statePayload,
                expectedExecutionId = execution.id,
                expectedProcessId = execution.remoteProcessId,
                expectedRequestHash = execution.requestHash,
                expectedTarget = execution.target?.let(::parseTargetOrNull),
            )
        } catch (error: ComputerRemoteExecutionParseException) {
            val observedAt = nowMillis()
            val backgroundCompleted = execution.completionMode ==
                ComputerExecutionCompletionMode.RETURN_HANDLE.name &&
                execution.status in TOOL_TERMINAL_STATUSES
            val localStatus = when {
                backgroundCompleted -> null
                error.code == ComputerErrorCodes.EXECUTION_REQUEST_HASH_CONFLICT ->
                    ComputerExecutionStatus.FAILED.name
                else -> ComputerExecutionStatus.UNKNOWN.name
            }
            dao.markRemoteExecutionUnknown(
                executionId = execution.id,
                errorCode = error.code,
                observedAt = observedAt,
                localStatus = localStatus,
                finishedAt = if (backgroundCompleted) null else observedAt,
            )
            return ComputerExecutionReconciliation(
                outcome = ComputerExecutionReconciliationOutcome.INVALID,
                execution = dao.getExecutionById(execution.id),
                message = error.message,
            )
        }

        val observedAt = nowMillis()
        val localStatus = localToolStatus(execution, parsed.status)
        val terminal = parsed.status in TERMINAL_REMOTE_STATUSES
        val errorCode = when (parsed.status) {
            ComputerRemoteStatus.UNKNOWN,
            ComputerRemoteStatus.STOPPED,
            ComputerRemoteStatus.MISSING,
             -> ComputerErrorCodes.EXECUTION_UNKNOWN
            else -> null
        }
        dao.updateRemoteExecutionObservation(
            executionId = execution.id,
            target = parsed.target.name,
            remoteProcessId = parsed.processId,
            remoteStatus = parsed.status.name,
            remoteExitCode = parsed.exitCode,
            observedAt = observedAt,
            localStatus = localStatus,
            // 本地取消竞态时 localStatus 会保持 CANCELLED，仍要在 VPS 终态到达后
            // 写入 finishedAt，避免这条执行永远看起来像“取消中”。
            finishedAt = if (terminal) observedAt else null,
            localExitCode = if (terminal) parsed.exitCode else null,
            errorCode = errorCode,
        )
        return ComputerExecutionReconciliation(
            outcome = ComputerExecutionReconciliationOutcome.UPDATED,
            execution = dao.getExecutionById(execution.id),
            state = parsed,
        )
    }

    private suspend fun updateMissing(execution: ComputerExecutionEntity): ComputerExecutionEntity? {
        val observedAt = nowMillis()
        dao.updateRemoteExecutionObservation(
            executionId = execution.id,
            target = execution.target,
            remoteProcessId = execution.remoteProcessId,
            remoteStatus = ComputerRemoteStatus.MISSING.name,
            remoteExitCode = null,
            observedAt = observedAt,
            localStatus = localToolStatus(execution, ComputerRemoteStatus.MISSING),
            finishedAt = observedAt,
            localExitCode = null,
            errorCode = ComputerErrorCodes.EXECUTION_NOT_FOUND,
        )
        return dao.getExecutionById(execution.id)
    }

    /**
     * background Tool 在启动成功后本地已是 SUCCEEDED，即使远端仍 RUNNING 也不能改回 RUNNING。
     * foreground 的 STARTING/RUNNING 才跟随远端状态推进到终态。
     */
    private fun localToolStatus(
        execution: ComputerExecutionEntity,
        remoteStatus: ComputerRemoteStatus,
    ): String? {
        val current = execution.status
        val backgroundReturnedHandle = execution.completionMode == "RETURN_HANDLE"
        if (backgroundReturnedHandle && current in TOOL_TERMINAL_STATUSES) return null
        // 用户停止后本地可以先进入 CANCELLED；远端取消尚未确认时只更新 remoteStatus，
        // 不把本地终态重新改成 RUNNING。
        if (current == ComputerExecutionStatus.CANCELLED.name &&
            remoteStatus in setOf(ComputerRemoteStatus.STARTING, ComputerRemoteStatus.RUNNING)
        ) return null
        return when (remoteStatus) {
            ComputerRemoteStatus.STARTING -> if (current == "STARTING") "STARTING" else "RUNNING"
            ComputerRemoteStatus.RUNNING -> if (current in setOf("QUEUED", "STARTING", "RUNNING")) "RUNNING" else null
            ComputerRemoteStatus.SUCCEEDED -> if (current !in TOOL_TERMINAL_STATUSES) "SUCCEEDED" else null
            ComputerRemoteStatus.FAILED -> if (current !in TOOL_TERMINAL_STATUSES) "FAILED" else null
            ComputerRemoteStatus.TIMED_OUT -> if (current !in TOOL_TERMINAL_STATUSES) "TIMED_OUT" else null
            ComputerRemoteStatus.CANCELLED -> if (current !in TOOL_TERMINAL_STATUSES) "CANCELLED" else null
            ComputerRemoteStatus.STOPPED,
            ComputerRemoteStatus.MISSING,
            ComputerRemoteStatus.UNKNOWN,
            -> if (current == "SUCCEEDED" && backgroundReturnedHandle) null else "UNKNOWN"
        }
    }

    private fun parseTargetOrNull(value: String): ComputerExecTarget? = runCatching {
        ComputerExecTarget.valueOf(value)
    }.getOrNull()

    private companion object {
        val TOOL_TERMINAL_STATUSES = setOf("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED")
        val TERMINAL_REMOTE_STATUSES = setOf(
            ComputerRemoteStatus.SUCCEEDED,
            ComputerRemoteStatus.FAILED,
            ComputerRemoteStatus.TIMED_OUT,
            ComputerRemoteStatus.CANCELLED,
        )
    }
}
