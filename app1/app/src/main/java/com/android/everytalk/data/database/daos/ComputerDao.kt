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

    @Query("SELECT * FROM computer_executions WHERE status = 'UNKNOWN' ORDER BY finishedAt ASC")
    suspend fun getUnknownExecutions(): List<ComputerExecutionEntity>

    @Upsert
    suspend fun upsertExecution(execution: ComputerExecutionEntity)

    @Query("DELETE FROM computer_executions WHERE id = :executionId AND status = 'UNKNOWN'")
    suspend fun deleteExecution(executionId: String)

    @Query("UPDATE computer_executions SET status = 'UNKNOWN', finishedAt = :finishedAt, errorCode = 'EXECUTION_UNKNOWN' WHERE status IN ('STARTING', 'RUNNING')")
    suspend fun markInterruptedExecutionsUnknown(finishedAt: Long = System.currentTimeMillis())

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
