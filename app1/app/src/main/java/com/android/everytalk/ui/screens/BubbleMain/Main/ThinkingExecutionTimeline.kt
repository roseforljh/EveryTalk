package com.android.everytalk.ui.screens.BubbleMain.Main

import androidx.compose.ui.res.painterResource
import androidx.annotation.DrawableRes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.everytalk.R
import com.android.everytalk.data.network.TOOL_CALL_WRITING_STATUS
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.statecontroller.CONTEXT_COMPRESSION_FAILURE_PREFIX
import com.android.everytalk.statecontroller.CONTEXT_COMPRESSION_RUNNING_STATUS
import com.android.everytalk.statecontroller.AGENT_LOOP_CONTINUING_STATUS
import com.android.everytalk.ui.components.WebsiteIcon
import com.android.everytalk.util.web.linkHost
import com.android.everytalk.util.locale.localizeUiMessage

private const val MAX_VISIBLE_SOURCE_PILLS = 5
private val TimelineSearchBlue = Color(0xFF2563EB)
private val TimelineWebGreen = Color(0xFF00A86B)
private val TimelineToolOrange = Color(0xFFF06A00)
private val TimelineAgentTeal = Color(0xFF00897B)
private val TimelineReasoningPurple = Color(0xFF7C3AED)
private val TimelineErrorRed = Color(0xFFE5484D)

@Composable
internal fun localizedExecutionStatusText(status: String?): String? {
    val text = status?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val context = LocalContext.current
    return when {
        text == "等待首个响应" -> stringResource(R.string.thinking_waiting_first_response)
        text == "正在处理" -> stringResource(R.string.computer_action_working)
        text == TOOL_CALL_WRITING_STATUS -> stringResource(R.string.thinking_writing)
        text == "正在接收思考" -> stringResource(R.string.thinking_receiving)
        text == "已收到思考，等待正文" -> stringResource(R.string.thinking_received_waiting_content)
        text == "正在准备服务器" -> stringResource(R.string.thinking_preparing_server)
        text == "正在恢复远端任务" -> stringResource(R.string.thinking_restoring_remote_task)
        text == "等待服务器重连" -> stringResource(R.string.thinking_waiting_server_reconnect)
        text == "正在取消远端任务" -> stringResource(R.string.thinking_cancelling_remote_task)
        text == "远端取消失败，等待恢复确认" -> stringResource(R.string.thinking_remote_cancel_failed)
        text == "远端任务已取消" -> stringResource(R.string.thinking_remote_task_cancelled)
        text == AGENT_LOOP_CONTINUING_STATUS -> stringResource(R.string.thinking_analyzing_tool_result)
        text == "搜索网页" -> stringResource(R.string.thinking_searching_web)
        text.startsWith("搜索网页 · ") -> stringResource(
            R.string.thinking_searching_web_named,
            text.removePrefix("搜索网页 · "),
        )
        text == "读取网页" -> stringResource(R.string.thinking_reading_web)
        text.startsWith("读取网页 · ") -> stringResource(
            R.string.thinking_reading_web_named,
            text.removePrefix("读取网页 · "),
        )
        text == "MCP读取网页" -> stringResource(R.string.thinking_reading_web_with_mcp)
        text.startsWith("MCP读取网页 · ") -> stringResource(
            R.string.thinking_reading_web_with_mcp_named,
            text.removePrefix("MCP读取网页 · "),
        )
        text == "选择回答能力" -> stringResource(R.string.thinking_selecting_capability)
        text == "获取当前时间" -> stringResource(R.string.thinking_getting_current_time)
        text == "读取附件" -> stringResource(R.string.thinking_reading_attachment)
        text == "调用工具" -> stringResource(R.string.thinking_calling_tool)
        text.startsWith("调用工具 · ") -> stringResource(
            R.string.thinking_calling_tool_named,
            text.removePrefix("调用工具 · "),
        )
        text == "调用MCP" -> stringResource(R.string.thinking_calling_mcp)
        text.startsWith("调用MCP · ") -> stringResource(
            R.string.thinking_calling_mcp_named,
            text.removePrefix("调用MCP · "),
        )
        text.startsWith("工具结果 · ") -> stringResource(
            R.string.thinking_tool_result,
            text.removePrefix("工具结果 · "),
        )
        text == CONTEXT_COMPRESSION_RUNNING_STATUS -> stringResource(R.string.thinking_context_compressing)
        text.startsWith(CONTEXT_COMPRESSION_FAILURE_PREFIX) -> stringResource(
            R.string.thinking_context_compression_failed,
            context.localizeUiMessage(text.removePrefix(CONTEXT_COMPRESSION_FAILURE_PREFIX)),
        )
        else -> context.localizeUiMessage(text)
    }
}

private fun webLinkClick(uriHandler: UriHandler, href: String): (() -> Unit)? {
    val target = href.trim().takeIf { linkHost(it).isNotBlank() } ?: return null
    return {
        try {
            uriHandler.openUri(target)
        } catch (_: Exception) {
        }
    }
}

internal fun executionSummaryText(
    reasoningText: String,
    activityStatusText: String?,
    executionSteps: List<ExecutionStep>,
): String {
    val activity = activityStatusText?.trim().orEmpty()
    if (activity.isNotEmpty() && !isGenericExecutionStatus(activity)) return activity
    latestReasoningSummary(reasoningText)?.let { return it }
    if (activity.isNotEmpty()) return activity
    executionSteps.lastOrNull()?.let { step ->
        return listOfNotNull(step.title, step.labels.firstOrNull()).joinToString(" · ")
    }
    return "等待首个响应"
}

/** 只有仍在等待或执行中的状态才显示扫描高光，终态保留为静态可回看的文本。 */
internal fun isExecutionStatusActive(status: String?): Boolean {
    val text = status?.trim()?.takeIf(String::isNotEmpty) ?: return false
    return TERMINAL_EXECUTION_STATUS_PREFIXES.none(text::startsWith)
}

private val TERMINAL_EXECUTION_STATUS_PREFIXES = listOf(
    "远端任务已",
    "远端取消失败",
    "命令执行完成",
    "命令执行失败",
    "命令已取消",
    "已由新消息取代",
)

internal fun latestReasoningSummary(reasoningText: String): String? {
    val line = reasoningText
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("~~~") }
        .lastOrNull()
        ?: return null
    val withoutPrefix = line.trimStart { character ->
        character.isWhitespace() || character in charArrayOf('#', '-', '+', '>', '*')
    }
    return withoutPrefix
        .trim(' ', '*', '_', '`')
        .takeIf { it.isNotEmpty() }
}

internal fun isGenericExecutionStatus(text: String): Boolean = text in setOf(
    "等待首个响应",
    "正在接收思考",
    "已收到思考，等待正文",
)

internal data class ExecutionTimelineEntry(
    val step: ExecutionStep,
    val invocationCount: Int = 1,
)

internal sealed interface OrderedExecutionItem {
    data class Reasoning(val text: String) : OrderedExecutionItem
    /** 工具前后的模型旁白，按普通文本展示，不使用思考的灰色样式。 */
    data class Commentary(val text: String) : OrderedExecutionItem
    data class Step(val entry: ExecutionTimelineEntry) : OrderedExecutionItem
}

/**
 * 聊天主界面里的执行过程分段。
 *
 * 过程文字保持原始位置；两段过程文字之间连续发生的工具调用合并为一个工具段。
 * 这里只负责分组，不改写事件顺序，也不生成模型没有返回的思考内容。
 */
internal sealed interface ExecutionProcessSection {
    data class Narrative(
        val text: String,
        /** true 表示来自 Content 旁白，应使用普通正文颜色。 */
        val isCommentary: Boolean = false,
    ) : ExecutionProcessSection
    data class ToolGroup(val entries: List<ExecutionTimelineEntry>) : ExecutionProcessSection
}

private fun continuesSameTool(previous: ExecutionTimelineEntry?, step: ExecutionStep): Boolean {
    val previousToolName = previous?.step?.labels?.singleOrNull()?.trim().orEmpty()
    val toolName = step.labels.singleOrNull()?.trim().orEmpty()
    return step.type in setOf(ExecutionStepType.Tool, ExecutionStepType.Agent) &&
        previous?.step?.type == step.type &&
        toolName.isNotEmpty() &&
        toolName == previousToolName
}

private fun appendTimelineEntry(
    entries: List<ExecutionTimelineEntry>,
    incoming: ExecutionTimelineEntry,
): List<ExecutionTimelineEntry> {
    val previous = entries.lastOrNull()
    if (!continuesSameTool(previous, incoming.step)) return entries + incoming
    return entries.dropLast(1) + previous!!.copy(
        step = incoming.step,
        invocationCount = (previous.invocationCount.toLong() + incoming.invocationCount)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt(),
    )
}

internal fun executionTimelineEntries(steps: List<ExecutionStep>): List<ExecutionTimelineEntry> =
    buildList {
        steps.forEach { step ->
            val previous = lastOrNull()
            if (continuesSameTool(previous, step)) {
                val previousEntry = requireNotNull(previous)
                this[lastIndex] = ExecutionTimelineEntry(
                    step = step,
                    invocationCount = if (previousEntry.invocationCount == Int.MAX_VALUE) {
                        Int.MAX_VALUE
                    } else {
                        previousEntry.invocationCount + 1
                    },
                )
            } else {
                add(ExecutionTimelineEntry(step))
            }
        }
    }

/**
 * 新消息利用 reasoningBefore 还原真实顺序。旧消息没有该字段时采用“思考在前、工具在后”的兼容顺序。
 */
internal fun orderedExecutionItems(
    reasoningText: String,
    executionSteps: List<ExecutionStep>,
    executionTrace: List<ExecutionTraceEvent> = emptyList(),
): List<OrderedExecutionItem> = buildList {
    if (executionTrace.isNotEmpty()) {
        executionTrace.forEach { event ->
            when (event) {
                is ExecutionTraceEvent.Content -> event.text
                    .takeIf(String::isNotBlank)
                    ?.let { add(OrderedExecutionItem.Commentary(it)) }
                is ExecutionTraceEvent.UserMessageBoundary -> Unit
                is ExecutionTraceEvent.Reasoning -> event.text
                    .takeIf(String::isNotBlank)
                    ?.let { add(OrderedExecutionItem.Reasoning(it)) }
                is ExecutionTraceEvent.Tool -> add(
                    OrderedExecutionItem.Step(ExecutionTimelineEntry(event.step))
                )
            }
        }
        return@buildList
    }

    // 混合新旧步骤时顺序信息不完整，统一走旧消息兼容布局，避免伪造执行先后。
    val hasCompleteOrderMetadata = executionSteps.isNotEmpty() &&
        executionSteps.all { it.reasoningBefore != null }
    if (!hasCompleteOrderMetadata) {
        reasoningText.takeIf(String::isNotBlank)?.let { add(OrderedExecutionItem.Reasoning(it)) }
        executionTimelineEntries(executionSteps).forEach { add(OrderedExecutionItem.Step(it)) }
        return@buildList
    }

    fun appendStep(step: ExecutionStep) {
        val previous = lastOrNull() as? OrderedExecutionItem.Step
        if (continuesSameTool(previous?.entry, step)) {
            val previousItem = requireNotNull(previous)
            this[lastIndex] = OrderedExecutionItem.Step(
                ExecutionTimelineEntry(
                    step = step,
                    invocationCount = if (previousItem.entry.invocationCount == Int.MAX_VALUE) {
                        Int.MAX_VALUE
                    } else {
                        previousItem.entry.invocationCount + 1
                    },
                )
            )
        } else {
            add(OrderedExecutionItem.Step(ExecutionTimelineEntry(step)))
        }
    }

    executionSteps.forEach { step ->
        step.reasoningBefore
            ?.takeIf(String::isNotBlank)
            ?.let { add(OrderedExecutionItem.Reasoning(it)) }
        appendStep(step)
    }

    val recordedPrefix = buildString {
        executionSteps.forEach { step -> step.reasoningBefore?.let(::append) }
    }
    val trailingReasoning = when {
        recordedPrefix.isEmpty() -> reasoningText
        reasoningText.startsWith(recordedPrefix) -> reasoningText.drop(recordedPrefix.length)
        else -> ""
    }
    trailingReasoning
        .takeIf(String::isNotBlank)
        ?.let { add(OrderedExecutionItem.Reasoning(it)) }
}

/**
 * 把有序事件转换成主界面的多段执行过程。
 * 连续工具只合并容器，容器内部仍保留每个工具及其真实顺序。
 */
internal fun executionProcessSections(
    reasoningText: String,
    executionSteps: List<ExecutionStep>,
    executionTrace: List<ExecutionTraceEvent> = emptyList(),
): List<ExecutionProcessSection> = buildList {
    orderedExecutionItems(reasoningText, executionSteps, executionTrace).forEach { item ->
        when (item) {
            is OrderedExecutionItem.Reasoning -> {
                val previous = lastOrNull() as? ExecutionProcessSection.Narrative
                if (previous == null || previous.isCommentary) {
                    add(ExecutionProcessSection.Narrative(item.text))
                } else {
                    this[lastIndex] = previous.copy(text = previous.text + item.text)
                }
            }

            is OrderedExecutionItem.Commentary -> {
                val previous = lastOrNull() as? ExecutionProcessSection.Narrative
                if (previous == null || !previous.isCommentary) {
                    add(ExecutionProcessSection.Narrative(item.text, isCommentary = true))
                } else {
                    this[lastIndex] = previous.copy(text = previous.text + item.text)
                }
            }

            is OrderedExecutionItem.Step -> {
                val previous = lastOrNull() as? ExecutionProcessSection.ToolGroup
                if (previous == null) {
                    add(ExecutionProcessSection.ToolGroup(listOf(item.entry)))
                } else {
                    this[lastIndex] = previous.copy(
                        entries = appendTimelineEntry(previous.entries, item.entry),
                    )
                }
            }
        }
    }

}

@Composable
internal fun ThinkingExecutionTimeline(
    executionSteps: List<ExecutionStep>,
    executionTrace: List<ExecutionTraceEvent> = emptyList(),
    webSearchResults: List<WebSearchResult>,
    activityStatusText: String?,
    reasoningText: String,
    isReasoningActive: Boolean,
    messageIsError: Boolean,
    modifier: Modifier = Modifier,
    reasoningContent: @Composable (text: String, index: Int) -> Unit,
) {
    val localizedActivityStatusText = localizedExecutionStatusText(activityStatusText)
    val orderedItems = orderedExecutionItems(reasoningText, executionSteps, executionTrace)
    val pendingItemIndex = orderedItems.indexOfFirst { item ->
        item is OrderedExecutionItem.Step && !item.entry.step.completed
    }
    val hasSpecificActivity = !activityStatusText.isNullOrBlank() &&
        !isGenericExecutionStatus(activityStatusText)
    val activeReasoningItemIndex = orderedItems.lastIndex.takeIf { index ->
        isReasoningActive &&
            index >= 0 &&
            orderedItems[index] is OrderedExecutionItem.Reasoning &&
            pendingItemIndex < 0 &&
            !hasSpecificActivity
    } ?: -1
    val showStandaloneActivity = isReasoningActive && activeReasoningItemIndex < 0 && pendingItemIndex < 0
    val sourceItemIndex = orderedItems.indexOfLast { item ->
        item is OrderedExecutionItem.Step && item.entry.step.type == ExecutionStepType.Search
    }
    val nodeCount = orderedItems.size +
        (if (showStandaloneActivity) 1 else 0) +
        (if (webSearchResults.isNotEmpty() && sourceItemIndex < 0) 1 else 0) +
        (if (!isReasoningActive) 1 else 0)
    var nodeIndex = 0
    var toolNodeIndex = 0
    var reasoningNodeIndex = 0
    var expandedStepIds by remember { mutableStateOf(emptySet<String>()) }
    var collapsedReasoningIndices by remember { mutableStateOf(emptySet<Int>()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("reasoning-execution-timeline"),
    ) {
        orderedItems.forEachIndexed { itemIndex, item ->
            when (item) {
                is OrderedExecutionItem.Reasoning -> {
                    val currentReasoningIndex = reasoningNodeIndex++
                    val isExpanded = currentReasoningIndex !in collapsedReasoningIndices
                    TimelineNode(
                        icon = R.drawable.ic_gpt_sparkle,
                        iconTint = TimelineReasoningPurple,
                        title = stringResource(R.string.thinking_process),
                        active = itemIndex == activeReasoningItemIndex,
                        completed = itemIndex != activeReasoningItemIndex,
                        first = nodeIndex == 0,
                        last = nodeIndex == nodeCount - 1,
                        expanded = isExpanded,
                        onToggleExpand = {
                            collapsedReasoningIndices = if (isExpanded) {
                                collapsedReasoningIndices + currentReasoningIndex
                            } else {
                                collapsedReasoningIndices - currentReasoningIndex
                            }
                        },
                        modifier = Modifier.testTag(
                            "reasoning-execution-reasoning-step-$currentReasoningIndex"
                        ),
                    ) {
                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = expandVertically(
                                animationSpec = tween(220, easing = FastOutSlowInEasing),
                            ) + fadeIn(animationSpec = tween(160)),
                            exit = shrinkVertically(
                                animationSpec = tween(200, easing = FastOutSlowInEasing),
                            ) + fadeOut(animationSpec = tween(120)),
                        ) {
                            ReasoningNodeCard {
                                reasoningContent(item.text, currentReasoningIndex)
                            }
                        }
                    }
                }
                is OrderedExecutionItem.Commentary -> {
                    val currentReasoningIndex = reasoningNodeIndex++
                    val isExpanded = currentReasoningIndex !in collapsedReasoningIndices
                    TimelineNode(
                        icon = R.drawable.ic_gpt_sparkle,
                        iconTint = MaterialTheme.colorScheme.onSurface,
                        title = stringResource(R.string.thinking_process),
                        active = false,
                        completed = true,
                        first = nodeIndex == 0,
                        last = nodeIndex == nodeCount - 1,
                        expanded = isExpanded,
                        onToggleExpand = {
                            collapsedReasoningIndices = if (isExpanded) {
                                collapsedReasoningIndices + currentReasoningIndex
                            } else {
                                collapsedReasoningIndices - currentReasoningIndex
                            }
                        },
                        modifier = Modifier.testTag(
                            "reasoning-execution-commentary-step-$currentReasoningIndex"
                        ),
                    ) {
                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = expandVertically(
                                animationSpec = tween(220, easing = FastOutSlowInEasing),
                            ) + fadeIn(animationSpec = tween(160)),
                            exit = shrinkVertically(
                                animationSpec = tween(200, easing = FastOutSlowInEasing),
                            ) + fadeOut(animationSpec = tween(120)),
                        ) {
                            ReasoningNodeCard {
                                reasoningContent(item.text, currentReasoningIndex)
                            }
                        }
                    }
                }
                is OrderedExecutionItem.Step -> {
                    val entry = item.entry
                    val step = entry.step
                    val currentToolIndex = toolNodeIndex++
                    val canExpand = step.type in setOf(ExecutionStepType.Tool, ExecutionStepType.Agent) &&
                        (step.labels.isNotEmpty() || !step.detail.isNullOrBlank() || !step.resultPreview.isNullOrBlank())
                    val isStepExpanded = step.id in expandedStepIds || (step.labels.isEmpty() && step.isError)
                    val toggleStepExpanded: () -> Unit = {
                        expandedStepIds = if (step.id in expandedStepIds) {
                            expandedStepIds - step.id
                        } else {
                            expandedStepIds + step.id
                        }
                    }
                    TimelineNode(
                        icon = stepIcon(step.type),
                        iconTint = if (step.isError) TimelineErrorRed else stepIconTint(step.type),
                        title = localizedExecutionStatusText(step.title) ?: step.title,
                        active = isReasoningActive && itemIndex == pendingItemIndex,
                        completed = step.completed,
                        first = nodeIndex == 0,
                        last = nodeIndex == nodeCount - 1,
                        expanded = if (canExpand) isStepExpanded else null,
                        onToggleExpand = if (canExpand) toggleStepExpanded else null,
                        modifier = Modifier.testTag("reasoning-execution-step-$currentToolIndex"),
                    ) {
                        ExecutionLabels(
                            step = step,
                            invocationCount = entry.invocationCount,
                            expanded = isStepExpanded,
                            onToggleExpanded = toggleStepExpanded,
                        )
                        if (itemIndex == sourceItemIndex && webSearchResults.isNotEmpty()) {
                            WebsiteLabels(webSearchResults)
                        }
                    }
                }
            }
            nodeIndex++
        }

        if (showStandaloneActivity) {
            TimelineNode(
                icon = R.drawable.ic_gpt_sparkle,
                iconTint = TimelineReasoningPurple,
                title = localizedActivityStatusText
                    ?: stringResource(R.string.thinking_waiting_first_response),
                active = true,
                completed = false,
                first = nodeIndex == 0,
                last = nodeIndex == nodeCount - 1,
                modifier = Modifier.testTag("reasoning-execution-live-step"),
            )
            nodeIndex++
        }

        if (webSearchResults.isNotEmpty() && sourceItemIndex < 0) {
            TimelineNode(
                icon = R.drawable.ic_globe,
                iconTint = TimelineWebGreen,
                title = stringResource(R.string.thinking_browsed_websites),
                active = false,
                completed = true,
                first = nodeIndex == 0,
                last = nodeIndex == nodeCount - 1,
                modifier = Modifier.testTag("reasoning-execution-sources-step"),
            ) {
                WebsiteLabels(webSearchResults)
            }
            nodeIndex++
        }

        if (!isReasoningActive) {
            TimelineNode(
                icon = if (messageIsError) R.drawable.ic_close_bold else R.drawable.ic_check_circle,
                iconTint = if (messageIsError) {
                    TimelineErrorRed
                } else {
                    TimelineWebGreen
                },
                title = if (messageIsError) {
                    stringResource(R.string.thinking_execution_failed)
                } else {
                    stringResource(R.string.thinking_complete)
                },
                active = false,
                completed = true,
                first = nodeIndex == 0,
                last = true,
                modifier = Modifier.testTag("reasoning-execution-finish-step"),
            ) {
                // 复用终态节点正文，长错误允许换行和复制，不再仅对压缩错误显示原因。
                if (messageIsError && !localizedActivityStatusText.isNullOrBlank()) {
                    val flatDarkStyle = MaterialTheme.colorScheme.background.luminance() < 0.5f
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = if (flatDarkStyle) 0.12f else 0.08f),
                        border = BorderStroke(
                            width = 0.75.dp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.28f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = localizedActivityStatusText,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .testTag("reasoning-execution-failure-reason"),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 思考段卡片容器，提供柔和圆角底色与边线，保持顶部紧凑间距以对齐时间线标题。 */
@Composable
private fun ReasoningNodeCard(
    content: @Composable () -> Unit,
) {
    val flatDarkStyle = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (flatDarkStyle) 0.045f else 0.03f),
        border = BorderStroke(
            width = 0.75.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (flatDarkStyle) 0.35f else 0.45f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun TimelineNode(
    @DrawableRes icon: Int,
    iconTint: Color,
    title: String,
    active: Boolean,
    completed: Boolean,
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    expanded: Boolean? = null,
    onToggleExpand: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    val flatDarkStyle = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val density = androidx.compose.ui.platform.LocalDensity.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded == true) 180f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "timelineNodeChevronRotation",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val x = with(density) { 12.dp.toPx() }
                val markerTop = with(density) { 2.dp.toPx() }
                val markerBottom = with(density) { 26.dp.toPx() }
                if (!first) {
                    drawLine(
                        color = lineColor,
                        start = androidx.compose.ui.geometry.Offset(x, 0f),
                        end = androidx.compose.ui.geometry.Offset(x, markerTop),
                        strokeWidth = with(density) { 1.25.dp.toPx() },
                    )
                }
                if (!last) {
                    drawLine(
                        color = lineColor,
                        start = androidx.compose.ui.geometry.Offset(x, markerBottom),
                        end = androidx.compose.ui.geometry.Offset(x, size.height),
                        strokeWidth = with(density) { 1.25.dp.toPx() },
                    )
                }
            },
    ) {
        Box(
            modifier = Modifier
                .width(24.dp)
                .padding(top = 2.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val badgeBg = if (active || completed) {
                iconTint.copy(alpha = if (flatDarkStyle) 0.16f else 0.11f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(badgeBg),
                contentAlignment = Alignment.Center,
            ) {
                TimelineNodeIcon(
                    icon = icon,
                    tint = iconTint,
                    active = active,
                    completed = completed,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, bottom = if (last) 4.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val titleRowModifier = if (onToggleExpand != null) {
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .pointerInput(onToggleExpand) {
                        detectTapGestures { onToggleExpand() }
                    }
                    .padding(vertical = 2.dp)
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            }
            Row(
                modifier = titleRowModifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    if (active) {
                        ScanningHighlightText(
                            text = title,
                            textColor = MaterialTheme.colorScheme.onSurface,
                            useSmallStyle = false,
                            modifier = Modifier.testTag("reasoning-active-step-title"),
                        )
                    } else {
                        Text(
                            text = title,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (expanded != null && onToggleExpand != null) {
                    Icon(
                        painter = painterResource(R.drawable.ic_gpt_chevron_down_md),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        modifier = Modifier
                            .size(15.dp)
                            .graphicsLayer { rotationZ = chevronRotation },
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun TimelineNodeIcon(
    @DrawableRes icon: Int,
    tint: Color,
    active: Boolean,
    completed: Boolean,
) {
    val resolvedTint = if (active || completed) {
        tint
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    if (active) {
        ActiveTimelineNodeIcon(icon = icon, tint = resolvedTint)
    } else {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = resolvedTint,
            modifier = Modifier
                .size(15.dp)
                .testTag("reasoning-timeline-icon-static"),
        )
    }
}

@Composable
private fun ActiveTimelineNodeIcon(
    @DrawableRes icon: Int,
    tint: Color,
) {
    val transition = rememberInfiniteTransition(label = "执行步骤图标动画")
    val pulseScale by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1_200,
                easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f),
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "执行步骤图标缩放",
    )
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(15.dp)
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            }
            .testTag("reasoning-timeline-icon-active"),
    )
}

internal fun stepIcon(type: ExecutionStepType): Int = when (type) {
    ExecutionStepType.Search -> R.drawable.ic_search
    ExecutionStepType.Web -> R.drawable.ic_globe
    ExecutionStepType.Tool -> R.drawable.ic_gpt_wrench
    ExecutionStepType.Agent -> R.drawable.ic_gpt_terminal
}

internal fun stepIconTint(type: ExecutionStepType): Color = when (type) {
    ExecutionStepType.Search -> TimelineSearchBlue
    ExecutionStepType.Web -> TimelineWebGreen
    ExecutionStepType.Tool -> TimelineToolOrange
    ExecutionStepType.Agent -> TimelineAgentTeal
}

@Composable
private fun ExecutionLabels(
    step: ExecutionStep,
    invocationCount: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    if (step.labels.isEmpty()) {
        // 缺少工具标签的失败记录仍需显示服务端返回的原因。
        if (step.isError || expanded) ExecutionStepDetails(step)
        return
    }
    val uriHandler = LocalUriHandler.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        step.labels.forEachIndexed { index, label ->
            CapsuleLabel(
                text = if (step.type == ExecutionStepType.Web) linkHost(label).ifBlank { label } else label,
                icon = when (step.type) {
                    ExecutionStepType.Search -> R.drawable.ic_search
                    ExecutionStepType.Web -> R.drawable.ic_globe
                    ExecutionStepType.Tool -> R.drawable.ic_gpt_wrench
                    ExecutionStepType.Agent -> R.drawable.ic_gpt_terminal
                },
                iconTint = stepIconTint(step.type),
                onClick = when (step.type) {
                    ExecutionStepType.Web -> webLinkClick(uriHandler, label)
                    ExecutionStepType.Tool,
                    ExecutionStepType.Agent,
                    -> onToggleExpanded
                    else -> null
                },
                trailingText = if (
                    step.type in setOf(ExecutionStepType.Tool, ExecutionStepType.Agent) && invocationCount > 1
                ) {
                    "x $invocationCount"
                } else {
                    null
                },
                modifier = Modifier.testTag("reasoning-execution-label-$index"),
            )
        }
    }
    AnimatedVisibility(
        visible = expanded && step.type in setOf(ExecutionStepType.Tool, ExecutionStepType.Agent),
        enter = expandVertically(
            animationSpec = tween(200, easing = FastOutSlowInEasing),
        ) + fadeIn(animationSpec = tween(150)),
        exit = shrinkVertically(
            animationSpec = tween(180, easing = FastOutSlowInEasing),
        ) + fadeOut(animationSpec = tween(120)),
    ) {
        ExecutionStepDetails(step)
    }
}

@Composable
private fun ExecutionStepDetails(step: ExecutionStep) {
    val detail = step.detail?.trim().takeUnless { it.isNullOrEmpty() }
        ?: step.labels.joinToString(" ").trim()
    if (detail.isEmpty() && step.resultPreview.isNullOrBlank()) return
    val flatDarkStyle = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val statusColor = when {
        step.isError -> TimelineErrorRed
        step.completed -> TimelineWebGreen
        else -> TimelineToolOrange
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (flatDarkStyle) 0.055f else 0.035f),
        border = BorderStroke(
            width = 0.75.dp,
            color = if (step.isError) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .testTag("reasoning-execution-detail-${step.id}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Text(
                    text = stringResource(
                        if (step.isError) {
                            R.string.thinking_execution_failed
                        } else if (step.completed) {
                            R.string.thinking_execution_completed
                        } else {
                            R.string.thinking_execution_running
                        },
                    ),
                    color = if (step.isError) statusColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.weight(1f))
                step.durationMs?.let { duration ->
                    Text(
                        text = stringResource(R.string.thinking_elapsed_duration, localizedExecutionDuration(duration)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            step.resultPreview?.takeIf(String::isNotBlank)?.let { result ->
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (flatDarkStyle) 0.06f else 0.04f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = result,
                            color = if (step.isError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("reasoning-execution-result-${step.id}"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WebsiteLabels(results: List<WebSearchResult>) {
    val sources = results.distinctBy { linkHost(it.href).ifBlank { it.href } }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("reasoning-website-labels"),
    ) {
        sources.take(MAX_VISIBLE_SOURCE_PILLS).forEachIndexed { index, source ->
            WebsiteCapsule(
                source = source,
                modifier = Modifier.testTag("reasoning-website-label-$index"),
            )
        }
        if (sources.size > MAX_VISIBLE_SOURCE_PILLS) {
            val remainingCount = sources.size - MAX_VISIBLE_SOURCE_PILLS
            CapsuleLabel(
                text = pluralStringResource(
                    R.plurals.thinking_remaining_sources,
                    remainingCount,
                    remainingCount,
                ),
            )
        }
    }
}

@Composable
private fun CapsuleLabel(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailingText: String? = null,
) {
    CapsuleSurface(
        modifier = modifier,
        maxWidth = 280.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = iconTint ?: LocalContentColor.current,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (trailingText == null) {
                    Modifier
                } else {
                    Modifier.weight(1f, fill = false)
                },
            )
            trailingText?.let { trailing ->
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CapsuleSurface(
    modifier: Modifier,
    maxWidth: Dp,
    onClick: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val surfaceModifier = modifier.widthIn(max = maxWidth)
    val flatDarkStyle = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(999.dp)
    val color = if (flatDarkStyle) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    }
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val border = BorderStroke(
        width = 0.75.dp,
        color = if (flatDarkStyle) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        },
    )
    if (onClick == null) {
        Surface(
            modifier = surfaceModifier,
            shape = shape,
            color = color,
            contentColor = contentColor,
            border = border,
            content = content,
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = surfaceModifier,
            shape = shape,
            color = color,
            contentColor = contentColor,
            border = border,
            content = content,
        )
    }
}

@Composable
private fun WebsiteCapsule(
    source: WebSearchResult,
    modifier: Modifier = Modifier,
) {
    val flatDarkStyle = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val host = linkHost(source.href).ifBlank { source.title.ifBlank { source.href } }
    val onClick = webLinkClick(LocalUriHandler.current, source.href)
    CapsuleSurface(
        modifier = modifier,
        maxWidth = 250.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(
                        if (flatDarkStyle) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                WebsiteIcon(
                    href = source.href,
                    tint = TimelineWebGreen,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = host,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
