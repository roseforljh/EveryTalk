package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.agent.AgentRunStatus
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.ui.components.MarkdownPart

/**
 * 在主线程应用一次 Room 对账结果。读取期间切换会话、收到新内容或点击停止时，
 * baseline 已经过期，必须丢弃，不能用旧快照覆盖新的界面事实。
 * 活跃执行器仍负责实时增量；只有执行器退出后才以持久记录补齐完整结果。
 */
internal fun ViewModelStateHolder.reconcileAgentRun(
    run: AgentRunEntity,
    trace: List<ExecutionTraceEvent>,
    baseline: Message,
    hasActiveJob: Boolean,
): Boolean {
    if (_currentConversationId.value != run.sessionId) return false
    val index = messages.indexOfFirst { it.id == run.visibleAssistantMessageId }
    if (index < 0 || messages[index] != baseline) return false
    val status = runCatching { AgentRunStatus.valueOf(run.status) }.getOrNull() ?: return false
    if (hasActiveJob && isActiveAgentUiStatus(status)) {
        attachTextAgentUi(run.visibleAssistantMessageId)
        return true
    }

    // 本地执行阶段没有执行器时，只显示等待核对，不能把持久中间态误报为仍在执行。
    // 远端执行、模型重连和审批等待各有自己的恢复入口，保留它们的真实等待状态。
    val projectedStatus = when (status) {
        AgentRunStatus.CREATED, AgentRunStatus.PREPARING_CONTEXT,
        AgentRunStatus.COMPACTING_CONTEXT, AgentRunStatus.WAITING_MODEL, AgentRunStatus.STREAMING_MODEL,
        AgentRunStatus.CHECKING_PERMISSION, AgentRunStatus.EXECUTING_TOOL,
        AgentRunStatus.PERSISTING_RESULT, AgentRunStatus.RETRYING,
        -> AgentRunStatus.INTERRUPTED
        else -> status
    }
    val replaceAttempt = status == AgentRunStatus.MODEL_CONTINUATION_PENDING || status == AgentRunStatus.RETRYING
    // 停止和崩溃恢复已封存部分正文，晚到的 Agent 账本只能更新本地状态，不能覆盖正式内容。
    val hasTranscript = !baseline.isIncomplete && (trace.isNotEmpty() || replaceAttempt)
    val executionStatus = restoredAgentExecutionStatus(projectedStatus, run.terminalReason)
        .let { restored ->
            // 实时错误包含服务商的原始说明，账本可能只有 HTTP 状态等摘要，恢复时优先保留详细说明。
            if (status == AgentRunStatus.FAILED && baseline.isError) {
                baseline.executionStatus?.takeIf {
                    it.isNotBlank() && it != "任务失败" && it != "执行失败"
                } ?: restored
            } else restored
        }
    val transcriptText = if (hasTranscript) {
        trace.filterIsInstance<ExecutionTraceEvent.Content>().joinToString("") { it.text }
    } else baseline.text
    // 没有执行过程的请求也必须能看到原因；从账本重建正文时，错误说明不会存在于内容事件中。
    val text = if (status == AgentRunStatus.FAILED && !executionStatus.isNullOrBlank() &&
        !transcriptText.contains(executionStatus)
    ) {
        listOf(transcriptText, "⚠️ $executionStatus").filter(String::isNotBlank).joinToString("\n\n")
    } else transcriptText
    val reasoning = if (hasTranscript) {
        trace.filterIsInstance<ExecutionTraceEvent.Reasoning>().joinToString("") { it.text }.takeIf(String::isNotBlank)
    } else baseline.reasoning
    messages[index] = baseline.copy(
        text = text,
        reasoning = reasoning,
        parts = if (hasTranscript) {
            text.takeIf(String::isNotBlank)
                ?.let { listOf(MarkdownPart.Text(id = "text_0", content = it)) }.orEmpty()
        } else baseline.parts,
        contentStarted = text.isNotBlank(),
        executionTrace = if (hasTranscript) trace else baseline.executionTrace,
        executionSteps = if (hasTranscript) {
            trace.filterIsInstance<ExecutionTraceEvent.Tool>().map { it.step }
        } else baseline.executionSteps,
        executionStatus = executionStatus,
        isError = baseline.isError || status == AgentRunStatus.FAILED,
        // 等待恢复不是终态。写入结束时间会触发持久层的过期任务清理，误取消待恢复任务。
        executionFinishedAt = run.updatedAt.takeIf {
            status in setOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.CANCELLED)
        },
    )
    if (isActiveAgentUiStatus(projectedStatus)) {
        attachTextAgentUi(run.visibleAssistantMessageId)
    } else {
        detachTextAgentUi(run.visibleAssistantMessageId)
    }
    return true
}
