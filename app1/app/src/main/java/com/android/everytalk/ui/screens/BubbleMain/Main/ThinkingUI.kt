package com.android.everytalk.ui.screens.BubbleMain.Main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.WebSearchResult
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

internal fun reasoningSheetTallHeightFraction(): Float = AppModalBottomSheetMaximumHeightFraction

internal fun reasoningSheetText(
    displayedReasoningText: String,
    isReasoningActive: Boolean,
    messageIsError: Boolean,
): String = when {
    displayedReasoningText.isNotBlank() -> displayedReasoningText
    messageIsError -> "思考过程中发生错误"
    isReasoningActive -> ""
    else -> "暂无详细思考内容"
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
    var visibilityNotified by remember(currentMessageId) { mutableStateOf(false) }

    val showInlineThinkingStatus = !messageIsError &&
        !mainContentHasStarted &&
        (isReasoningStreaming || displayedReasoningText.isNotBlank() || activityStatusText != null)
    val hasReviewableProcess = displayedReasoningText.isNotBlank() ||
        executionSteps.isNotEmpty() ||
        webSearchResults.isNotEmpty()
    val shouldShowReviewDotToggle = hasReviewableProcess &&
        !messageIsError &&
        (isReasoningComplete || !isReasoningStreaming)
    val inlineStatusText = executionSummaryText(
        reasoningText = displayedReasoningText,
        activityStatusText = activityStatusText,
        executionSteps = executionSteps,
    )
    val openReasoningSheet = {
        focusManager.clearFocus()
        showReasoningSheet = true
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
    ) {
        AnimatedVisibility(
            visible = showInlineThinkingStatus,
            enter = fadeIn(tween(150)) + expandVertically(
                animationSpec = tween(220),
                expandFrom = Alignment.Top,
            ),
            exit = fadeOut(tween(180)) + shrinkVertically(
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top,
            ),
        ) {
            ThinkingStatusRow(
                text = inlineStatusText,
                textColor = reasoningTextColor,
                onClick = openReasoningSheet,
                modifier = Modifier.onSizeChanged {
                    if (it.height > 0 && !visibilityNotified) {
                        view.post { onVisibilityChanged() }
                        visibilityNotified = true
                    }
                },
            )
        }

        var showDotDelayed by remember(currentMessageId) {
            mutableStateOf(!showInlineThinkingStatus)
        }
        LaunchedEffect(showInlineThinkingStatus) {
            if (showInlineThinkingStatus) {
                showDotDelayed = false
            } else {
                delay(280)
                showDotDelayed = true
            }
        }

        AnimatedVisibility(
            visible = shouldShowReviewDotToggle && showDotDelayed,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150)),
        ) {
            Box(
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .testTag("reasoning-sheet-review-toggle")
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = openReasoningSheet,
                        ),
                ) {
                    val circleIconSize by animateDpAsState(
                        targetValue = if (showReasoningSheet) 10.dp else 7.dp,
                        animationSpec = tween(
                            durationMillis = 250,
                            easing = FastOutSlowInEasing,
                        ),
                        label = "reasoningSheetToggleIconSize_$currentMessageId",
                    )
                    Box(
                        modifier = Modifier
                            .size(circleIconSize)
                            .background(reasoningToggleDotColor, CircleShape),
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
            webSearchResults = webSearchResults,
            isReasoningActive = !messageIsError && !mainContentHasStarted,
            messageIsError = messageIsError,
            scrollState = streamingScrollState,
            onDismissRequest = { showReasoningSheet = false },
        )
    }
}

@Composable
private fun ThinkingStatusRow(
    text: String,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScanningHighlightText(
            text = text,
            textColor = textColor,
            useSmallStyle = false,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .testTag("reasoning-inline-status-text"),
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
    webSearchResults: List<WebSearchResult>,
    isReasoningActive: Boolean,
    messageIsError: Boolean,
    scrollState: ScrollState,
    onDismissRequest: () -> Unit,
) {
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
        )
    }
    val normalizedSheetMarkdown = remember(sheetText) {
        normalizeReasoningMarkdown(sheetText)
    }
    val preparedSheetMessage = remember(normalizedSheetMarkdown) {
        StreamBlockParser.prepareMessage(
            content = normalizedSheetMarkdown,
            messageId = "reasoning-sheet",
            contentVersion = contentVersionForRendering(normalizedSheetMarkdown),
        )
    }
    val preparedSheetDocument = remember(preparedSheetMessage) {
        (parseMarkdown(
            preparedSheetMessage.markdown,
            flavour = EveryTalkMarkdownFlavourDescriptor,
        ) as? State.Success)?.let { state ->
            PreparedMarkdownDocument(
                state = state,
                nodes = state.node.children,
            )
        }
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
                text = "执行过程",
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
            webSearchResults = webSearchResults,
            activityStatusText = activityStatusText,
            reasoningText = sheetText,
            isReasoningActive = isReasoningActive,
            messageIsError = messageIsError,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    if (isReasoningActive) contentDescription = "执行过程加载中"
                },
        ) {
            if (sheetText.isNotBlank()) {
                val markdownModifier = Modifier
                    .fillMaxWidth()
                    .testTag("reasoning-sheet-markdown")
                if (preparedSheetDocument != null) {
                    UnifiedMarkdownNodesRenderer(
                        preparedMessage = preparedSheetMessage,
                        preparedMarkdownDocument = preparedSheetDocument,
                        nodes = preparedSheetDocument.nodes,
                        sender = Sender.AI,
                        modifier = markdownModifier,
                    )
                } else {
                    UnifiedMarkdownRenderer(
                        preparedMessage = preparedSheetMessage,
                        sender = Sender.AI,
                        modifier = markdownModifier,
                    )
                }
            }
        }
    }
}
