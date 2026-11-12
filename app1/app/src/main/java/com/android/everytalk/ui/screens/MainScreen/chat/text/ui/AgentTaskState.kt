package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.agent.AgentRunStatus
import com.android.everytalk.data.agent.AgentTaskTarget
import com.android.everytalk.data.computer.PendingComputerToolApproval
import com.android.everytalk.data.computer.hostConfirmationRequest

internal enum class AgentTaskPhase {
    PLANNING, WAITING, RUNNING, REVIEWING, APPROVAL, COMPLETED, FAILED, STOPPED, UNUSED,
}

internal data class AgentTaskItem(
    /** 抽屉只展示服务器子任务或 Fork 分支任务，直接使用稳定 ID，切换标签不会改变执行目标。 */
    val computerId: String,
    val name: String,
    val phase: AgentTaskPhase,
    val trace: List<ExecutionTraceEvent>,
    /** 用于在抽屉时间线中渲染 UserMessageBoundary 对应的用户提问气泡与引用标签。 */
    val userMessages: Map<String, Message> = emptyMap(),
    val isForkTask: Boolean = false,
)

internal const val FORK_TASK_ID = "fork"

/**
 * 把 Fork 分支会话的消息摊成抽屉时间线：用户提问作为边界，AI 回复沿用自身的执行轨迹
 * （思考、工具、正文），流式期间轨迹会随缓冲刷新实时增长。
 */
internal fun buildForkTaskItem(messages: List<Message>, runningMessageId: String?): AgentTaskItem {
    val boundariesInAiTrace = messages.asSequence()
        .filter { it.sender == Sender.AI }
        .flatMap { it.executionTrace.asSequence() }
        .filterIsInstance<ExecutionTraceEvent.UserMessageBoundary>()
        .mapTo(hashSetOf()) { it.messageId }
    val trace = mutableListOf<ExecutionTraceEvent>()
    val users = mutableMapOf<String, Message>()
    messages.forEach { message ->
        when (message.sender) {
            Sender.User -> {
                users[message.id] = message
                // Agent 中途追问的边界已在 AI 轨迹里按真实顺序出现，不再重复插入。
                if (message.id !in boundariesInAiTrace) trace += ExecutionTraceEvent.UserMessageBoundary(message.id)
            }
            Sender.AI -> {
                val events = message.executionTrace
                if (events.none { it is ExecutionTraceEvent.Reasoning } && !message.reasoning.isNullOrBlank()) {
                    trace += ExecutionTraceEvent.Reasoning(message.reasoning)
                }
                trace += events
                if (events.none { it is ExecutionTraceEvent.Content } && message.text.isNotBlank()) {
                    trace += ExecutionTraceEvent.Content(message.text)
                }
            }
            else -> Unit
        }
    }
    val phase = when {
        runningMessageId != null -> AgentTaskPhase.RUNNING
        messages.lastOrNull { it.sender == Sender.AI }?.isError == true -> AgentTaskPhase.FAILED
        users.isEmpty() -> AgentTaskPhase.WAITING
        else -> AgentTaskPhase.COMPLETED
    }
    return AgentTaskItem(
        computerId = FORK_TASK_ID,
        name = "Fork",
        phase = phase,
        trace = trace,
        userMessages = users,
        isForkTask = true,
    )
}

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
