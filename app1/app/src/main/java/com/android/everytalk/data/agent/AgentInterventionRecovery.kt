package com.android.everytalk.data.agent

import com.android.everytalk.data.database.daos.AgentDao
import java.util.UUID

/** 启动恢复只读取 Room 事实，不依赖旧 Flow、callback 或 wake-up。 */
class AgentInterventionRecovery(
    private val dao: AgentDao,
    private val store: AgentInterventionStore,
    private val registry: AgentInterventionPolicyRegistry = AgentInterventionPolicyRegistry(),
    private val broker: AgentInterventionBroker? = null,
) {
    /** 兼容旧调用方：旧集合只能表示“已有投影”，无法验证 nonce，因此会安全轮换。 */
    suspend fun recover(activeNonceIds: Set<String>): List<RecoveryAction> =
        recover(activeNonces = activeNonceIds.associateWith { null })

    data class RecoveryAction(
        val suspensionId: String,
        val action: String,
        /** 只在需要重新输入时返回给可信 UI，禁止进入 Room、日志或模型。 */
        val newResolutionNonce: String? = null,
    )

    suspend fun recover(activeNonces: Map<String, String?> = emptyMap()): List<RecoveryAction> = buildList {
        store.startupCandidates().forEach { suspension ->
            val run = dao.getRun(suspension.runId) ?: return@forEach
            if (run.status in TERMINAL_RUN_STATUSES || run.runGeneration != suspension.runGeneration) {
                val state = runCatching { SuspensionState.valueOf(suspension.status) }.getOrNull()
                if (state in setOf(
                        SuspensionState.WAITING_USER,
                        SuspensionState.WAITING_USER_REENTRY,
                        SuspensionState.RESOLUTION_RECEIVED,
                        SuspensionState.DELIVERED,
                        SuspensionState.READY_TO_RESUME,
                        SuspensionState.READY_TO_RESUME_WITH_FAILURE,
                        SuspensionState.RESUMING,
                        SuspensionState.RESUMED,
                    )
                ) {
                    dao.cancelPendingSuspensionsAndSlots(
                        runId = run.id,
                        reason = run.terminalReason ?: AgentRunTerminalResult.RUN_TERMINATED,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                if (state in setOf(
                        SuspensionState.FULFILLING,
                        SuspensionState.DELIVERY_UNKNOWN,
                        SuspensionState.RECONCILIATION_REQUIRED,
                        SuspensionState.RECONCILING,
                    )
                ) {
                    val fact = broker?.reconcile(suspension.id)
                    add(RecoveryAction(suspension.id, "TERMINAL_RECONCILIATION_${fact?.name ?: "REQUIRED"}"))
                } else {
                    add(RecoveryAction(suspension.id, "TERMINAL_CLEANUP_ONLY"))
                }
                return@forEach
            }
            val state = SuspensionState.valueOf(suspension.status)
            val compatibility = registry.compatibility(
                suspension.capabilityId,
                suspension.policyVersion,
                suspension.adapterContractVersion,
            )
            if (compatibility != AgentInterventionPolicyRegistry.Compatibility.COMPATIBLE) {
                val safeState = if (
                    state in setOf(
                        SuspensionState.WAITING_USER,
                        SuspensionState.WAITING_USER_REENTRY,
                        SuspensionState.RESOLUTION_RECEIVED,
                        SuspensionState.DELIVERED,
                        SuspensionState.READY_TO_RESUME,
                        SuspensionState.READY_TO_RESUME_WITH_FAILURE,
                    )
                ) SuspensionState.READY_TO_RESUME_WITH_FAILURE else SuspensionState.USER_DECISION_REQUIRED
                if (state == safeState || store.outcome(
                        suspension.id,
                        state,
                        safeState,
                        suspension.rowVersion,
                        compatibility.name,
                    )
                ) {
                    add(RecoveryAction(suspension.id, compatibility.name))
                }
                return@forEach
            }
            when (state) {
                SuspensionState.RESOLUTION_RECEIVED -> {
                    if (suspension.resolutionMaterialKind == ResolutionMaterialKind.EPHEMERAL.name) {
                        val nonce = UUID.randomUUID().toString()
                        if (store.enterUserReentry(suspension.id, SuspensionState.RESOLUTION_RECEIVED, suspension.rowVersion, nonce)) {
                            add(RecoveryAction(suspension.id, "WAITING_USER_REENTRY", nonce))
                        }
                    } else if (broker != null) {
                        val fact = broker.fulfill(suspension.id)
                        add(RecoveryAction(suspension.id, "FULFILLMENT_${fact?.name ?: "NOT_CLAIMED"}"))
                    } else {
                        add(RecoveryAction(suspension.id, "FULFILLMENT_PENDING_ADAPTER"))
                    }
                }
                SuspensionState.DELIVERED -> {
                    if (broker?.recoverDelivered(suspension.id) == true ||
                        broker == null && store.transition(
                            suspension.id,
                            SuspensionState.DELIVERED,
                            SuspensionState.READY_TO_RESUME,
                            suspension.rowVersion,
                        )
                    ) {
                        add(RecoveryAction(suspension.id, "READY_TO_RESUME"))
                    }
                }
                SuspensionState.FULFILLING,
                SuspensionState.DELIVERY_UNKNOWN,
                SuspensionState.RECONCILIATION_REQUIRED,
                SuspensionState.RECONCILING,
                -> {
                    val fact = broker?.reconcile(suspension.id)
                    add(RecoveryAction(suspension.id, "RECONCILIATION_${fact?.name ?: "REQUIRED"}"))
                }
                SuspensionState.READY_TO_RESUME,
                SuspensionState.RESUMING,
                SuspensionState.READY_TO_RESUME_WITH_FAILURE,
                -> add(RecoveryAction(suspension.id, "RESUME_REQUIRED"))
                SuspensionState.WAITING_USER,
                SuspensionState.WAITING_USER_REENTRY,
                -> {
                    // “仍持有旧 nonce”不代表它与 Room 匹配；重新输入分支会替换 nonce hash。
                    if (suspension.id in activeNonces &&
                        (activeNonces[suspension.id] == null || store.matchesResolutionNonce(suspension, activeNonces[suspension.id]))) {
                        add(RecoveryAction(suspension.id, "PROJECT_TO_UI"))
                    } else {
                        val nonce = UUID.randomUUID().toString()
                        if (store.rotateResolutionNonce(suspension.id, state, suspension.rowVersion, nonce)) {
                            add(RecoveryAction(suspension.id, "PROJECT_TO_UI", nonce))
                        }
                    }
                }
                SuspensionState.USER_DECISION_REQUIRED -> add(RecoveryAction(suspension.id, "PROJECT_TO_UI"))
                else -> Unit
            }
        }
    }

    private companion object {
        val TERMINAL_RUN_STATUSES = setOf(
            AgentRunStatus.COMPLETED.name,
            AgentRunStatus.FAILED.name,
            AgentRunStatus.CANCELLED.name,
        )
    }
}
