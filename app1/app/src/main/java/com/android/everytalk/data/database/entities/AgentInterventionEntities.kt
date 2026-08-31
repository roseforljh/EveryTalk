package com.android.everytalk.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 人类接力账本。此表只保存非敏感摘要和状态，不保存 resolution 明文。 */
@Entity(
    tableName = "agent_suspensions",
    indices = [
        Index(value = ["activeSuspensionIdempotencyKey"], unique = true),
        Index(value = ["runId", "status"]),
        Index(value = ["runId", "executionSlot"]),
    ],
    foreignKeys = [ForeignKey(entity = AgentRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
)
data class AgentSuspensionEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val runGeneration: Long,
    val turnId: String,
    val requestId: String,
    val toolCallId: String,
    val executionSlot: String,
    val requestHash: String,
    val capabilityId: String,
    val targetBindingRef: String,
    val requestSource: String,
    val policyVersion: String,
    val adapterContractVersion: String,
    val bindingGeneration: Long,
    val executionGeneration: Long,
    val resourceEpoch: Long,
    val activeSuspensionIdempotencyKey: String,
    val resolutionMaterialKind: String,
    val status: String,
    val continuationKind: String,
    val reconciliationPhase: String? = null,
    val resolutionNonceHash: String? = null,
    val fulfillmentAttemptId: String? = null,
    val resumeAttemptId: String? = null,
    val rowVersion: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long?,
    val failureCode: String? = null,
)

/** Tool 槽位只保存槽位生命周期，不复制 Suspension 内部细状态。 */
@Entity(
    tableName = "agent_execution_slots",
    primaryKeys = ["runId", "executionSlot"],
    indices = [Index(value = ["runId", "state"])],
    foreignKeys = [ForeignKey(entity = AgentRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
)
data class AgentExecutionSlotEntity(
    val runId: String,
    val executionSlot: String,
    val toolCallId: String,
    val executionGeneration: Long,
    val state: String,
    val suspensionId: String? = null,
    val updatedAt: Long,
)

/** 执行期 Grant。usageCount 和状态由 DAO 原子 CAS 更新。 */
@Entity(
    tableName = "agent_capability_grants",
    indices = [
        Index(value = ["runId", "executionSlot"]),
        Index(value = ["status", "expiresAt"]),
    ],
    foreignKeys = [ForeignKey(entity = AgentRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
)
data class AgentCapabilityGrantEntity(
    @PrimaryKey val grantId: String,
    val capability: String,
    val runId: String,
    val runGeneration: Long,
    val toolCallId: String,
    val executionSlot: String,
    val operation: String,
    val targetBinding: String,
    val audience: String,
    val scope: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val maxUses: Int,
    val usageCount: Int = 0,
    val grantUseAttemptId: String? = null,
    val status: String = "AVAILABLE",
    val generation: Long,
    val revoked: Boolean = false,
    val rowVersion: Long = 0,
)

/** 底层资源互斥 Lease，与 execution slot 状态分开。 */
@Entity(
    tableName = "agent_resource_leases",
    primaryKeys = ["resourceRef", "leaseKind"],
    indices = [Index(value = ["runId", "leaseOwner"]), Index(value = ["expiresAt"])],
    foreignKeys = [ForeignKey(entity = AgentRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
)
data class AgentResourceLeaseEntity(
    val resourceRef: String,
    val leaseOwner: String,
    val leaseKind: String,
    val leaseGeneration: Long,
    val runId: String,
    val runGeneration: Long,
    val issuedAt: Long,
    val expiresAt: Long,
    val revoked: Boolean = false,
)

/** 长期授权只保存安全存储引用和用户授权范围，不保存 credential 明文。 */
@Entity(
    tableName = "agent_stored_authorizations",
    indices = [Index(value = ["provider", "workspaceId", "computerId"])],
)
data class AgentStoredAuthorizationEntity(
    @PrimaryKey val authorizationId: String,
    val provider: String,
    val credentialReference: String,
    val userConsentScope: String,
    val workspaceId: String?,
    val computerId: String?,
    val issuedAt: Long,
    val expiresAt: Long?,
    val revoked: Boolean,
    val generation: Long,
)

/** OAuth state 元数据。state 和 verifier 明文均不写入 Room。 */
@Entity(
    tableName = "agent_oauth_states",
    indices = [Index(value = ["runId", "consumed", "expiresAt"])],
    foreignKeys = [ForeignKey(entity = AgentRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
)
data class AgentOAuthStateEntity(
    @PrimaryKey val stateHash: String,
    val runId: String,
    val runGeneration: Long,
    val capability: String,
    val targetBinding: String,
    val clientId: String,
    val redirectUri: String,
    val verifierReference: String,
    val verifierGeneration: Long,
    val issuedAt: Long,
    val expiresAt: Long,
    val consumed: Boolean = false,
    val callbackAttemptId: String? = null,
    val rowVersion: Long = 0,
)
