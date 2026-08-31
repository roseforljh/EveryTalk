package com.android.everytalk.data.agent

import kotlinx.serialization.Serializable

/** Agent 模型循环的整体状态。工具槽位和人类接力状态不放进此枚举。 */
enum class AgentLoopState {
    RUNNING,
    WAITING_TOOL_BATCH,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/** 单个 Tool 槽位的生命周期，不复制 Suspension 内部状态。 */
enum class ExecutionSlotState {
    PENDING,
    RUNNING,
    SUSPENDED,
    RESUMING,
    COMPLETED,
    FAILED,
    UNKNOWN,
}

/** 人类接力的持久化事实状态。 */
enum class SuspensionState {
    WAITING_USER,
    WAITING_USER_REENTRY,
    RESOLUTION_RECEIVED,
    FULFILLING,
    DELIVERED,
    DELIVERY_UNKNOWN,
    RECONCILIATION_REQUIRED,
    RECONCILING,
    READY_TO_RESUME,
    READY_TO_RESUME_WITH_FAILURE,
    RESUMING,
    RESUMED,
    USER_DECISION_REQUIRED,
    CANCELLED,
    EXPIRED,
    TARGET_LOST,
}

enum class ResolutionMaterialKind { NONE, EPHEMERAL, DURABLE_REFERENCE }

enum class ReconciliationOutcome { DELIVERED, NOT_DELIVERED, UNKNOWN }

enum class InterventionRequestSource(val trustLevel: Int) {
    MODEL_HINT(0),
    EXECUTOR_PROVEN(1),
    SYSTEM_CHALLENGE(2),
}

@Serializable
data class CapabilityRequest(
    val requestedCapability: String,
    val reasonSafe: String,
    val userVisibleContext: String? = null,
)

@Serializable
data class BindingRef(
    val bindingType: String,
    val bindingEntityId: String,
    val bindingGeneration: Long,
    val bindingDigest: String? = null,
    val ownerScope: String,
)

@Serializable
data class StoredAuthorization(
    val authorizationId: String,
    val provider: String,
    val credentialReference: String,
    val userConsentScope: String,
    val workspaceId: String? = null,
    val computerId: String? = null,
    val issuedAt: Long,
    val expiresAt: Long?,
    val revoked: Boolean,
    val generation: Long,
)

@Serializable
data class CapabilityGrant(
    val grantId: String,
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
    val generation: Long,
    val revoked: Boolean = false,
)

@Serializable
data class ResourceLease(
    val resourceRef: String,
    val leaseOwner: String,
    val leaseKind: String,
    val leaseGeneration: Long,
    val runId: String,
    val runGeneration: Long,
    val expiresAt: Long,
    val revoked: Boolean = false,
)

@Serializable
data class TargetAttestation(
    val attestationId: String,
    val source: String,
    val executionId: String,
    val issuedAt: Long,
    val expiresAt: Long,
)

@Serializable
enum class AgentContinuationKind {
    RETRY_TOOL,
    CONTINUE_TOOL,
    CONTINUE_EXECUTION,
    CONTINUE_PTY,
    RESUME_AGENT_LOOP,
    VERIFY_THEN_RESUME,
    REPLAN_REQUIRED,
}

@Serializable
data class ContinuationResult(
    val code: String,
    val message: String,
    val resumeAllowed: Boolean,
)

/** 模型不可构造的可信请求。敏感字段值只通过受保护 Adapter 传递。 */
@Serializable
data class TrustedInterventionRequest(
    val suspensionId: String,
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
    val resourceEpoch: Long = 0,
    val continuation: AgentContinuationKind,
    val resolutionMaterialKind: ResolutionMaterialKind,
    val activeSuspensionIdempotencyKey: String,
)

object AgentRunTerminalResult {
    const val RUN_TERMINATED = "RUN_TERMINATED"
    const val RUN_TERMINATED_AFTER_DELIVERY = "RUN_TERMINATED_AFTER_DELIVERY"
    const val RUN_TERMINATED_NOT_DELIVERED = "RUN_TERMINATED_NOT_DELIVERED"
    const val RUN_TERMINATED_EXTERNAL_STATE_UNKNOWN = "RUN_TERMINATED_EXTERNAL_STATE_UNKNOWN"
}

/** 可信 UI 的非敏感投影。resolutionNonce 只用于当前表单提交，不进入模型或持久化。 */
data class PendingIntervention(
    val suspensionId: String,
    val runId: String,
    val capabilityId: String,
    val state: SuspensionState,
    val resolutionNonce: String? = null,
)
