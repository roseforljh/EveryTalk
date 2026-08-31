package com.android.everytalk.data.agent

data class ContinuationCheckpoint(
    val requestHash: String,
    val preconditionFingerprint: String,
    val resourceEpoch: Long,
    val executionGeneration: Long,
    val bindingGeneration: Long,
)

sealed interface AgentContinuation {
    data class RetryTool(val checkpoint: ContinuationCheckpoint) : AgentContinuation
    data class ContinueTool(val toolExecutionRef: String) : AgentContinuation
    data class ContinueExecution(val executionId: String) : AgentContinuation
    data class ContinuePty(val bindingRef: BindingRef) : AgentContinuation
    data class ResumeAgentLoop(val resultCode: String) : AgentContinuation
    data class VerifyThenResume(val verificationPlanId: String) : AgentContinuation
    data class ReplanRequired(val reasonCode: String) : AgentContinuation
}

/** RETRY_TOOL 的任一世界状态前置条件变化都必须重新规划。 */
fun AgentContinuation.RetryTool.validate(current: ContinuationCheckpoint): AgentContinuation =
    if (checkpoint == current) this else AgentContinuation.ReplanRequired("PRECONDITION_CHANGED")
