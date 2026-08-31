package com.android.everytalk.data.agent

enum class ApprovalGateState { NOT_REQUIRED, WAITING, ALLOWED, REJECTED }
enum class CapabilityGateState { NOT_REQUIRED, WAITING, AVAILABLE, FAILED }

data class ExecutionGateSnapshot(
    val approval: ApprovalGateState,
    val capability: CapabilityGateState,
)

/** Approval 与 Intervention 保持独立，只有所有必需 Gate 满足才允许执行。 */
class RunGateCoordinator {
    fun canExecute(snapshot: ExecutionGateSnapshot): Boolean =
        snapshot.approval in setOf(ApprovalGateState.NOT_REQUIRED, ApprovalGateState.ALLOWED) &&
            snapshot.capability in setOf(CapabilityGateState.NOT_REQUIRED, CapabilityGateState.AVAILABLE)

    fun slotState(snapshot: ExecutionGateSnapshot): ExecutionSlotState = when {
        canExecute(snapshot) -> ExecutionSlotState.RUNNING
        snapshot.approval == ApprovalGateState.REJECTED || snapshot.capability == CapabilityGateState.FAILED -> ExecutionSlotState.FAILED
        else -> ExecutionSlotState.SUSPENDED
    }
}
