package com.android.everytalk.ui.screens.BubbleMain.Main

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.statecontroller.CONTEXT_COMPRESSION_FAILURE_PREFIX
import com.android.everytalk.statecontroller.CONTEXT_COMPRESSION_RUNNING_STATUS
import com.android.everytalk.statecontroller.AGENT_LOOP_CONTINUING_STATUS
import com.android.everytalk.util.web.linkFaviconUrl
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
        text == "正在接收思考" -> stringResource(R.string.thinking_receiving)
        text == "已收到思考，等待正文" -> stringResource(R.string.thinking_received_waiting_content)
        text == "正在准备服务器" -> stringResource(R.string.thinking_preparing_server)
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
        else -> text
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

private fun isGenericExecutionStatus(text: String): Boolean = text in setOf(
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
    data class Step(val entry: ExecutionTimelineEntry) : OrderedExecutionItem
}

private fun continuesSameTool(previous: ExecutionTimelineEntry?, step: ExecutionStep): Boolean {
    val previousToolName = previous?.step?.labels?.singleOrNull()?.trim().orEmpty()
    val toolName = step.labels.singleOrNull()?.trim().orEmpty()
    return step.type in setOf(ExecutionStepType.Tool, ExecutionStepType.Agent) &&
        previous?.step?.type == step.type &&
        toolName.isNotEmpty() &&
        toolName == previousToolName
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("reasoning-execution-timeline"),
    ) {
        orderedItems.forEachIndexed { itemIndex, item ->
            when (item) {
                is OrderedExecutionItem.Reasoning -> {
                    val currentReasoningIndex = reasoningNodeIndex++
                    TimelineNode(
                        icon = Icons.Outlined.AutoAwesome,
                        iconTint = TimelineReasoningPurple,
                        title = stringResource(R.string.thinking_process),
                        active = itemIndex == activeReasoningItemIndex,
                        completed = itemIndex != activeReasoningItemIndex,
                        first = nodeIndex == 0,
                        last = nodeIndex == nodeCount - 1,
                        modifier = Modifier.testTag(
                            "reasoning-execution-reasoning-step-$currentReasoningIndex"
                        ),
                    ) {
                        reasoningContent(item.text, currentReasoningIndex)
                    }
                }
                is OrderedExecutionItem.Step -> {
                    val entry = item.entry
                    val step = entry.step
                    val currentToolIndex = toolNodeIndex++
                    TimelineNode(
                        icon = stepIcon(step.type),
                        iconTint = stepIconTint(step.type),
                        title = localizedExecutionStatusText(step.title) ?: step.title,
                        active = isReasoningActive && itemIndex == pendingItemIndex,
                        completed = step.completed,
                        first = nodeIndex == 0,
                        last = nodeIndex == nodeCount - 1,
                        modifier = Modifier.testTag("reasoning-execution-step-$currentToolIndex"),
                    ) {
                        ExecutionLabels(step, entry.invocationCount)
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
                icon = Icons.Outlined.AutoAwesome,
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
                icon = Icons.Filled.Public,
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
                icon = Icons.Filled.CheckCircle,
                iconTint = if (messageIsError) {
                    TimelineErrorRed
                } else {
                    TimelineWebGreen
                },
                title = if (messageIsError) {
                    localizedActivityStatusText
                        ?.takeIf { activityStatusText?.startsWith(CONTEXT_COMPRESSION_FAILURE_PREFIX) == true }
                        ?: stringResource(R.string.thinking_execution_failed)
                } else {
                    stringResource(R.string.thinking_complete)
                },
                active = false,
                completed = !messageIsError,
                first = nodeIndex == 0,
                last = true,
                modifier = Modifier.testTag("reasoning-execution-finish-step"),
            )
        }
    }
}

@Composable
private fun TimelineNode(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    active: Boolean,
    completed: Boolean,
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
    val density = androidx.compose.ui.platform.LocalDensity.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val x = with(density) { 11.dp.toPx() }
                val markerCenter = with(density) { 11.dp.toPx() }
                drawLine(
                    color = lineColor,
                    start = androidx.compose.ui.geometry.Offset(x, if (first) markerCenter else 0f),
                    end = androidx.compose.ui.geometry.Offset(x, if (last) markerCenter else size.height),
                    strokeWidth = with(density) { 1.dp.toPx() },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .width(22.dp)
                .padding(top = 1.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
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
                .padding(start = 12.dp, bottom = if (last) 4.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
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
            content()
        }
    }
}

@Composable
private fun TimelineNodeIcon(
    icon: ImageVector,
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
            imageVector = icon,
            contentDescription = null,
            tint = resolvedTint,
            modifier = Modifier
                .size(20.dp)
                .testTag("reasoning-timeline-icon-static"),
        )
    }
}

@Composable
private fun ActiveTimelineNodeIcon(
    icon: ImageVector,
    tint: Color,
) {
    val transition = rememberInfiniteTransition(label = "执行步骤图标动画")
    val pulseScale by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
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
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(20.dp)
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            }
            .testTag("reasoning-timeline-icon-active"),
    )
}

internal fun stepIcon(type: ExecutionStepType): ImageVector = when (type) {
    ExecutionStepType.Search -> Icons.Filled.Search
    ExecutionStepType.Web -> Icons.Filled.Public
    ExecutionStepType.Tool -> Icons.Filled.Build
    ExecutionStepType.Agent -> Icons.Filled.Terminal
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
) {
    if (step.labels.isEmpty()) return
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
                    ExecutionStepType.Search -> Icons.Filled.Search
                    ExecutionStepType.Web -> Icons.Filled.Public
                    ExecutionStepType.Tool -> Icons.Filled.Build
                    ExecutionStepType.Agent -> Icons.Filled.Terminal
                },
                iconTint = stepIconTint(step.type),
                onClick = if (step.type == ExecutionStepType.Web) {
                    webLinkClick(uriHandler, label)
                } else {
                    null
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
    icon: ImageVector? = null,
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
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint ?: LocalContentColor.current,
                    modifier = Modifier.size(16.dp),
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
    val color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val border = BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
    )
    if (onClick == null) {
        Surface(
            modifier = surfaceModifier,
            shape = CircleShape,
            color = color,
            contentColor = contentColor,
            border = border,
            content = content,
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = surfaceModifier,
            shape = CircleShape,
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
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Public,
                    contentDescription = null,
                    tint = TimelineWebGreen,
                    modifier = Modifier.size(14.dp),
                )
                val faviconUrl = linkFaviconUrl(source.href)
                if (faviconUrl.isNotBlank()) {
                    AsyncImage(
                        model = faviconUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape),
                    )
                }
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
