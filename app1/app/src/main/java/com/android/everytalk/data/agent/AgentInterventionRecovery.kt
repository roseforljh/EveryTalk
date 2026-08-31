package com.android.everytalk.data.agent

import com.android.everytalk.data.database.daos.AgentDao
import java.util.UUID

/** 启动恢复只读取 Room 事实，不依赖旧 Flow、callback 或 wake-up。 */
class AgentInterventionRecovery(
    private val dao: AgentDao,
    private val store: AgentInterventionStore,
    private val registry: AgentInterventionPolicyRegistry = AgentInterventionPolicyRegistry(),
    private val canFulfill: (String) -> Boolean = { false },
) {
    data class RecoveryAction(
        val suspensionId: String,
        val action: String,
        /** 只在需要重新输入时返回给可信 UI，禁止进入 Room、日志或模型。 */
        val newResolutionNonce: String? = null,
    )

    suspend fun recover(): List<RecoveryAction> = buildList {
        store.startupCandidates().forEach { suspension ->
            val run = dao.getRun(suspension.runId) ?: return@forEach
            if (run.status in TERMINAL_RUN_STATUSES || run.runGeneration != suspension.runGeneration) {
                add(RecoveryAction(suspension.id, "TERMINAL_CLEANUP_ONLY"))
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
                    } else if (canFulfill(suspension.capabilityId) && store.claimFulfillment(suspension.id, suspension.rowVersion, suspension.runGeneration, UUID.randomUUID().toString())) {
                        add(RecoveryAction(suspension.id, "CLAIMED_FULFILLMENT"))
                    } else {
                        add(RecoveryAction(suspension.id, "FULFILLMENT_PENDING_ADAPTER"))
                    }
                }
                SuspensionState.DELIVERED -> {
                    if (store.transition(suspension.id, SuspensionState.DELIVERED, SuspensionState.READY_TO_RESUME, suspension.rowVersion)) {
                        add(RecoveryAction(suspension.id, "READY_TO_RESUME"))
                    }
                }
                SuspensionState.FULFILLING,
                SuspensionState.DELIVERY_UNKNOWN,
                SuspensionState.RECONCILIATION_REQUIRED,
                SuspensionState.RECONCILING,
                -> add(RecoveryAction(suspension.id, "RECONCILIATION_REQUIRED"))
                SuspensionState.READY_TO_RESUME,
                SuspensionState.RESUMING,
                SuspensionState.READY_TO_RESUME_WITH_FAILURE,
                -> add(RecoveryAction(suspension.id, "RESUME_REQUIRED"))
                SuspensionState.WAITING_USER,
                SuspensionState.WAITING_USER_REENTRY,
                -> {
                    val nonce = UUID.randomUUID().toString()
                    if (store.rotateResolutionNonce(suspension.id, state, suspension.rowVersion, nonce)) {
                        add(RecoveryAction(suspension.id, "PROJECT_TO_UI", nonce))
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
