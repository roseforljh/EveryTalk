package com.android.everytalk.ui.screens.BubbleMain.Main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.data.DataClass.hasReviewableExecutionProcess
import com.android.everytalk.ui.components.sheet.AppModalBottomSheet
import com.android.everytalk.ui.components.sheet.AppModalBottomSheetMaximumHeightFraction
import com.android.everytalk.ui.components.markdown.EveryTalkMarkdownFlavourDescriptor
import com.android.everytalk.ui.components.streaming.PreparedMarkdownDocument
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.android.everytalk.ui.components.streaming.UnifiedMarkdownRenderer
import com.android.everytalk.ui.components.streaming.UnifiedMarkdownNodesRenderer
import com.android.everytalk.ui.components.streaming.contentVersionForRendering
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import kotlinx.coroutines.delay

private const val EXECUTION_CONTAINER_COLLAPSE_MS = 280
private const val EXECUTION_SECTION_HEIGHT_MS = 240
private const val EXECUTION_SECTION_EXPAND_FADE_MS = 160
private const val EXECUTION_SECTION_COLLAPSE_FADE_MS = 120
private const val EXECUTION_ARROW_ROTATION_MS = 200
private const val REASONING_PREVIEW_MAX_LINES = 3
private const val REASONING_PREVIEW_SOURCE_CHAR_LIMIT = 2_000
private const val EXECUTION_TOOL_PREVIEW_MAX_ITEMS = 3

internal fun reasoningSheetTallHeightFraction(): Float = AppModalBottomSheetMaximumHeightFraction

internal fun reasoningSheetText(
    displayedReasoningText: String,
    isReasoningActive: Boolean,
    messageIsError: Boolean,
    errorText: String,
    emptyText: String,
): String = when {
    displayedReasoningText.isNotBlank() -> displayedReasoningText
    messageIsError -> errorText
    isReasoningActive -> ""
    else -> emptyText
}

internal fun normalizeReasoningMarkdown(markdown: String): String {
    val lines = markdown.lineSequence().toList()
    var activeFenceMarker: Char? = null
    return buildString(markdown.length) {
        lines.forEachIndexed { index, line ->
            val fenceMarker = reasoningFenceMarker(line)
            val normalizedLine: String
            if (fenceMarker != null) {
                if (activeFenceMarker == null) {
                    activeFenceMarker = fenceMarker
                } else if (activeFenceMarker == fenceMarker) {
                    activeFenceMarker = null
                }
                normalizedLine = line
            } else if (activeFenceMarker == null) {
                normalizedLine = separateAdjacentStrongSections(line)
            } else {
                normalizedLine = line
            }
            append(normalizedLine)
            if (index < lines.lastIndex) {
                val nextLine = lines[index + 1]
                if (
                    activeFenceMarker == null &&
                    fenceMarker == null &&
                    normalizedLine.isNotBlank() &&
                    nextLine.isNotBlank() &&
                    !normalizedLine.endsWith("  ")
                ) {
                    append("  ")
                }
                append('\n')
            }
        }
    }
}

private fun reasoningFenceMarker(line: String): Char? {
    val trimmed = line.trimStart()
    val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    var markerCount = 0
    while (markerCount < trimmed.length && trimmed[markerCount] == marker) markerCount++
    return marker.takeIf { markerCount >= 3 }
}

private fun separateAdjacentStrongSections(line: String): String {
    val result = StringBuilder(line.length)
    var index = 0
    while (index < line.length) {
        val isStrongBoundary = index > 0 &&
            index + 4 < line.length &&
            line.startsWith("****", index) &&
            line[index - 1] != '*' &&
            !line[index - 1].isWhitespace() &&
            line[index + 4] != '*' &&
            !line[index + 4].isWhitespace()
        if (isStrongBoundary) {
            result.append("**\n\n**")
            index += 4
        } else {
            result.append(line[index])
            index++
        }
    }
    return result.toString()
}

/**
 * 把思考内容转成小容器中的干净文字。
 * 完整 Markdown 仍保留在抽屉，这里只移除预览中会显得杂乱的标记。
 */
internal fun reasoningPreviewPlainText(markdown: String): String {
    // ponytail: 预览只显示末尾三行，限制源文本避免长思考输出时反复测量全文。
    val source = markdown.takeLast(REASONING_PREVIEW_SOURCE_CHAR_LIMIT)
    return source.lineSequence()
        .filterNot { reasoningFenceMarker(it) != null }
        .map(::cleanReasoningPreviewLine)
        .filter(String::isNotBlank)
        .joinToString("\n")
}

private fun cleanReasoningPreviewLine(source: String): String {
    var start = source.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return ""
    while (start < source.length) {
        val marker = source[start]
        val next = source.getOrNull(start + 1)
        when {
            marker == '#' || marker == '>' -> {
                while (start < source.length && source[start] == marker) start++
                while (start < source.length && source[start].isWhitespace()) start++
            }
            marker in charArrayOf('-', '+', '*') && next?.isWhitespace() == true -> {
                start += 2
                while (start < source.length && source[start].isWhitespace()) start++
            }
            marker.isDigit() -> {
                var end = start
                while (end < source.length && source[end].isDigit()) end++
                val orderedMarker = source.getOrNull(end)
                if (
                    (orderedMarker == '.' || orderedMarker == ')') &&
                    source.getOrNull(end + 1)?.isWhitespace() == true
                ) {
                    start = end + 2
                    while (start < source.length && source[start].isWhitespace()) start++
                } else {
                    break
                }
            }
            else -> break
        }
    }
    return stripInlineReasoningMarkdown(source.substring(start))
}

private fun stripInlineReasoningMarkdown(source: String): String = buildString(source.length) {
    var index = 0
    var pendingSpace = false
    while (index < source.length) {
        val character = source[index]
        when {
            character == '\\' && index + 1 < source.length -> {
                if (pendingSpace && isNotEmpty()) append(' ')
                pendingSpace = false
                append(source[index + 1])
                index += 2
            }
            character == '!' && source.getOrNull(index + 1) == '[' -> index++
            character == '[' -> {
                val labelEnd = source.indexOf(']', startIndex = index + 1)
                val destinationStart = labelEnd + 1
                if (labelEnd > index && source.getOrNull(destinationStart) == '(') {
                    val destinationEnd = source.indexOf(')', startIndex = destinationStart + 1)
                    if (destinationEnd > destinationStart) {
                        if (pendingSpace && isNotEmpty()) append(' ')
                        pendingSpace = false
                        append(stripInlineReasoningMarkdown(source.substring(index + 1, labelEnd)))
                        index = destinationEnd + 1
                    } else {
                        append(character)
                        index++
                    }
                } else {
                    append(character)
                    index++
                }
            }
            character == '*' || character == '`' || character == '~' -> index++
            character == '_' && (
                source.getOrNull(index - 1)?.isLetterOrDigit() != true ||
                    source.getOrNull(index + 1)?.isLetterOrDigit() != true
                ) -> index++
            character.isWhitespace() -> {
                pendingSpace = true
                index++
            }
            else -> {
                if (pendingSpace && isNotEmpty()) append(' ')
                pendingSpace = false
                append(character)
                index++
            }
        }
    }
}.trim()

@Composable
internal fun ReasoningToggleAndContent(
    currentMessageId: String,
    displayedReasoningText: String,
    activityStatusText: String? = null,
    executionSteps: List<ExecutionStep> = emptyList(),
    executionTrace: List<ExecutionTraceEvent> = emptyList(),
    detailExecutionTrace: List<ExecutionTraceEvent> = executionTrace,
    detailExecutionTraceProvider: (() -> List<ExecutionTraceEvent>)? = null,
    detailInitialEventIndex: Int = 0,
    webSearchResults: List<WebSearchResult> = emptyList(),
    isReasoningStreaming: Boolean,
    isReasoningComplete: Boolean,
    replyIsStreaming: Boolean = false,
    messageIsError: Boolean,
    mainContentHasStarted: Boolean,
    executionStartedAtMillis: Long? = null,
    executionFinishedAtMillis: Long? = null,
    reasoningTextColor: Color,
    reasoningToggleDotColor: Color,
    modifier: Modifier = Modifier,
    streamingScrollState: ScrollState = rememberScrollState(),
    onInteractiveExpansionChanged: (key: String, expanded: Boolean) -> Unit = { _, _ -> },
    onVisibilityChanged: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    var showReasoningSheet by remember(currentMessageId) { mutableStateOf(false) }
    var visibilityNotified by remember(currentMessageId) { mutableStateOf(false) }

    // 是否还在运行只能由真实流状态决定。历史消息里的“正在……”只是展示文本，
    // 不能在退出重进后凭一行旧文字重新启动计时器。
    val processIsActive = executionProcessIsActive(
        executionFinishedAtMillis = executionFinishedAtMillis,
        messageIsError = messageIsError,
        replyIsStreaming = replyIsStreaming,
        isReasoningStreaming = isReasoningStreaming,
    )
    var userExpanded by remember(currentMessageId) { mutableStateOf(false) }
    val forceExpanded = processIsActive || replyIsStreaming
    val executionChainExpanded = forceExpanded || userExpanded
    var nowMillis by remember(currentMessageId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(forceExpanded, currentMessageId) {
        if (forceExpanded) userExpanded = false
    }
    LaunchedEffect(processIsActive, currentMessageId) {
        while (processIsActive) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val hasReviewableProcess = hasReviewableExecutionProcess(
        reasoningText = displayedReasoningText,
        executionSteps = executionSteps,
        executionTrace = executionTrace,
        webSearchResults = webSearchResults,
        executionStatus = activityStatusText,
    )
    val hasExecutionTiming = executionStartedAtMillis != null &&
        (executionFinishedAtMillis != null || processIsActive)
    val shouldShowExecutionChain = hasExecutionTiming || processIsActive || hasReviewableProcess ||
        (!mainContentHasStarted && displayedReasoningText.isNotBlank())
    val sections = remember(displayedReasoningText, executionSteps, executionTrace) {
        executionProcessSections(
            reasoningText = displayedReasoningText,
            executionSteps = executionSteps,
            executionTrace = executionTrace,
        )
    }
    val elapsedMillis = executionStartedAtMillis?.let { startedAt ->
        val endedAt = executionFinishedAtMillis ?: nowMillis.takeIf { processIsActive }
        endedAt?.minus(startedAt)?.coerceAtLeast(0L)
    }
    val elapsedText = elapsedMillis?.let { localizedExecutionDuration(it) }
    val executionChainTitle = when {
        processIsActive && elapsedText != null ->
            stringResource(R.string.thinking_processing_duration, elapsedText)
        processIsActive -> stringResource(R.string.computer_action_working).trimEnd('…')
        messageIsError && elapsedText != null ->
            stringResource(R.string.thinking_failed_duration, elapsedText)
        messageIsError -> stringResource(R.string.thinking_execution_failed)
        elapsedText != null -> stringResource(R.string.thinking_elapsed_duration, elapsedText)
        else -> stringResource(R.string.thinking_process)
    }
    val openReasoningSheet = {
        focusManager.clearFocus()
        showReasoningSheet = true
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
    ) {
        AnimatedVisibility(
            visible = shouldShowExecutionChain,
            enter = fadeIn(tween(EXECUTION_SECTION_EXPAND_FADE_MS)) + expandVertically(
                animationSpec = tween(EXECUTION_SECTION_HEIGHT_MS, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top,
            ),
            exit = fadeOut(tween(EXECUTION_SECTION_COLLAPSE_FADE_MS)) + shrinkVertically(
                animationSpec = tween(EXECUTION_CONTAINER_COLLAPSE_MS, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top,
            ),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reasoning-process-container")
                    .onSizeChanged {
                        if (it.height > 0 && !visibilityNotified) {
                            view.post { onVisibilityChanged() }
                            visibilityNotified = true
                        }
                    },
            ) {
                Column {
                    ExecutionChainHeader(
                        text = executionChainTitle,
                        active = processIsActive,
                        expanded = executionChainExpanded,
                        textColor = reasoningTextColor,
                        iconColor = reasoningToggleDotColor,
                        onClick = {
                            if (!forceExpanded) userExpanded = !userExpanded
                        },
                    )
                    AnimatedVisibility(
                        visible = executionChainExpanded,
                        enter = fadeIn(tween(EXECUTION_SECTION_EXPAND_FADE_MS)) + expandVertically(
                            animationSpec = tween(EXECUTION_SECTION_HEIGHT_MS, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Top,
                        ),
                        exit = fadeOut(tween(EXECUTION_SECTION_COLLAPSE_FADE_MS)) + shrinkVertically(
                            animationSpec = tween(EXECUTION_CONTAINER_COLLAPSE_MS, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.Top,
                        ),
                    ) {
                        ExecutionProcessContent(
                            expansionNamespace = currentMessageId,
                            sections = sections,
                            activityStatusText = activityStatusText,
                            active = processIsActive,
                            reasoningActive = isReasoningStreaming,
                            messageIsError = messageIsError,
                            onOpenDetails = openReasoningSheet,
                            onInteractiveExpansionChanged = onInteractiveExpansionChanged,
                        )
                    }
                }
            }
        }
    }

    if (showReasoningSheet) {
        ReasoningBottomSheet(
            displayedReasoningText = displayedReasoningText,
            activityStatusText = activityStatusText,
            executionSteps = executionSteps,
            executionTrace = detailExecutionTraceProvider?.invoke() ?: detailExecutionTrace,
            webSearchResults = webSearchResults,
            isReasoningActive = processIsActive,
            messageIsError = messageIsError,
            scrollState = streamingScrollState,
            initialEventIndex = detailInitialEventIndex,
            onDismissRequest = { showReasoningSheet = false },
        )
    }
}

/** 计时器只认当前真实流，历史状态文字不能自行恢复运行。 */
internal fun executionProcessIsActive(
    executionFinishedAtMillis: Long?,
    messageIsError: Boolean,
    replyIsStreaming: Boolean,
    isReasoningStreaming: Boolean,
): Boolean = executionFinishedAtMillis == null &&
    !messageIsError &&
    (replyIsStreaming || isReasoningStreaming)

@Composable
private fun ExecutionChainHeader(
    text: String,
    active: Boolean,
    expanded: Boolean,
    textColor: Color,
    iconColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(EXECUTION_ARROW_ROTATION_MS, easing = FastOutSlowInEasing),
        label = "executionChainArrow",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("reasoning-inline-status")
            .semantics(mergeDescendants = true) {
                contentDescription = text
                role = Role.Button
            }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .height(24.dp)
            .padding(horizontal = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) {
            ScanningHighlightText(
                text = text,
                textColor = textColor,
                useSmallStyle = false,
                modifier = Modifier
                    .testTag("reasoning-inline-status-text"),
            )
        } else {
            Text(
                text = text,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .testTag("reasoning-inline-status-text"),
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(
                if (expanded) R.string.thinking_collapse_execution else R.string.thinking_expand_execution
            ),
            tint = iconColor.copy(alpha = 0.72f),
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = arrowRotation },
        )
        Spacer(Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun ExecutionProcessContent(
    expansionNamespace: String,
    sections: List<ExecutionProcessSection>,
    activityStatusText: String?,
    active: Boolean,
    reasoningActive: Boolean,
    messageIsError: Boolean,
    onOpenDetails: () -> Unit,
    onInteractiveExpansionChanged: (key: String, expanded: Boolean) -> Unit,
) {
    val hasPendingTool = sections.any { section ->
        section is ExecutionProcessSection.ToolGroup && section.entries.any { !it.step.completed }
    }
    val hasSpecificActivity = !activityStatusText.isNullOrBlank() &&
        !isGenericExecutionStatus(activityStatusText)
    val showStandaloneStatus = active && !hasPendingTool && (
        sections.isEmpty() ||
            sections.lastOrNull() !is ExecutionProcessSection.Narrative ||
            hasSpecificActivity
        )
    val activeNarrativeIndex = if (
        reasoningActive && sections.lastOrNull() is ExecutionProcessSection.Narrative
    ) {
        sections.lastIndex
    } else {
        -1
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp)
            .testTag("reasoning-chain-summaries"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sections.forEachIndexed { sectionIndex, section ->
            when (section) {
                is ExecutionProcessSection.Narrative -> {
                    ReasoningPreviewCard(
                        text = section.text,
                        sectionIndex = sectionIndex,
                        active = sectionIndex == activeNarrativeIndex,
                        onOpenDetails = onOpenDetails,
                    )
                }

                is ExecutionProcessSection.ToolGroup -> {
                    ExecutionToolGroup(
                        expansionNamespace = expansionNamespace,
                        group = section,
                        active = active && section.entries.any { !it.step.completed },
                        groupIndex = sectionIndex,
                        onOpenDetails = onOpenDetails,
                        onInteractiveExpansionChanged = onInteractiveExpansionChanged,
                    )
                }
            }
        }

        if (showStandaloneStatus) {
            val status = localizedExecutionStatusText(activityStatusText)
                ?: stringResource(R.string.thinking_waiting_first_response)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reasoning-chain-summary-${sections.size}")
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onOpenDetails,
                    ),
            ) {
                ScanningHighlightText(
                    text = status,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    useSmallStyle = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reasoning-chain-live-status"),
                )
            }
        } else if (messageIsError) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reasoning-chain-summary-${sections.size}")
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onOpenDetails,
                    ),
            ) {
                Text(
                    text = localizedExecutionStatusText(activityStatusText)
                        ?: stringResource(R.string.thinking_execution_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("reasoning-chain-error-status"),
                )
            }
        }
    }
}

/** 每段连续思考都有独立小容器，工具调用仍保持在它们之间。 */
@Composable
private fun ReasoningPreviewCard(
    text: String,
    sectionIndex: Int,
    active: Boolean,
    onOpenDetails: () -> Unit,
) {
    val plainText = remember(text) { reasoningPreviewPlainText(text) }
    if (plainText.isBlank()) return
    var visible by remember(sectionIndex) { mutableStateOf(false) }
    LaunchedEffect(sectionIndex) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(EXECUTION_SECTION_EXPAND_FADE_MS)) + expandVertically(
            animationSpec = tween(EXECUTION_SECTION_HEIGHT_MS, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Top,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("reasoning-chain-summary-$sectionIndex")
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onOpenDetails,
                ),
        ) {
            LatestReasoningPreviewText(
                text = plainText,
                active = active,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** 按当前容器宽度测量换行，只取实际显示的最新三行。 */
@Composable
private fun LatestReasoningPreviewText(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val textStyle = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val previewText = remember(text, textStyle, maxWidthPx) {
            val layout = textMeasurer.measure(
                text = AnnotatedString(text),
                style = textStyle,
                constraints = Constraints(maxWidth = maxWidthPx),
            )
            val start = if (layout.lineCount > REASONING_PREVIEW_MAX_LINES) {
                layout.getLineStart(layout.lineCount - REASONING_PREVIEW_MAX_LINES)
            } else {
                0
            }
            text.substring(start).trimStart()
        }
        if (active) {
            ScanningHighlightText(
                text = previewText,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                useSmallStyle = false,
                maxLines = REASONING_PREVIEW_MAX_LINES,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reasoning-chain-narrative-scanning"),
            )
        } else {
            Text(
                text = previewText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = textStyle,
                maxLines = REASONING_PREVIEW_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reasoning-chain-narrative-static"),
            )
        }
    }
}

@Composable
private fun ExecutionToolGroup(
    expansionNamespace: String,
    group: ExecutionProcessSection.ToolGroup,
    active: Boolean,
    groupIndex: Int,
    onOpenDetails: () -> Unit,
    onInteractiveExpansionChanged: (key: String, expanded: Boolean) -> Unit,
) {
    val stableKey = group.entries.firstOrNull()?.step?.id ?: "group-$groupIndex"
    val expansionKey = "$expansionNamespace:$stableKey"
    var expanded by remember(stableKey) { mutableStateOf(false) }
    DisposableEffect(expansionKey, expanded) {
        if (expanded) onInteractiveExpansionChanged(expansionKey, true)
        onDispose {
            if (expanded) onInteractiveExpansionChanged(expansionKey, false)
        }
    }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(EXECUTION_ARROW_ROTATION_MS, easing = FastOutSlowInEasing),
        label = "executionGroupArrow",
    )
    val summary = executionToolGroupSummary(group.entries)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("reasoning-chain-summary-$groupIndex")
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { expanded = !expanded },
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (active) {
                ScanningHighlightText(
                    text = summary,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    useSmallStyle = false,
                    modifier = Modifier
                        .testTag("reasoning-chain-summary-scanning"),
                )
            } else {
                Text(
                    text = summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .testTag("reasoning-chain-summary-static"),
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.thinking_collapse_execution else R.string.thinking_expand_execution
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = arrowRotation },
            )
            Spacer(Modifier.weight(1f, fill = false))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(EXECUTION_SECTION_EXPAND_FADE_MS)) + expandVertically(
                animationSpec = tween(EXECUTION_SECTION_HEIGHT_MS, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top,
            ),
            exit = fadeOut(tween(EXECUTION_SECTION_COLLAPSE_FADE_MS)) + shrinkVertically(
                animationSpec = tween(EXECUTION_SECTION_HEIGHT_MS, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top,
            ),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 主页面只预览最近三条，完整执行记录仍由详情抽屉展示。
                group.entries.takeLast(EXECUTION_TOOL_PREVIEW_MAX_ITEMS).forEachIndexed { stepIndex, entry ->
                    InlineExecutionStep(
                        entry = entry,
                        active = active && !entry.step.completed,
                        onOpenDetails = onOpenDetails,
                        modifier = Modifier.testTag("reasoning-chain-tool-$groupIndex-$stepIndex"),
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineExecutionStep(
    entry: ExecutionTimelineEntry,
    active: Boolean,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = entry.step
    val title = localizedExecutionStatusText(step.title) ?: step.title
    val label = step.labels.firstOrNull()?.trim().orEmpty()
    val text = buildString {
        append(title)
        if (label.isNotEmpty()) append(" · ").append(label)
        if (entry.invocationCount > 1) append("  × ").append(entry.invocationCount)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onOpenDetails,
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) {
            ScanningHighlightText(
                text = text,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                useSmallStyle = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reasoning-chain-tool-scanning"),
            )
        } else {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reasoning-chain-tool-static"),
            )
        }
    }
}

@Composable
private fun executionToolGroupSummary(entries: List<ExecutionTimelineEntry>): String {
    val count = entries.sumOf { it.invocationCount.toLong() }
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    val types = entries.map { it.step.type }.toSet()
    val resource = when {
        types == setOf(ExecutionStepType.Agent) -> R.string.thinking_server_actions_count
        types == setOf(ExecutionStepType.Tool) -> R.string.thinking_tool_actions_count
        types == setOf(ExecutionStepType.Search) -> R.string.thinking_search_actions_count
        types == setOf(ExecutionStepType.Web) -> R.string.thinking_web_actions_count
        else -> R.string.thinking_mixed_actions_count
    }
    return stringResource(resource, count)
}

@Composable
private fun localizedExecutionDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> stringResource(R.string.thinking_duration_hours_minutes, hours, minutes)
        minutes > 0L -> stringResource(R.string.thinking_duration_minutes_seconds, minutes, seconds)
        else -> stringResource(R.string.thinking_duration_seconds, seconds)
    }
}

@Composable
internal fun ScanningHighlightText(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    useSmallStyle: Boolean = true,
    maxLines: Int = 1,
) {
    val transition = rememberInfiniteTransition(label = "scanningHighlightText")
    val shimmerProgress = transition.animateFloat(
        initialValue = -0.55f,
        targetValue = 1.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanningHighlightProgress",
    )
    val baseColor = textColor.copy(alpha = 0.58f)
    val highlightColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.98f)

    Text(
        text = text,
        style = (if (useSmallStyle) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
            .copy(color = baseColor),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val textWidthPx = size.width.coerceAtLeast(1f)
                val shimmerCenter = shimmerProgress.value * textWidthPx
                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.34f to Color.Transparent,
                            0.5f to highlightColor,
                            0.66f to Color.Transparent,
                            1f to Color.Transparent,
                        ),
                        start = Offset(shimmerCenter - textWidthPx * 0.65f, 0f),
                        end = Offset(shimmerCenter + textWidthPx * 0.65f, 0f),
                    ),
                    blendMode = BlendMode.SrcAtop,
                )
            },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReasoningBottomSheet(
    displayedReasoningText: String,
    activityStatusText: String?,
    executionSteps: List<ExecutionStep>,
    executionTrace: List<ExecutionTraceEvent>,
    webSearchResults: List<WebSearchResult>,
    isReasoningActive: Boolean,
    messageIsError: Boolean,
    scrollState: ScrollState,
    initialEventIndex: Int,
    onDismissRequest: () -> Unit,
) {
    var focusTop by remember(initialEventIndex) { mutableStateOf<Int?>(null) }
    LaunchedEffect(focusTop, initialEventIndex) {
        val target = focusTop ?: return@LaunchedEffect
        if (initialEventIndex > 0) {
            delay(250L)
            scrollState.scrollTo(target.coerceIn(0, scrollState.maxValue))
        }
    }
    val executionLoadingDescription = stringResource(R.string.thinking_execution_loading)
    val sheetText = if (
        displayedReasoningText.isBlank() &&
        !messageIsError &&
        (executionSteps.isNotEmpty() || webSearchResults.isNotEmpty())
    ) {
        ""
    } else {
        reasoningSheetText(
            displayedReasoningText = displayedReasoningText,
            isReasoningActive = isReasoningActive,
            messageIsError = messageIsError,
            errorText = stringResource(R.string.reasoning_error),
            emptyText = stringResource(R.string.reasoning_empty),
        )
    }
    AppModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.testTag("reasoning-sheet-surface"),
        scrollState = scrollState,
        sheetContentModifier = Modifier.testTag("reasoning-sheet-content"),
        scrollModifier = Modifier.testTag("reasoning-sheet-scroll"),
        contentModifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp),
        stateModifier = { sheetValue, contentOverflows ->
            Modifier.testTag(
                "reasoning-sheet-state-${sheetValue.name}-overflow-$contentOverflows",
            )
        },
        dragHandleModifier = Modifier.testTag("reasoning-sheet-drag-handle-enabled"),
        bottomIndicatorModifier = Modifier.testTag("reasoning-sheet-bottom-indicator"),
        header = {
            Text(
                text = stringResource(R.string.thinking_execution),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reasoning-sheet-header")
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        },
    ) {
        ThinkingExecutionTimeline(
            executionSteps = executionSteps,
            executionTrace = executionTrace,
            webSearchResults = webSearchResults,
            activityStatusText = activityStatusText,
            reasoningText = sheetText,
            isReasoningActive = isReasoningActive,
            messageIsError = messageIsError,
            focusItemIndex = initialEventIndex,
            onFocusItemPositioned = { focusTop = it },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    if (isReasoningActive) {
                        contentDescription = executionLoadingDescription
                    }
                },
        ) { reasoningSegment, reasoningIndex ->
            ReasoningMarkdownBlock(reasoningSegment, reasoningIndex)
        }
    }
}

/** 每段思考在自己的时间线节点内渲染，避免工具与整段文本被拆成两个区域。 */
@Composable
private fun ReasoningMarkdownBlock(
    text: String,
    index: Int,
    tagPrefix: String = "reasoning-sheet",
) {
    if (text.isBlank()) return
    val normalizedMarkdown = remember(text) { normalizeReasoningMarkdown(text) }
    val preparedMessage = remember(normalizedMarkdown, index) {
        StreamBlockParser.prepareMessage(
            content = normalizedMarkdown,
            messageId = "$tagPrefix-$index",
            contentVersion = contentVersionForRendering(normalizedMarkdown),
        )
    }
    val preparedDocument = remember(preparedMessage) {
        (parseMarkdown(
            preparedMessage.markdown,
            flavour = EveryTalkMarkdownFlavourDescriptor,
        ) as? State.Success)?.let { state ->
            PreparedMarkdownDocument(
                state = state,
                nodes = state.node.children,
            )
        }
    }
    val markdownModifier = Modifier
        .fillMaxWidth()
        .testTag(if (index == 0) "$tagPrefix-markdown" else "$tagPrefix-markdown-$index")
    if (preparedDocument != null) {
        UnifiedMarkdownNodesRenderer(
            preparedMessage = preparedMessage,
            preparedMarkdownDocument = preparedDocument,
            nodes = preparedDocument.nodes,
            sender = Sender.AI,
            modifier = markdownModifier,
        )
    } else {
        UnifiedMarkdownRenderer(
            preparedMessage = preparedMessage,
            sender = Sender.AI,
            modifier = markdownModifier,
        )
    }
}
