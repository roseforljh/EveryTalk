package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.agent.AgentRunStatus
import com.android.everytalk.data.agent.AgentTaskTarget
import com.android.everytalk.data.computer.PendingComputerToolApproval
import com.android.everytalk.data.computer.hostConfirmationRequest

internal enum class AgentTaskPhase {
    PLANNING, WAITING, RUNNING, REVIEWING, APPROVAL, COMPLETED, FAILED, STOPPED, UNUSED,
}

internal data class AgentTaskItem(
    /** 抽屉只展示服务器子任务，直接使用稳定 ID，切换标签不会改变执行目标。 */
    val computerId: String,
    val name: String,
    val phase: AgentTaskPhase,
    val trace: List<ExecutionTraceEvent>,
)

/** 只把有对应服务器且能生成审批卡的申请交给抽屉，其他申请继续使用原聊天入口。 */
internal fun taskApprovalsForDisplay(
    tasks: List<AgentTaskItem>,
    approvals: List<PendingComputerToolApproval>,
): List<PendingComputerToolApproval> = if (tasks.size < 2) emptyList() else approvals.filter { pending ->
    pending.hostConfirmationRequest() != null && tasks.any { it.computerId == pending.request.context.computerId }
}

/**
 * 从已有 Run 和有序执行记录生成只读视图，不创建第二套任务状态机。
 * 工具执行结束而模型仍在分析时显示等待后续安排，不能提前宣布整台服务器的任务完成。
 */
internal fun buildAgentTaskItems(
    status: String,
    message: Message?,
    targets: List<AgentTaskTarget>,
    approvals: List<PendingComputerToolApproval>,
    waitingComputerIds: Set<String> = emptySet(),
    fallbackTrace: List<ExecutionTraceEvent> = emptyList(),
): List<AgentTaskItem> {
    val terminal = status in setOf(AgentRunStatus.COMPLETED.name, AgentRunStatus.FAILED.name,
        AgentRunStatus.CANCELLED.name, AgentRunStatus.INTERRUPTED.name)
    val trace = message?.executionTrace.orEmpty().ifEmpty {
        message?.executionSteps.orEmpty().map { ExecutionTraceEvent.Tool(it) }
    }.ifEmpty { fallbackTrace }
    return targets.map { target ->
        val serverTrace = trace.filterIsInstance<ExecutionTraceEvent.Tool>().filter { event ->
            event.step.computerId == target.computerId || targets.size == 1 && event.step.computerId == null
        }
        val steps: List<ExecutionStep> = serverTrace.map { it.step }
        val phase = when {
            approvals.any { it.request.context.computerId == target.computerId } || target.computerId in waitingComputerIds -> AgentTaskPhase.APPROVAL
            terminal && status != AgentRunStatus.COMPLETED.name ->
                if (steps.lastOrNull()?.isError == true) AgentTaskPhase.FAILED else AgentTaskPhase.STOPPED
            steps.isEmpty() -> if (terminal) AgentTaskPhase.UNUSED else AgentTaskPhase.WAITING
            steps.last().isError -> AgentTaskPhase.FAILED
            terminal -> AgentTaskPhase.COMPLETED
            steps.any { it.started && !it.completed } -> AgentTaskPhase.RUNNING
            steps.any { !it.completed } -> AgentTaskPhase.WAITING
            else -> AgentTaskPhase.REVIEWING
        }
        AgentTaskItem(target.computerId, target.displayName, phase, serverTrace)
    }
}
