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
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.util.web.linkFaviconUrl
import com.android.everytalk.util.web.linkHost

private const val MAX_VISIBLE_SOURCE_PILLS = 5
private val TimelineSearchBlue = Color(0xFF2563EB)
private val TimelineWebGreen = Color(0xFF00A86B)
private val TimelineToolOrange = Color(0xFFF06A00)
private val TimelineReasoningPurple = Color(0xFF7C3AED)
private val TimelineErrorRed = Color(0xFFE5484D)

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

private fun latestReasoningSummary(reasoningText: String): String? {
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

@Composable
internal fun ThinkingExecutionTimeline(
    executionSteps: List<ExecutionStep>,
    webSearchResults: List<WebSearchResult>,
    activityStatusText: String?,
    reasoningText: String,
    isReasoningActive: Boolean,
    messageIsError: Boolean,
    modifier: Modifier = Modifier,
    reasoningContent: @Composable () -> Unit,
) {
    val hasReasoning = reasoningText.isNotBlank()
    val pendingStepIndex = executionSteps.indexOfLast { !it.completed }
    val reasoningIsActive = isReasoningActive && hasReasoning &&
        (pendingStepIndex < 0 || isGenericExecutionStatus(activityStatusText.orEmpty()))
    val showStandaloneActivity = isReasoningActive && !reasoningIsActive && pendingStepIndex < 0
    val sourceStepIndex = executionSteps.indexOfLast { it.type == ExecutionStepType.Search }
    val nodeCount = executionSteps.size +
        (if (showStandaloneActivity) 1 else 0) +
        (if (hasReasoning) 1 else 0) +
        (if (webSearchResults.isNotEmpty() && sourceStepIndex < 0) 1 else 0) +
        (if (!isReasoningActive) 1 else 0)
    var nodeIndex = 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("reasoning-execution-timeline"),
    ) {
        executionSteps.forEachIndexed { stepIndex, step ->
            val isActive = isReasoningActive && stepIndex == pendingStepIndex
            TimelineNode(
                icon = stepIcon(step.type),
                iconTint = stepIconTint(step.type),
                title = step.title,
                active = isActive,
                completed = step.completed,
                first = nodeIndex == 0,
                last = nodeIndex == nodeCount - 1,
                modifier = Modifier.testTag("reasoning-execution-step-$stepIndex"),
            ) {
                ExecutionLabels(step)
                if (stepIndex == sourceStepIndex && webSearchResults.isNotEmpty()) {
                    WebsiteLabels(webSearchResults)
                }
            }
            nodeIndex++
        }

        if (showStandaloneActivity) {
            TimelineNode(
                icon = Icons.Outlined.AutoAwesome,
                iconTint = TimelineReasoningPurple,
                title = activityStatusText?.takeIf { it.isNotBlank() } ?: "等待首个响应",
                active = true,
                completed = false,
                first = nodeIndex == 0,
                last = nodeIndex == nodeCount - 1,
                modifier = Modifier.testTag("reasoning-execution-live-step"),
            )
            nodeIndex++
        }

        if (webSearchResults.isNotEmpty() && sourceStepIndex < 0) {
            TimelineNode(
                icon = Icons.Filled.Public,
                iconTint = TimelineWebGreen,
                title = "查阅网站",
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

        if (hasReasoning) {
            TimelineNode(
                icon = Icons.Outlined.AutoAwesome,
                iconTint = TimelineReasoningPurple,
                title = "思考过程",
                active = reasoningIsActive,
                completed = !reasoningIsActive,
                first = nodeIndex == 0,
                last = nodeIndex == nodeCount - 1,
                modifier = Modifier.testTag("reasoning-execution-reasoning-step"),
            ) {
                reasoningContent()
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
                    activityStatusText
                        ?.takeIf { it.startsWith("上下文压缩失败：") }
                        ?: "执行失败"
                } else {
                    "完成"
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

private fun stepIcon(type: ExecutionStepType): ImageVector = when (type) {
    ExecutionStepType.Search -> Icons.Filled.Search
    ExecutionStepType.Web -> Icons.Filled.Public
    ExecutionStepType.Tool -> Icons.Filled.Build
}

private fun stepIconTint(type: ExecutionStepType): Color = when (type) {
    ExecutionStepType.Search -> TimelineSearchBlue
    ExecutionStepType.Web -> TimelineWebGreen
    ExecutionStepType.Tool -> TimelineToolOrange
}

@Composable
private fun ExecutionLabels(step: ExecutionStep) {
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
                },
                iconTint = stepIconTint(step.type),
                onClick = if (step.type == ExecutionStepType.Web) {
                    webLinkClick(uriHandler, label)
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
            CapsuleLabel(text = "其余 ${sources.size - MAX_VISIBLE_SOURCE_PILLS} 个")
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
            )
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
