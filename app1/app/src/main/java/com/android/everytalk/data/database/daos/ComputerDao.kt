package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.android.everytalk.data.database.entities.ComputerAuditEventEntity
import com.android.everytalk.data.database.entities.ComputerEntity
import com.android.everytalk.data.database.entities.ComputerExecutionEntity
import com.android.everytalk.data.database.entities.ComputerPreviewEntity
import com.android.everytalk.data.database.entities.ComputerWorkspaceEntity
import com.android.everytalk.data.database.entities.ConversationComputerSelectionEntity
import com.android.everytalk.data.database.entities.WorkspaceSecretMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ComputerDao {
    @Query("SELECT * FROM computers WHERE status != 'DELETED' ORDER BY createdAt ASC")
    fun observeComputers(): Flow<List<ComputerEntity>>

    @Query("SELECT * FROM computers WHERE id = :computerId LIMIT 1")
    suspend fun getComputer(computerId: String): ComputerEntity?

    @Query("SELECT * FROM computers WHERE runMode = 'DIRECT' AND status != 'DELETED'")
    suspend fun getLegacyDirectComputers(): List<ComputerEntity>

    @Upsert
    suspend fun upsertComputer(computer: ComputerEntity)

    @Query("UPDATE computers SET status = :status, lastErrorCode = :errorCode, updatedAt = :updatedAt WHERE id = :computerId")
    suspend fun updateComputerStatus(
        computerId: String,
        status: String,
        errorCode: String?,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE computers SET permissionMode = :permissionMode, updatedAt = :updatedAt WHERE id = :computerId")
    suspend fun updatePermissionMode(
        computerId: String,
        permissionMode: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM computers WHERE id = :computerId")
    suspend fun deleteComputer(computerId: String)

    @Query("SELECT selectedComputerId FROM conversation_computer_selections WHERE conversationId = :conversationId")
    suspend fun getSelectedComputerId(conversationId: String): String?

    @Query("SELECT * FROM conversation_computer_selections")
    fun observeSelections(): Flow<List<ConversationComputerSelectionEntity>>

    @Upsert
    suspend fun upsertSelection(selection: ConversationComputerSelectionEntity)

    @Query("DELETE FROM conversation_computer_selections WHERE conversationId = :conversationId")
    suspend fun clearSelection(conversationId: String)

    @Transaction
    suspend fun selectComputer(conversationId: String, computerId: String) {
        upsertSelection(
            ConversationComputerSelectionEntity(
                conversationId = conversationId,
                selectedComputerId = computerId,
            ),
        )
    }

    @Query("SELECT * FROM computer_workspaces WHERE computerId = :computerId AND conversationId = :conversationId LIMIT 1")
    suspend fun getWorkspace(computerId: String, conversationId: String): ComputerWorkspaceEntity?

    @Query("SELECT * FROM computer_workspaces WHERE conversationId = :conversationId")
    suspend fun getWorkspacesForConversation(conversationId: String): List<ComputerWorkspaceEntity>

    @Query("SELECT * FROM computer_workspaces WHERE id = :workspaceId LIMIT 1")
    suspend fun getWorkspaceById(workspaceId: String): ComputerWorkspaceEntity?

    @Query("SELECT * FROM computer_workspaces WHERE computerId = :computerId ORDER BY lastUsedAt DESC")
    fun observeWorkspaces(computerId: String): Flow<List<ComputerWorkspaceEntity>>

    @Query("SELECT * FROM computer_workspaces WHERE computerId = :computerId ORDER BY lastUsedAt DESC")
    suspend fun getWorkspacesForComputer(computerId: String): List<ComputerWorkspaceEntity>

    @Upsert
    suspend fun upsertWorkspace(workspace: ComputerWorkspaceEntity)

    /**
     * 远端准备只更新不会改变 Workspace 归属的运行字段。
     * 首条消息可能同时迁移 conversationId，禁止用旧实体整行 Upsert 把稳定 ID 覆盖回临时 ID。
     */
    @Query(
        """
        UPDATE computer_workspaces
        SET hostPath = :hostPath,
            status = :status,
            lastUsedAt = :lastUsedAt
        WHERE id = :workspaceId
        """,
    )
    suspend fun updateWorkspaceRuntimeState(
        workspaceId: String,
        hostPath: String,
        status: String,
        lastUsedAt: Long = System.currentTimeMillis(),
    )

    /** Container 配置成功后补齐迁移自旧 Direct 记录的镜像元数据。 */
    @Query("UPDATE computer_workspaces SET containerImage = :containerImage WHERE computerId = :computerId AND runMode = 'CONTAINER'")
    suspend fun updateContainerWorkspaceImage(computerId: String, containerImage: String)

    /** 服务器地址、端口或账号改变后，旧 Workspace 保留文件映射并等待新目标重新校验。 */
    @Query("UPDATE computer_workspaces SET status = 'RECOVERING' WHERE computerId = :computerId")
    suspend fun markComputerWorkspacesRecovering(computerId: String)

    @Query("DELETE FROM computer_workspaces WHERE id = :workspaceId")
    suspend fun deleteWorkspace(workspaceId: String)

    @Query("UPDATE computer_workspaces SET conversationId = :targetConversationId WHERE id = :workspaceId")
    suspend fun updateWorkspaceConversationId(workspaceId: String, targetConversationId: String)

    /**
     * 首条消息落库后，聊天会把临时会话 ID 换成稳定 ID。
     * 服务器选择和 Workspace 必须在同一事务内跟随迁移，避免第一轮 Tool 路由漂移。
     */
    @Transaction
    suspend fun migrateConversationId(sourceConversationId: String, targetConversationId: String) {
        if (sourceConversationId == targetConversationId) return

        val sourceSelection = getSelectedComputerId(sourceConversationId)
        if (sourceSelection != null && getSelectedComputerId(targetConversationId) == null) {
            selectComputer(targetConversationId, sourceSelection)
        }
        clearSelection(sourceConversationId)

        getWorkspacesForConversation(sourceConversationId).forEach { sourceWorkspace ->
            val existingTarget = getWorkspace(sourceWorkspace.computerId, targetConversationId)
            if (existingTarget == null) {
                updateWorkspaceConversationId(sourceWorkspace.id, targetConversationId)
            } else {
                // 同一服务器已有稳定 Workspace 时保留稳定映射，远端源目录仍保留，禁止误删用户文件。
                deleteWorkspace(sourceWorkspace.id)
            }
        }
    }

    @Query("SELECT * FROM computer_executions WHERE toolCallId = :toolCallId LIMIT 1")
    suspend fun getExecutionByToolCallId(toolCallId: String): ComputerExecutionEntity?

    @Query("SELECT * FROM computer_executions WHERE id = :executionId LIMIT 1")
    suspend fun getExecutionById(executionId: String): ComputerExecutionEntity?

    /**
     * 找出需要和 VPS 对账的执行。
     *
     * 旧记录没有 remoteStatus，因此在本地仍处于 STARTING/RUNNING 时也必须纳入恢复，
     * 否则升级后第一轮已经发出的命令会被遗漏。前台执行还必须关联等待远端结果的
     * AgentRun；已结束 Run 遗留的 RUNNING 快照不能继续启动服务或发送断线通知。
     * RETURN_HANDLE 和尚未确认的取消请求仍由服务持续监听。
     */
    @Query(
        """
        SELECT execution.* FROM computer_executions AS execution
        INNER JOIN computer_workspaces AS workspace ON workspace.id = execution.workspaceId
        WHERE execution.toolName = 'exec' AND (
                execution.remoteStatus IN ('STARTING', 'RUNNING')
                AND (
                    execution.status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLED')
                    OR (execution.completionMode = 'RETURN_HANDLE' AND execution.status = 'SUCCEEDED')
                    OR (execution.status = 'UNKNOWN' AND execution.errorCode IN (
                        'EXECUTION_CANCEL_FAILED', 'EXECUTION_RESULT_UNAVAILABLE', 'EXECUTION_UNKNOWN'
                    ))
                )
           OR (execution.remoteStatus IS NULL AND execution.status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLED'))
           OR (execution.remoteStatus = 'UNKNOWN' AND execution.status = 'UNKNOWN' AND execution.errorCode IN (
                'EXECUTION_CANCEL_FAILED', 'EXECUTION_RESULT_UNAVAILABLE', 'EXECUTION_UNKNOWN'
              ))
              )
          AND (
                execution.completionMode = 'RETURN_HANDLE'
                OR execution.errorCode IN ('EXECUTION_CANCEL_REQUESTED', 'EXECUTION_CANCEL_FAILED')
                OR EXISTS (
                    SELECT 1 FROM agent_runs AS run
                    WHERE run.status IN ('WAITING_REMOTE_EXECUTION', 'INTERRUPTED')
                      AND (
                            run.id = execution.runId
                            OR (execution.runId IS NULL AND run.sessionId = workspace.conversationId)
                          )
                )
              )
        ORDER BY COALESCE(execution.lastObservedAt, execution.startedAt, 0) ASC
        """,
    )
    suspend fun getActiveRemoteExecutions(): List<ComputerExecutionEntity>

    /** App 启动和 Agent 恢复只主动查询等待结果的前台任务，避免唤醒历史后台服务。 */
    @Query(
        """
        SELECT * FROM computer_executions
        WHERE toolName = 'exec'
          AND (completionMode IS NULL OR completionMode != 'RETURN_HANDLE')
          AND (
                (remoteStatus IN ('STARTING', 'RUNNING') AND (
                    status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLED')
                    OR (status = 'UNKNOWN' AND errorCode IN (
                        'EXECUTION_CANCEL_FAILED', 'EXECUTION_RESULT_UNAVAILABLE', 'EXECUTION_UNKNOWN'
                    ))
                 ))
                OR (remoteStatus IS NULL AND status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLED'))
                OR (remoteStatus = 'UNKNOWN' AND status = 'UNKNOWN' AND errorCode IN (
                    'EXECUTION_CANCEL_FAILED', 'EXECUTION_RESULT_UNAVAILABLE', 'EXECUTION_UNKNOWN'
                  ))
              )
        ORDER BY COALESCE(lastObservedAt, startedAt, 0) ASC
        """,
    )
    suspend fun getActiveForegroundRemoteExecutions(): List<ComputerExecutionEntity>

    /** 仅查询指定会话的前台执行，供 AgentRun 恢复使用。 */
    @Query(
        """
        SELECT execution.* FROM computer_executions AS execution
        INNER JOIN computer_workspaces AS workspace ON workspace.id = execution.workspaceId
        WHERE execution.toolName = 'exec'
          AND workspace.conversationId IN (:conversationIds)
          AND (execution.completionMode IS NULL OR execution.completionMode != 'RETURN_HANDLE')
          AND EXISTS (
                SELECT 1 FROM agent_runs AS run
                WHERE run.sessionId = workspace.conversationId
                  AND run.status IN ('WAITING_REMOTE_EXECUTION', 'INTERRUPTED')
          )
          AND (
                (execution.remoteStatus IN ('STARTING', 'RUNNING') AND (
                    execution.status IN ('QUEUED', 'STARTING', 'RUNNING')
                    OR execution.status = 'CANCELLED'
                    OR (execution.status = 'UNKNOWN' AND execution.errorCode IN (
                        'EXECUTION_CANCEL_FAILED', 'EXECUTION_RESULT_UNAVAILABLE', 'EXECUTION_UNKNOWN'
                    ))
                ))
                OR (execution.remoteStatus IS NULL AND execution.status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLED'))
                OR (execution.remoteStatus = 'UNKNOWN' AND execution.status = 'UNKNOWN' AND execution.errorCode IN (
                    'EXECUTION_CANCEL_FAILED', 'EXECUTION_RESULT_UNAVAILABLE', 'EXECUTION_UNKNOWN'
                ))
              )
        ORDER BY COALESCE(execution.lastObservedAt, execution.startedAt, 0) ASC
        """,
    )
    suspend fun getActiveForegroundRemoteExecutionsForConversations(
        conversationIds: List<String>,
    ): List<ComputerExecutionEntity>

    @Query(
        """
        SELECT * FROM computer_executions
        WHERE toolName = 'exec'
          AND computerId = :computerId
          AND (
                (remoteStatus IN ('STARTING', 'RUNNING')
                 AND (
                    status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLED')
                    OR (completionMode = 'RETURN_HANDLE' AND status = 'SUCCEEDED')
                    OR (status = 'UNKNOWN' AND errorCode IN (
                        'EXECUTION_CANCEL_FAILED', 'EXECUTION_RESULT_UNAVAILABLE', 'EXECUTION_UNKNOWN'
                    ))
                 ))
                OR (remoteStatus IS NULL AND status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLED'))
                OR (remoteStatus = 'UNKNOWN' AND status = 'UNKNOWN' AND errorCode IN (
                    'EXECUTION_CANCEL_FAILED', 'EXECUTION_RESULT_UNAVAILABLE', 'EXECUTION_UNKNOWN'
                ))
              )
        ORDER BY COALESCE(lastObservedAt, startedAt, 0) ASC
        """,
    )
    suspend fun getActiveRemoteExecutionsForComputer(computerId: String): List<ComputerExecutionEntity>

    /**
     * 获取某个会话 Workspace 下仍在 VPS 运行的受管 Execution。
     * Workspace 是会话与服务器的稳定映射，不需要把会话 ID 再复制到 Execution 表。
     */
    @Query(
        """
        SELECT execution.* FROM computer_executions AS execution
        INNER JOIN computer_workspaces AS workspace ON workspace.id = execution.workspaceId
        WHERE execution.toolName = 'exec'
          AND workspace.conversationId = :conversationId
          AND (execution.completionMode IS NULL OR execution.completionMode != 'RETURN_HANDLE')
          AND (
                (execution.remoteStatus IN ('STARTING', 'RUNNING')
                 AND (
                    execution.status IN ('QUEUED', 'STARTING', 'RUNNING')
                    OR execution.status = 'CANCELLED'
                    OR (execution.status = 'UNKNOWN' AND execution.errorCode IN (
                        'EXECUTION_CANCEL_FAILED', 'EXECUTION_RESULT_UNAVAILABLE', 'EXECUTION_UNKNOWN'
                    ))
                 ))
                OR (execution.remoteStatus IS NULL AND execution.status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLED'))
                OR (execution.remoteStatus = 'UNKNOWN' AND execution.status = 'UNKNOWN' AND execution.errorCode IN (
                    'EXECUTION_CANCEL_FAILED', 'EXECUTION_RESULT_UNAVAILABLE', 'EXECUTION_UNKNOWN'
                ))
              )
        ORDER BY COALESCE(execution.lastObservedAt, execution.startedAt, 0) ASC
        """,
    )
    suspend fun getActiveForegroundRemoteExecutionsForConversation(
        conversationId: String,
    ): List<ComputerExecutionEntity>

    /** 停止按钮使用该查询，取消指定 AgentRun 下创建的全部前台与后台 VPS 任务。 */
    @Query(
        """
        SELECT * FROM computer_executions
        WHERE runId = :runId
          AND toolName = 'exec'
          AND (
                remoteStatus IN ('STARTING', 'RUNNING')
                OR (remoteStatus IS NULL AND status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLED'))
              )
        ORDER BY COALESCE(lastObservedAt, startedAt, 0) ASC
        """,
    )
    suspend fun getCancellableRemoteExecutionsForRun(
        runId: String,
    ): List<ComputerExecutionEntity>

    /** 停止按钮备用查询，取消该会话下当前所有仍在运行的前台和后台受管任务。 */
    @Query(
        """
        SELECT execution.* FROM computer_executions AS execution
        INNER JOIN computer_workspaces AS workspace ON workspace.id = execution.workspaceId
        WHERE workspace.conversationId = :conversationId
          AND execution.toolName = 'exec'
          AND (
                execution.remoteStatus IN ('STARTING', 'RUNNING')
                OR (execution.remoteStatus IS NULL AND execution.status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLED'))
              )
        ORDER BY COALESCE(execution.lastObservedAt, execution.startedAt, 0) ASC
        """,
    )
    suspend fun getCancellableRemoteExecutionsForConversation(
        conversationId: String,
    ): List<ComputerExecutionEntity>

    /** 先记录取消意图，再发 SSH 请求，App 在这段竞态窗口退出后仍会继续对账。 */
    @Query(
        """
        UPDATE computer_executions
        SET errorCode = 'EXECUTION_CANCEL_REQUESTED',
            cancelRequestedAt = :observedAt,
            lastObservedAt = :observedAt
        WHERE id = :executionId
          AND toolName = 'exec'
          AND (
                remoteStatus IN ('STARTING', 'RUNNING')
                OR (remoteStatus IS NULL AND status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLED'))
          )
        """,
    )
    suspend fun markRemoteExecutionCancellationRequested(
        executionId: String,
        observedAt: Long = System.currentTimeMillis(),
    )

    /** 按 AgentRun 查询全部活动 Execution。 */
    @Query(
        """
        SELECT * FROM computer_executions
        WHERE runId = :runId
          AND toolName = 'exec'
          AND (
                remoteStatus IN ('STARTING', 'RUNNING')
                OR (remoteStatus IS NULL AND status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLED'))
          )
        ORDER BY COALESCE(lastObservedAt, startedAt, 0) ASC
        """,
    )
    suspend fun getActiveExecutionsForAgentRun(runId: String): List<ComputerExecutionEntity>

    /** 判断 AgentRun 是否真正创建过 VPS 操作，普通聊天不会命中。 */
    @Query("SELECT COUNT(*) FROM computer_executions WHERE runId = :runId")
    suspend fun countExecutionsForAgentRun(runId: String): Int

    /** 原子声明结果已接回原 AgentRun，防止重复续写（UPDATE ... WHERE resultAttachedAt IS NULL）。返回更新行数。 */
    @Query(
        """
        UPDATE computer_executions
        SET resultAttachedAt = :attachedAt,
            lastObservedAt = :attachedAt
        WHERE id = :executionId
          AND resultAttachedAt IS NULL
        """,
    )
    suspend fun claimResult(
        executionId: String,
        attachedAt: Long = System.currentTimeMillis(),
    ): Int

    @Query(
        """
        UPDATE computer_executions
        SET resultAttachedAt = :attachedAt,
            lastObservedAt = :attachedAt
        WHERE id = :executionId
          AND resultAttachedAt IS NULL
        """,
    )
    suspend fun markResultAttached(
        executionId: String,
        attachedAt: Long = System.currentTimeMillis(),
    ): Int

    /** 标记结果已接回原 AgentRun，防止重复消费。 */
    @Query("UPDATE computer_executions SET resultAttachedAt = :observedAt, lastObservedAt = :observedAt WHERE id = :executionId AND resultAttachedAt IS NULL")
    suspend fun markExecutionResultConsumed(
        executionId: String,
        observedAt: Long = System.currentTimeMillis(),
    )

    /** 查询指定 Run 下已完成但尚未接回结果的 Execution。 */
    @Query(
        """
        SELECT * FROM computer_executions
        WHERE runId = :runId
          AND toolName = 'exec'
          AND resultAttachedAt IS NULL
          AND (
                remoteStatus IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'STOPPED')
                OR status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
          )
        ORDER BY COALESCE(finishedAt, lastObservedAt, 0) ASC
        """,
    )
    suspend fun getUnconsumedCompletedExecutionsForRun(runId: String): List<ComputerExecutionEntity>

    @Query(
        """
        SELECT * FROM computer_executions
        WHERE workspaceId = :workspaceId
          AND remoteStatePath IS NOT NULL
        ORDER BY COALESCE(lastObservedAt, startedAt, 0) ASC
        """,
    )
    suspend fun getRemoteExecutionsForWorkspace(workspaceId: String): List<ComputerExecutionEntity>

    /** 启动确认后一次性写入远端引用，避免把状态目录交给模型参数。 */
    @Query(
        """
        UPDATE computer_executions
        SET target = COALESCE(:target, target),
            completionMode = COALESCE(:completionMode, completionMode),
            remoteProcessId = :remoteProcessId,
            remoteStatePath = :remoteStatePath,
            remoteStatus = :remoteStatus,
            runId = COALESCE(:runId, runId),
            lastObservedAt = :observedAt
        WHERE id = :executionId
        """,
    )
    suspend fun updateRemoteExecutionReference(
        executionId: String,
        target: String?,
        completionMode: String?,
        remoteProcessId: String?,
        remoteStatePath: String?,
        remoteStatus: String?,
        runId: String? = null,
        observedAt: Long = System.currentTimeMillis(),
    )

    /**
     * 写入一次已解析的远端观测。
     * localStatus 只由协调器根据当前执行语义计算，DAO 不自行把 background 的 Tool 状态改回 RUNNING。
     */
    @Query(
        """
        UPDATE computer_executions
        SET target = COALESCE(:target, target),
            remoteProcessId = COALESCE(:remoteProcessId, remoteProcessId),
            remoteStatus = :remoteStatus,
            remoteExitCode = :remoteExitCode,
            lastObservedAt = :observedAt,
            status = COALESCE(:localStatus, status),
            finishedAt = COALESCE(:finishedAt, finishedAt),
            exitCode = COALESCE(:localExitCode, exitCode),
            errorCode = :errorCode
        WHERE id = :executionId
        """,
    )
    suspend fun updateRemoteExecutionObservation(
        executionId: String,
        target: String?,
        remoteProcessId: String?,
        remoteStatus: String,
        remoteExitCode: Int?,
        observedAt: Long,
        localStatus: String?,
        finishedAt: Long?,
        localExitCode: Int?,
        errorCode: String?,
    )

    /** 合并一次 Runtime Watch 事件。日志正文仍留在 VPS，只保存已消费字节游标和状态事实。 */
    @Query(
        """
        UPDATE computer_executions
        SET stdoutCursor = CASE WHEN :stdoutCursor > stdoutCursor THEN :stdoutCursor ELSE stdoutCursor END,
            stderrCursor = CASE WHEN :stderrCursor > stderrCursor THEN :stderrCursor ELSE stderrCursor END,
            lastEventAt = :eventAt,
            lastObservedAt = :observedAt,
            remoteStatus = :remoteStatus,
            remoteExitCode = :remoteExitCode
        WHERE id = :executionId
        """,
    )
    suspend fun updateRemoteExecutionProgress(
        executionId: String,
        stdoutCursor: Long,
        stderrCursor: Long,
        eventAt: Long,
        observedAt: Long,
        remoteStatus: String,
        remoteExitCode: Int?,
    )

    /** 状态协议损坏或查询结果无法核实时，只保留 UNKNOWN，不猜测远端成功或失败。 */
    @Query(
        """
        UPDATE computer_executions
        SET remoteStatus = 'UNKNOWN',
            errorCode = :errorCode,
            lastObservedAt = :observedAt,
            status = COALESCE(:localStatus, status),
            finishedAt = COALESCE(:finishedAt, finishedAt)
        WHERE id = :executionId
        """,
    )
    suspend fun markRemoteExecutionUnknown(
        executionId: String,
        errorCode: String = "EXECUTION_UNKNOWN",
        observedAt: Long = System.currentTimeMillis(),
        localStatus: String? = null,
        finishedAt: Long? = null,
    )

    @Query("SELECT * FROM computer_executions WHERE status = 'UNKNOWN' ORDER BY finishedAt ASC")
    suspend fun getUnknownExecutions(): List<ComputerExecutionEntity>

    @Upsert
    suspend fun upsertExecution(execution: ComputerExecutionEntity)

    @Query("DELETE FROM computer_executions WHERE id = :executionId AND status = 'UNKNOWN'")
    suspend fun deleteExecution(executionId: String)

    /**
     * 普通 Computer Tool 没有 VPS 状态文件，进程退出后无法继续确认结果。
     * exec 由远端对账器恢复，不能在这里提前覆盖成 UNKNOWN。
     */
    @Query(
        """
        UPDATE computer_executions
        SET status = 'UNKNOWN', finishedAt = :finishedAt, errorCode = 'EXECUTION_UNKNOWN'
        WHERE toolName != 'exec' AND status IN ('QUEUED', 'STARTING', 'RUNNING')
        """,
    )
    suspend fun markInterruptedNonExecExecutionsUnknown(finishedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM computer_previews WHERE workspaceId = :workspaceId ORDER BY createdAt DESC")
    fun observePreviews(workspaceId: String): Flow<List<ComputerPreviewEntity>>

    @Query("SELECT * FROM computer_previews WHERE workspaceId = :workspaceId ORDER BY createdAt DESC")
    suspend fun getPreviews(workspaceId: String): List<ComputerPreviewEntity>

    @Query("SELECT * FROM computer_previews WHERE visibility = 'PUBLIC' AND status = 'ACTIVE' AND expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun getExpiredPublicPreviews(now: Long = System.currentTimeMillis()): List<ComputerPreviewEntity>

    @Query("SELECT * FROM computer_previews WHERE visibility = 'PUBLIC' AND status = 'ACTIVE'")
    suspend fun getActivePublicPreviews(): List<ComputerPreviewEntity>

    @Query("SELECT p.* FROM computer_previews p INNER JOIN computer_workspaces w ON w.id = p.workspaceId WHERE w.computerId = :computerId AND p.visibility = 'PUBLIC' AND p.status = 'ACTIVE' ORDER BY p.createdAt DESC")
    suspend fun getActivePublicPreviewsForComputer(computerId: String): List<ComputerPreviewEntity>

    @Query("UPDATE computers SET status = 'CONFIGURATION_REQUIRED', updatedAt = :updatedAt WHERE runMode = 'CONTAINER' AND status = 'READY' AND (bootstrapVersion IS NULL OR bootstrapVersion != :expectedVersion)")
    suspend fun markOutdatedContainerConfiguration(
        expectedVersion: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    /**
     * Android 进程可能在 SSH 探测或 Container 配置期间被系统直接结束。
     * Container 版本落后时回到待修复，其余情况回到离线，禁止永久保留不可操作的中间状态。
     */
    @Query(
        """
        UPDATE computers
        SET status = CASE
                WHEN runMode = 'CONTAINER' AND (bootstrapVersion IS NULL OR bootstrapVersion != :expectedVersion)
                    THEN 'CONFIGURATION_REQUIRED'
                ELSE 'OFFLINE'
            END,
            updatedAt = :updatedAt
        WHERE status IN ('PROBING', 'PROVISIONING', 'VERIFYING')
        """,
    )
    suspend fun recoverInterruptedComputerOperations(
        expectedVersion: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("SELECT * FROM computer_previews WHERE id = :previewId LIMIT 1")
    suspend fun getPreview(previewId: String): ComputerPreviewEntity?

    @Upsert
    suspend fun upsertPreview(preview: ComputerPreviewEntity)

    @Query("UPDATE computer_previews SET status = 'STOPPED', localPort = NULL WHERE visibility = 'PRIVATE' AND status = 'ACTIVE'")
    suspend fun markPrivatePreviewsStopped()

    @Query("DELETE FROM computer_previews WHERE id = :previewId")
    suspend fun deletePreview(previewId: String)

    @Query("SELECT * FROM workspace_secret_metadata WHERE workspaceId = :workspaceId ORDER BY name ASC")
    fun observeWorkspaceSecrets(workspaceId: String): Flow<List<WorkspaceSecretMetadataEntity>>

    @Query("SELECT * FROM workspace_secret_metadata WHERE workspaceId = :workspaceId ORDER BY name ASC")
    suspend fun getWorkspaceSecrets(workspaceId: String): List<WorkspaceSecretMetadataEntity>

    @Query("SELECT * FROM workspace_secret_metadata WHERE workspaceId = :workspaceId AND name = :name LIMIT 1")
    suspend fun getWorkspaceSecret(workspaceId: String, name: String): WorkspaceSecretMetadataEntity?

    @Upsert
    suspend fun upsertWorkspaceSecretMetadata(metadata: WorkspaceSecretMetadataEntity)

    @Query("DELETE FROM workspace_secret_metadata WHERE id = :secretId")
    suspend fun deleteWorkspaceSecretMetadata(secretId: String)

    @Query("SELECT * FROM computer_audit_events WHERE computerId = :computerId ORDER BY createdAt DESC LIMIT :limit")
    fun observeAuditEvents(computerId: String, limit: Int = 100): Flow<List<ComputerAuditEventEntity>>

    @Upsert
    suspend fun upsertAuditEvent(event: ComputerAuditEventEntity)
}
