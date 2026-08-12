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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.ExecutionStep
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

@Composable
internal fun ReasoningToggleAndContent(
    currentMessageId: String,
    displayedReasoningText: String,
    activityStatusText: String? = null,
    executionSteps: List<ExecutionStep> = emptyList(),
    executionTrace: List<ExecutionTraceEvent> = emptyList(),
    webSearchResults: List<WebSearchResult> = emptyList(),
    isReasoningStreaming: Boolean,
    isReasoningComplete: Boolean,
    messageIsError: Boolean,
    mainContentHasStarted: Boolean,
    reasoningTextColor: Color,
    reasoningToggleDotColor: Color,
    modifier: Modifier = Modifier,
    streamingScrollState: ScrollState = rememberScrollState(),
    onVisibilityChanged: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    var showReasoningSheet by remember(currentMessageId) { mutableStateOf(false) }
    var executionChainExpanded by remember(currentMessageId) { mutableStateOf(false) }
    var visibilityNotified by remember(currentMessageId) { mutableStateOf(false) }

    val processIsActive = !messageIsError &&
        (isReasoningStreaming || !activityStatusText.isNullOrBlank())
    val hasReviewableProcess = hasReviewableExecutionProcess(
        reasoningText = displayedReasoningText,
        executionSteps = executionSteps,
        executionTrace = executionTrace,
        webSearchResults = webSearchResults,
        executionStatus = activityStatusText,
    )
    val inlineStatusText = localizedExecutionStatusText(executionSummaryText(
        reasoningText = displayedReasoningText,
        activityStatusText = activityStatusText,
        executionSteps = executionSteps,
    )).orEmpty()
    val shouldShowExecutionChain = processIsActive || hasReviewableProcess ||
        (!mainContentHasStarted && displayedReasoningText.isNotBlank())
    val executionChainTitle = when {
        processIsActive -> inlineStatusText
        messageIsError -> stringResource(R.string.thinking_execution_failed)
        isReasoningComplete || !isReasoningStreaming || mainContentHasStarted ->
            stringResource(R.string.thinking_process)
        else -> inlineStatusText
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
            enter = fadeIn(tween(150)) + expandVertically(
                animationSpec = tween(220),
                expandFrom = Alignment.Top,
            ),
            exit = fadeOut(tween(180)) + shrinkVertically(
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top,
            ),
        ) {
            Column {
                ExecutionChainHeader(
                    text = executionChainTitle,
                    active = processIsActive,
                    expanded = executionChainExpanded,
                    textColor = reasoningTextColor,
                    iconColor = reasoningToggleDotColor,
                    onClick = { executionChainExpanded = !executionChainExpanded },
                    modifier = Modifier.onSizeChanged {
                        if (it.height > 0 && !visibilityNotified) {
                            view.post { onVisibilityChanged() }
                            visibilityNotified = true
                        }
                    },
                )
                AnimatedVisibility(
                    visible = executionChainExpanded,
                    enter = fadeIn(tween(140)) + expandVertically(
                        animationSpec = tween(200),
                        expandFrom = Alignment.Top,
                    ),
                    exit = fadeOut(tween(120)) + shrinkVertically(
                        animationSpec = tween(180),
                        shrinkTowards = Alignment.Top,
                    ),
                ) {
                    ExecutionChainSummaryList(
                        text = inlineStatusText,
                        onOpenSheet = openReasoningSheet,
                    )
                }
            }
        }
    }

    if (showReasoningSheet) {
        ReasoningBottomSheet(
            displayedReasoningText = displayedReasoningText,
            activityStatusText = activityStatusText,
            executionSteps = executionSteps,
            executionTrace = executionTrace,
            webSearchResults = webSearchResults,
            isReasoningActive = processIsActive,
            messageIsError = messageIsError,
            scrollState = streamingScrollState,
            onDismissRequest = { showReasoningSheet = false },
        )
    }
}

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
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "executionChainArrow",
    )
    Row(
        modifier = modifier
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
            .heightIn(min = 44.dp)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) {
            ScanningHighlightText(
                text = text,
                textColor = textColor,
                useSmallStyle = false,
                modifier = Modifier
                    .widthIn(max = 260.dp)
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
                    .widthIn(max = 260.dp)
                    .testTag("reasoning-inline-status-text"),
            )
        }
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(
                if (expanded) R.string.thinking_collapse_execution else R.string.thinking_expand_execution
            ),
            tint = iconColor.copy(alpha = 0.72f),
            modifier = Modifier
                .padding(start = 2.dp)
                .size(18.dp)
                .graphicsLayer { rotationZ = arrowRotation },
        )
    }
}

@Composable
private fun ExecutionChainSummaryList(
    text: String,
    onOpenSheet: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(start = 8.dp, bottom = 4.dp)
            .testTag("reasoning-chain-summaries"),
    ) {
        ExecutionChainSummaryRow(
            text = text,
            onClick = onOpenSheet,
            modifier = Modifier.testTag("reasoning-chain-summary-0"),
        )
    }
}

@Composable
private fun ExecutionChainSummaryRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .height(44.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScanningHighlightText(
            text = text,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            useSmallStyle = false,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("reasoning-chain-summary-text"),
        )
    }
}

@Composable
internal fun ScanningHighlightText(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    useSmallStyle: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "scanningHighlightText")
    val shimmerProgress by transition.animateFloat(
        initialValue = -0.55f,
        targetValue = 1.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanningHighlightProgress",
    )
    var textWidthPx by remember { mutableFloatStateOf(1f) }
    val shimmerCenter = shimmerProgress * textWidthPx
    val baseColor = textColor.copy(alpha = 0.58f)
    val highlightColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.98f)
    val shimmerBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to baseColor,
            0.34f to baseColor,
            0.5f to highlightColor,
            0.66f to baseColor,
            1f to baseColor,
        ),
        start = Offset(shimmerCenter - textWidthPx * 0.65f, 0f),
        end = Offset(shimmerCenter + textWidthPx * 0.65f, 0f),
    )

    Text(
        text = text,
        style = (if (useSmallStyle) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
            .copy(brush = shimmerBrush),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.onSizeChanged { textWidthPx = it.width.coerceAtLeast(1).toFloat() },
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
    onDismissRequest: () -> Unit,
) {
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
private fun ReasoningMarkdownBlock(text: String, index: Int) {
    if (text.isBlank()) return
    val normalizedMarkdown = remember(text) { normalizeReasoningMarkdown(text) }
    val preparedMessage = remember(normalizedMarkdown, index) {
        StreamBlockParser.prepareMessage(
            content = normalizedMarkdown,
            messageId = "reasoning-sheet-$index",
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
        .testTag(if (index == 0) "reasoning-sheet-markdown" else "reasoning-sheet-markdown-$index")
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
