@file:OptIn(ExperimentalFoundationApi::class)
package com.android.everytalk.ui.screens.MainScreen.chat.text.ui
import com.android.everytalk.R
import androidx.compose.ui.res.painterResource

import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.ui.screens.BubbleMain.Main.AttachmentsContent
import com.android.everytalk.ui.screens.BubbleMain.Main.ReasoningToggleAndContent
import com.android.everytalk.ui.screens.BubbleMain.Main.UserOrErrorMessageContent
import com.android.everytalk.ui.screens.BubbleMain.Main.MessageContextMenu
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.core.PlaceholderRole
import com.android.everytalk.ui.theme.ChatDimensions
import com.android.everytalk.ui.theme.chatColors

import com.android.everytalk.ui.components.EnhancedMarkdownText
import com.android.everytalk.ui.components.WebPreviewDialog
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogSubtextColor
import com.android.everytalk.ui.components.scrollFadeEdge
import com.android.everytalk.ui.components.streaming.StreamBlock
import com.android.everytalk.ui.components.streaming.StreamBlocksRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.first

internal fun shouldResetTransientBottomReserve(
    previousConversationId: String?,
    currentConversationId: String,
    isApiCalling: Boolean
): Boolean = !isApiCalling &&
    !previousConversationId.isNullOrBlank() &&
    previousConversationId != currentConversationId

internal fun shouldEnableUserScrollForPinnedUserBubble(
    grokScrollCompleted: Boolean,
    isApiCalling: Boolean,
    hasPinnedUserMessage: Boolean,
    hasDynamicBottomReserve: Boolean
): Boolean = grokScrollCompleted

internal fun shouldClearTransientBottomReserveOnStreamChange(isApiCalling: Boolean): Boolean = false

internal fun resolvePinnedAnchorPreScrollConsumption(
    availableY: Float,
    currentY: Int,
    targetY: Int,
    hasPinnedUserMessage: Boolean,
    hasDynamicBottomReserve: Boolean,
    grokScrollCompleted: Boolean
): Float = 0f

internal fun pinnedAnchorLayoutVersion(
    totalItemsCount: Int,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    visibleItemsSizeSum: Int,
    visibleItemsOffsetSum: Int
): Long {
    var result = totalItemsCount.toLong()
    result = result * 31 + firstVisibleItemIndex
    result = result * 31 + firstVisibleItemScrollOffset
    result = result * 31 + visibleItemsSizeSum
    result = result * 31 + visibleItemsOffsetSum
    return result
}

internal fun restorePinnedBubbleAnchorForSession(
    savedAnchorY: Int,
    isPinnedRuntimeActive: Boolean
): Int = if (isPinnedRuntimeActive && savedAnchorY > 0) savedAnchorY else -1

internal fun shouldDispatchImageLoadedToBottomScroller(
    isApiCalling: Boolean,
    isAtBottom: Boolean,
    hasPinnedUserMessage: Boolean,
    hasDynamicBottomReserve: Boolean
): Boolean = !isApiCalling && isAtBottom && !(hasPinnedUserMessage && hasDynamicBottomReserve)

internal fun shouldShrinkDynamicBottomReserveForVisibleGap(
    hasPinnedUserMessage: Boolean,
    preservePinnedReserve: Boolean
): Boolean = !preservePinnedReserve || !hasPinnedUserMessage

internal fun resolveDynamicBottomReserveForVisibleGap(
    currentReservePx: Int,
    visibleGapPx: Int,
    minPinnedReservePx: Int,
    maxPinnedReservePx: Int,
    hasPinnedUserMessage: Boolean
): Int {
    val safeCurrent = currentReservePx.coerceAtLeast(0)
    val safeGap = visibleGapPx.coerceAtLeast(0)
    return safeGap.coerceAtMost(safeCurrent)
}

@Composable
private fun rememberHistoryLoadingShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "historyLoadingShimmer")
    val shimmerOffset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "historyLoadingShimmerOffset",
    )
    val baseColor = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    } else {
        Color(0xFFDFDFE2)
    }
    val highlightColor = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    } else {
        baseColor.copy(alpha = 0.20f)
    }
    return Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(shimmerOffset - 3000f, 0f),
        end = Offset(shimmerOffset, 0f),
    )
}

@Composable
internal fun HistoryLoadingBubblePlaceholderItem(
    role: PlaceholderRole,
    widthFraction: Float,
    estimatedHeight: Dp,
) {
    val shimmerBrush = rememberHistoryLoadingShimmerBrush()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (role == PlaceholderRole.User) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction.coerceIn(0.1f, 1f))
                .height(estimatedHeight)
                .clip(RoundedCornerShape(18.dp))
                .background(shimmerBrush),
        )
    }
}

@Composable
fun ChatMessagesList(
    chatItems: List<ChatListItem>,
    viewModel: AppViewModel,
    listState: LazyListState,
    scrollStateManager: com.android.everytalk.ui.screens.MainScreen.chat.text.state.ChatScrollStateManager,
    scrollSessionKey: String,
    conversationId: String = scrollSessionKey,
    bubbleMaxWidth: Dp,
    onShowAiMessageOptions: (Message) -> Unit,
    onImageLoaded: () -> Unit,
    onImageClick: (String) -> Unit,
    additionalBottomPadding: Dp = 0.dp
) {
    val reasoningHeightMap = remember { mutableMapOf<String, Int>() }
    // 防止 AnimatedItems 等状态在重组时被重复触发

    var isContextMenuVisible by remember { mutableStateOf(false) }
    var contextMenuMessage by remember { mutableStateOf<Message?>(null) }
    var contextMenuPressOffset by remember { mutableStateOf(Offset.Zero) }
    // 防重复触发：在极短时间内只允许一次预览弹出
    var lastImagePreviewAt by remember { mutableStateOf(0L) }

    val isApiCalling by viewModel.isTextApiCalling.collectAsState()
    val currentStreamingId by viewModel.currentTextStreamingAiMessageId.collectAsState()
    val density = LocalDensity.current
    
    // Performance monitoring: Track recomposition count for ChatMessagesList
    // This helps verify that the overall list recomposition is reduced
    // Requirements: 1.4, 3.4
    val listRecompositionCount = remember { mutableStateOf(0) }
    LaunchedEffect(chatItems.size, isApiCalling, currentStreamingId) {
        listRecompositionCount.value++
        if (listRecompositionCount.value % 5 == 0) {
            android.util.Log.d(
                "ChatMessagesList",
                "List recomposed ${listRecompositionCount.value} times (items: ${chatItems.size}, streaming: $isApiCalling)"
            )
        }
    }

    val isAtBottom by scrollStateManager.isAtBottom
    
    val userMessageIndices = remember(chatItems) {
        val indices = mutableListOf<Int>()
        chatItems.forEachIndexed { i, item ->
            if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage) {
                indices.add(i)
            }
        }
        indices
    }
    
    val lastUserMessageInfo = remember(chatItems) {
        var index = -1
        var id: String? = null
        chatItems.forEachIndexed { i, item ->
            if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage) {
                index = i
                id = item.stableId
            }
        }
        Pair(index, id)
    }
    
    var dynamicBottomPaddingTarget by remember(scrollSessionKey) { mutableStateOf(0.dp) }
    var dynamicBottomPaddingImmediate by remember(scrollSessionKey) { mutableStateOf(0.dp) }
    var grokScrollCompleted by remember(scrollSessionKey) { mutableStateOf(true) }
    var pinnedUserMessageId by remember(scrollSessionKey) { mutableStateOf<String?>(null) }
    var firstBubbleScreenY by remember(scrollSessionKey) {
        mutableStateOf(-1)
    }
    var previousConversationIdForReserve by remember { mutableStateOf<String?>(null) }

    var skipAnimation by remember(scrollSessionKey) { mutableStateOf(true) }

    fun clearTransientBottomReserve() {
        skipAnimation = true
        pinnedUserMessageId = null
        dynamicBottomPaddingTarget = 0.dp
        dynamicBottomPaddingImmediate = 0.dp
    }

    // 流式期间用快速动画，结束归零用慢动画
    val isTargetZero = dynamicBottomPaddingTarget <= 0.dp
    val dynamicBottomPaddingAnimated by androidx.compose.animation.core.animateDpAsState(
        targetValue = dynamicBottomPaddingTarget,
        animationSpec = when {
            skipAnimation -> androidx.compose.animation.core.snap()
            isTargetZero -> androidx.compose.animation.core.tween(
                durationMillis = 800,
                easing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)
            )
            else -> androidx.compose.animation.core.tween(
                durationMillis = 80,
                easing = androidx.compose.animation.core.LinearEasing
            )
        },
        finishedListener = { if (skipAnimation) skipAnimation = false },
        label = "dynamicBottomPadding"
    )

    // 始终用 animated 值，避免 immediate/animated 切换时的跳变
    val dynamicBottomPadding = dynamicBottomPaddingAnimated

    LaunchedEffect(scrollSessionKey) {
        grokScrollCompleted = true
        clearTransientBottomReserve()
        firstBubbleScreenY = restorePinnedBubbleAnchorForSession(
            savedAnchorY = viewModel.getScrollState(scrollSessionKey)?.firstBubbleScreenY ?: -1,
            isPinnedRuntimeActive = pinnedUserMessageId != null && dynamicBottomPaddingTarget > 0.dp
        )
        android.util.Log.d("GrokScroll", "Session changed, reset state, restored firstBubbleScreenY=$firstBubbleScreenY")
    }

    LaunchedEffect(conversationId, isApiCalling) {
        if (shouldResetTransientBottomReserve(previousConversationIdForReserve, conversationId, isApiCalling)) {
            grokScrollCompleted = true
            clearTransientBottomReserve()
            firstBubbleScreenY = restorePinnedBubbleAnchorForSession(
                savedAnchorY = viewModel.getScrollState(conversationId)?.firstBubbleScreenY ?: -1,
                isPinnedRuntimeActive = pinnedUserMessageId != null && dynamicBottomPaddingTarget > 0.dp
            )
            android.util.Log.d("GrokScroll", "Conversation changed, cleared transient bottom reserve")
        }
        previousConversationIdForReserve = conversationId
    }
    
    LaunchedEffect(isApiCalling) {
        if (shouldClearTransientBottomReserveOnStreamChange(isApiCalling)) {
            clearTransientBottomReserve()
        }
    }

    // 流式输出期间，动态缩小底部 padding，避免出现多余空白
    // GrokScroll 结束时已做初始 shrink，此处只负责 AI 内容增长时持续缩减
    LaunchedEffect(isApiCalling) {
        if (!isApiCalling) return@LaunchedEffect

        snapshotFlow { grokScrollCompleted }.first { it }

        if (dynamicBottomPaddingTarget <= 0.dp) return@LaunchedEffect

        snapshotFlow {
            val li = listState.layoutInfo
            val viewportHeight = li.viewportEndOffset - li.viewportStartOffset
            if (viewportHeight <= 0) return@snapshotFlow -1

            val lastRealItem = li.visibleItemsInfo.lastOrNull { it.key != "dynamic_padding_spacer" }
                ?: return@snapshotFlow -1

            val contentBottomInViewport = lastRealItem.offset + lastRealItem.size
            val gap = li.viewportEndOffset - contentBottomInViewport - li.afterContentPadding
            gap.coerceAtLeast(0)
        }.collect { gapPx ->
            if (gapPx < 0) return@collect
            val newPadding = with(density) { gapPx.toDp() }
            if (newPadding < dynamicBottomPaddingTarget) {
                dynamicBottomPaddingImmediate = newPadding
                dynamicBottomPaddingTarget = newPadding
            }
        }
    }

    LaunchedEffect(firstBubbleScreenY) {
        if (firstBubbleScreenY > 0) {
            val current = viewModel.getScrollState(scrollSessionKey)
            if (current == null || current.firstBubbleScreenY != firstBubbleScreenY) {
                viewModel.cacheScrollState(
                    scrollSessionKey,
                    (current ?: com.android.everytalk.statecontroller.ConversationScrollState()).copy(
                        firstBubbleScreenY = firstBubbleScreenY
                    )
                )
            }
        }
    }

    LaunchedEffect(pinnedUserMessageId, isApiCalling, grokScrollCompleted, firstBubbleScreenY) {
        val pinnedId = pinnedUserMessageId ?: return@LaunchedEffect
        if (!grokScrollCompleted) return@LaunchedEffect
        val targetY = firstBubbleScreenY
        if (targetY <= 0) return@LaunchedEffect

        val stableWindowNanos = 50_000_000L
        var stableSinceNanos = 0L
        var lastLayoutVersion = listState.layoutInfo.run {
            pinnedAnchorLayoutVersion(
                totalItemsCount = totalItemsCount,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                visibleItemsSizeSum = visibleItemsInfo.sumOf { it.size },
                visibleItemsOffsetSum = visibleItemsInfo.sumOf { it.offset }
            )
        }
        while (true) {
            val frameNanos = withFrameNanos { it }
            val li = listState.layoutInfo
            val layoutVersion = pinnedAnchorLayoutVersion(
                totalItemsCount = li.totalItemsCount,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                visibleItemsSizeSum = li.visibleItemsInfo.sumOf { it.size },
                visibleItemsOffsetSum = li.visibleItemsInfo.sumOf { it.offset }
            )
            val layoutChanged = layoutVersion != lastLayoutVersion
            lastLayoutVersion = layoutVersion

            val item = li.visibleItemsInfo.firstOrNull { it.key == pinnedId }
                ?: continue
            val currentY = item.offset - li.viewportStartOffset
            val drift = currentY - targetY

            if (kotlin.math.abs(drift) > 1) {
                stableSinceNanos = 0L
                val consumed = listState.scrollBy(drift.toFloat())
                val missing = kotlin.math.abs(drift.toFloat()) - kotlin.math.abs(consumed)
                if (drift > 0 && missing > 0.5f) {
                    val missingDp = with(density) { missing.toDp() }
                    dynamicBottomPaddingImmediate += missingDp
                    dynamicBottomPaddingTarget += missingDp
                }
            } else {
                if (stableSinceNanos == 0L) {
                    stableSinceNanos = frameNanos
                }
                val stableDurationNanos = frameNanos - stableSinceNanos
                if (stableDurationNanos >= stableWindowNanos && dynamicBottomPaddingTarget > 0.dp) {
                    val lastRealItem = li.visibleItemsInfo.lastOrNull { it.key != "dynamic_padding_spacer" }
                    if (lastRealItem != null) {
                        val contentBottom = lastRealItem.offset + lastRealItem.size
                        val gapPx = (li.viewportEndOffset - contentBottom - li.afterContentPadding)
                            .coerceAtLeast(0)
                        val gapDp = with(density) { gapPx.toDp() }
                        if (gapDp < dynamicBottomPaddingTarget) {
                            dynamicBottomPaddingImmediate = gapDp
                            dynamicBottomPaddingTarget = gapDp
                        }
                    }
                }
                if (!layoutChanged && stableDurationNanos >= stableWindowNanos) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        pinnedAnchorLayoutVersion(
                            totalItemsCount = info.totalItemsCount,
                            firstVisibleItemIndex = listState.firstVisibleItemIndex,
                            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                            visibleItemsSizeSum = info.visibleItemsInfo.sumOf { it.size },
                            visibleItemsOffsetSum = info.visibleItemsInfo.sumOf { it.offset }
                        )
                    }
                        .first { it != lastLayoutVersion }
                    stableSinceNanos = 0L
                }
            }
        }
    }
    
    val fadeBackgroundColor = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .scrollFadeEdge(listState = listState, backgroundColor = fadeBackgroundColor)
    ) {
            val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val topPadding = statusBarTop + 72.dp
            val density = LocalDensity.current
            val topPaddingPx = with(density) { topPadding.toPx().toInt() }
            
            val lastSentUserMessageId by viewModel.lastSentUserMessageId.collectAsState()
            
            LaunchedEffect(lastSentUserMessageId) {
                val sentId = lastSentUserMessageId ?: return@LaunchedEffect
                android.util.Log.d("GrokScroll", "Triggered by lastSentUserMessageId=$sentId")
                
                val currentItems = viewModel.chatListItems.first { items ->
                    items.any { item ->
                        item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage &&
                            item.stableId == sentId
                    }
                }
                val lastUserIndex = currentItems.indexOfLast { item ->
                    item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage &&
                        item.stableId == sentId
                }
                val currentUserMessageIndices = currentItems.mapIndexedNotNull { index, item ->
                    if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage) index else null
                }
                
                android.util.Log.d("GrokScroll", "Found sent message at index=$lastUserIndex")
                
                val li = listState.layoutInfo
                val firstUserIndex = currentUserMessageIndices.firstOrNull() ?: -1
                
                if (currentUserMessageIndices.size == 1) {
                    val firstItem = li.visibleItemsInfo.firstOrNull { it.index == firstUserIndex }
                        ?: kotlinx.coroutines.withTimeoutOrNull(2000) {
                            snapshotFlow {
                                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == firstUserIndex }
                            }.first { it != null }
                        }
                    firstBubbleScreenY = if (firstItem != null) {
                        firstItem.offset - listState.layoutInfo.viewportStartOffset
                    } else {
                        topPaddingPx
                    }
                    android.util.Log.d("GrokScroll", "First bubble Y: $firstBubbleScreenY")
                    viewModel.consumeLastSentUserMessageId()
                    return@LaunchedEffect
                }
                
                android.util.Log.d("GrokScroll", "Consumed, proceeding with scroll")
                
                grokScrollCompleted = false
                pinnedUserMessageId = sentId
                try {
                    if (firstBubbleScreenY < 0) {
                        firstBubbleScreenY = topPaddingPx
                    }
                    val targetScreenY = firstBubbleScreenY
                    
                    val viewportHeight = li.viewportEndOffset - li.viewportStartOffset
                    val paddingDp = with(density) { viewportHeight.toDp() }
                    dynamicBottomPaddingImmediate = paddingDp
                    dynamicBottomPaddingTarget = paddingDp

                    // 等待 spacer 被添加到布局中（currentItems + spacer）
                    val expectedItemCount = currentItems.size + 1
                    kotlinx.coroutines.withTimeoutOrNull(500) {
                        snapshotFlow { listState.layoutInfo.totalItemsCount }
                            .first { it >= expectedItemCount }
                    }

                    val initialItem = kotlinx.coroutines.withTimeoutOrNull(300) {
                        snapshotFlow {
                            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastUserIndex }
                        }.first { it != null }
                    }

                    if (initialItem == null) {
                        listState.animateScrollToItem(lastUserIndex, scrollOffset = 0)
                        kotlinx.coroutines.withTimeoutOrNull(300) {
                            snapshotFlow {
                                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastUserIndex }
                            }.first { it != null }
                        }
                    }

                    val startInfo = listState.layoutInfo
                    val startItem = startInfo.visibleItemsInfo.firstOrNull { it.index == lastUserIndex }
                    if (startItem != null) {
                        val startY = startItem.offset - startInfo.viewportStartOffset
                        val distancePx = startY - targetScreenY
                        if (kotlin.math.abs(distancePx) > 4) {
                            val durationMs = (240 + kotlin.math.abs(distancePx) * 0.35f).toInt().coerceIn(260, 520)
                            val easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f)
                            val startNanos = withFrameNanos { it }
                            var previous = 0f
                            while (true) {
                                val frameNanos = withFrameNanos { it }
                                val elapsedMs = (frameNanos - startNanos) / 1_000_000f
                                val fraction = (elapsedMs / durationMs).coerceIn(0f, 1f)
                                val current = distancePx * easing.transform(fraction)
                                listState.scrollBy(current - previous)
                                previous = current
                                if (fraction >= 1f) break
                            }
                            val remaining = distancePx - previous
                            if (kotlin.math.abs(remaining) > 0.5f) {
                                listState.scrollBy(remaining)
                            }
                        }
                    }

                    val li2 = listState.layoutInfo
                    val currItem = li2.visibleItemsInfo.firstOrNull { it.index == lastUserIndex }
                    if (currItem != null) {
                        val actualY = currItem.offset - li2.viewportStartOffset
                        val correction = actualY - targetScreenY
                        if (kotlin.math.abs(correction) > 4) {
                            listState.scrollBy(correction.toFloat())
                        }
                        android.util.Log.d("GrokScroll", "Scrolled: targetY=$targetScreenY, actualY=$actualY, correction=$correction")
                    } else {
                        android.util.Log.d("GrokScroll", "Item not visible after scrollToItem, fallback")
                    }

                    android.util.Log.d("GrokScroll", "Keep padding until API done")

                    // 立即缩减多余空白
                    val liAfter = listState.layoutInfo
                    val lastReal = liAfter.visibleItemsInfo.lastOrNull { it.key != "dynamic_padding_spacer" }
                    if (lastReal != null) {
                        val gapPx = (liAfter.viewportEndOffset - (lastReal.offset + lastReal.size) - liAfter.afterContentPadding)
                            .coerceAtLeast(0)
                        val gapDp = with(density) { gapPx.toDp() }
                        if (gapDp < dynamicBottomPaddingImmediate) {
                            dynamicBottomPaddingImmediate = gapDp
                            dynamicBottomPaddingTarget = gapDp
                        }
                    }

                    viewModel.consumeLastSentUserMessageId()
                    android.util.Log.d("GrokScroll", "Scroll sequence completed")
                } finally {
                    grokScrollCompleted = true
                }
            }
            
            // 前端消息列表渲染。
            // LazyColumn 只渲染屏幕附近的消息，适合长对话；listState 负责记录滚动位置。
            LazyColumn(
                state = listState,
                reverseLayout = false,
                userScrollEnabled = shouldEnableUserScrollForPinnedUserBubble(
                    grokScrollCompleted = grokScrollCompleted,
                    isApiCalling = isApiCalling,
                    hasPinnedUserMessage = pinnedUserMessageId != null,
                    hasDynamicBottomReserve = dynamicBottomPaddingTarget > 0.dp
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollStateManager.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = 6.dp,
                    end = 16.dp,
                    top = topPadding,
                    bottom = additionalBottomPadding + 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(
            items = chatItems,
            key = { _, item -> item.stableId },
            contentType = { _, item -> 
                when (item) {
                    // stableId 和 contentType 用来稳定复用消息气泡，流式回复时界面不会频繁重建。
                    // Merge all AI message types into a single contentType to prevent
                    // item recreation when switching between Streaming/Non-Streaming states.
                    // This allows the inner Composable to handle state transitions smoothly.
                    is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage,
                    is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageStreaming,
                    is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode,
                    is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCodeStreaming -> "AiMessage"
                    else -> item::class.java.simpleName
                }
            }
        ) { index, item ->
            // 判断是否为用户消息，用于决定布局和对齐方式
            val isUserMessage = item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage ||
                (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.ErrorMessage &&
                 viewModel.getMessageById((item as com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.ErrorMessage).messageId)?.sender == com.android.everytalk.data.DataClass.Sender.User)
            
                    // 根据消息类型决定对齐方式和气泡布局
            val itemAlignment = when (item) {
                is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage -> Alignment.CenterEnd
                is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.ErrorMessage -> {
                    val message = viewModel.getMessageById(item.messageId)
                    if (message?.sender == com.android.everytalk.data.DataClass.Sender.User) {
                        Alignment.CenterEnd
                    } else {
                        Alignment.CenterStart
                    }
                }
                else -> Alignment.CenterStart
            }

            Box(
                modifier = if (isUserMessage) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.fillMaxWidth()
                },
                contentAlignment = itemAlignment
            ) {

                    // 根据消息类型渲染不同内容；这里不用 Column 包裹以避免额外布局抖动
                    // 前端消息列表渲染：这里按消息类型分发到不同气泡组件。
                    // 用户消息靠右，AI 和系统消息靠左，附件、思考过程、正文等内容在各自分支里渲染。
                    when (item) {
                        is ChatListItem.LoadingBubblePlaceholder -> {
                            HistoryLoadingBubblePlaceholderItem(
                                role = item.role,
                                widthFraction = item.widthFraction,
                                estimatedHeight = item.estimatedHeightDp.dp,
                            )
                        }

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage -> {
                            // 使用 Row + Arrangement.End，确保用户消息稳定靠右显示
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(
                                    modifier = Modifier.wrapContentWidth(),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    val message = viewModel.getMessageById(item.messageId)
                                    if (message != null) {
                                        if (!item.attachments.isNullOrEmpty()) {
                                            AttachmentsContent(
                                                attachments = item.attachments,
                                                onAttachmentClick = { },
                                                maxWidth = bubbleMaxWidth * ChatDimensions.USER_BUBBLE_WIDTH_RATIO,
                                                message = message,
                                                onEditRequest = { viewModel.requestEditMessage(it) },
                                                onRegenerateRequest = { regeneratedMessage ->
                                                    scrollStateManager.lockAutoScroll()
                                                    viewModel.regenerateAiResponse(regeneratedMessage, isImageGeneration = false, scrollToNewMessage = true)
                                                },
                                                onLongPress = { msg, offset ->
                                                    contextMenuMessage = msg
                                                    contextMenuPressOffset = offset
                                                    isContextMenuVisible = true
                                                },
                                                scrollStateManager = scrollStateManager,
                                                onImageLoaded = onImageLoaded,
                                                bubbleColor = MaterialTheme.chatColors.userBubble,
                                                isAiGenerated = false,
                                                onImageClick = onImageClick
                                            )
                                        }
                                        if (item.text.isNotBlank()) {
                                            // 纯文本用户消息内容
                                            UserOrErrorMessageContent(
                                                message = message,
                                                displayedText = item.text,
                                                showLoadingDots = false,
                                                bubbleColor = MaterialTheme.chatColors.userBubble,
                                                contentColor = MaterialTheme.colorScheme.onSurface,
                                                isError = false,
                                                maxWidth = bubbleMaxWidth * ChatDimensions.USER_BUBBLE_WIDTH_RATIO,
                                                onLongPress = { msg, offset ->
                                                    contextMenuMessage = msg
                                                    contextMenuPressOffset = offset
                                                    isContextMenuVisible = true
                                                },
                                                scrollStateManager = scrollStateManager
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.SystemMessage -> {
                            val message = viewModel.getMessageById(item.messageId)
                            if (message != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    UserOrErrorMessageContent(
                                        message = message,
                                        displayedText = item.text,
                                        showLoadingDots = false,
                                        bubbleColor = MaterialTheme.chatColors.aiBubble,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        isError = false,
                                        maxWidth = bubbleMaxWidth,
                                        onLongPress = { msg, offset ->
                                            contextMenuMessage = msg
                                            contextMenuPressOffset = offset
                                            isContextMenuVisible = true
                                        },
                                        scrollStateManager = scrollStateManager
                                    )
                                }
                            }
                        }

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageReasoning -> {
                            val reasoningCompleteMap = viewModel.textReasoningCompleteMap
                            val streamingReasoning by remember(item.message.id) {
                                viewModel.getStreamingReasoning(item.message.id)
                            }.collectAsState(initial = item.message.reasoning ?: "")
                            val displayedReasoningText =
                                if (currentStreamingId == item.message.id && streamingReasoning.isNotBlank()) {
                                    streamingReasoning
                                } else {
                                    item.message.reasoning ?: ""
                                }
                            val isReasoningStreaming = remember(
                                displayedReasoningText,
                                reasoningCompleteMap[item.message.id],
                                item.message.contentStarted
                            ) {
                                displayedReasoningText.isNotBlank() &&
                                (reasoningCompleteMap[item.message.id] != true) &&
                                !item.message.contentStarted
                            }
                            val isReasoningComplete = reasoningCompleteMap[item.message.id] ?: false

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { newSize ->
                                        val prevHeight = reasoningHeightMap[item.message.id] ?: newSize.height
                                        if (
                                            prevHeight > newSize.height &&
                                            isApiCalling &&
                                            firstBubbleScreenY > 0 &&
                                            pinnedUserMessageId != null
                                        ) {
                                            val deltaDp = with(density) { (prevHeight - newSize.height).toDp() }
                                            dynamicBottomPaddingImmediate += deltaDp
                                            dynamicBottomPaddingTarget += deltaDp
                                        }
                                        reasoningHeightMap[item.message.id] = newSize.height
                                    },
                                contentAlignment = Alignment.CenterStart
                            ) {
                                ReasoningToggleAndContent(
                                    modifier = Modifier.fillMaxWidth(),
                                    currentMessageId = item.message.id,
                                    displayedReasoningText = displayedReasoningText,
                                    isReasoningStreaming = isReasoningStreaming,
                                    isReasoningComplete = isReasoningComplete,
                                    messageIsError = item.message.isError,
                                    mainContentHasStarted = item.message.contentStarted,
                                    reasoningTextColor = MaterialTheme.chatColors.reasoningText,
                                    reasoningToggleDotColor = MaterialTheme.colorScheme.onSurface,
                                    onVisibilityChanged = { }
                                )
                            }
                        }

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage, is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageStreaming -> {
                            val messageId = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage) item.messageId else (item as com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageStreaming).messageId
                            val message = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage) item.message else viewModel.getMessageById(messageId)
                            if (message != null) {
                                val text = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage) item.text else message.text
                                val isStreaming = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageStreaming) true else (currentStreamingId == message.id)
                                if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage && item.hasPendingMath) {
                                    android.util.Log.d(
                                        "ChatMessagesList",
                                        "Pending math block: msgId=${item.messageId.take(8)}, blocks=${item.blocks.size}, hash=${item.blocksHash}"
                                    )
                                }

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    AiMessageItem(
                                        message = message,
                                        text = text,
                                        blocks = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage) {
                                            item.blocks
                                        } else {
                                            emptyList()
                                        },
                                        maxWidth = bubbleMaxWidth,
                                        isStreaming = isStreaming,
                                        messageOutputType = message.outputType,
                                        viewModel = viewModel,
                                        onImageClick = { url ->
                                            val now = SystemClock.elapsedRealtime()
                                            if (now - lastImagePreviewAt > 500) {
                                                lastImagePreviewAt = now
                                                onImageClick(url)
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode, is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCodeStreaming -> {
                            val messageId = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode) item.messageId else (item as com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCodeStreaming).messageId
                            val message = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode) item.message else viewModel.getMessageById(messageId)
                            if (message != null) {
                                val text = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode) item.text else message.text
                                val isStreaming = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCodeStreaming) true else (currentStreamingId == message.id)

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    AiMessageItem(
                                        message = message,
                                        text = text,
                                        maxWidth = bubbleMaxWidth,
                                        isStreaming = isStreaming,
                                        messageOutputType = message.outputType,
                                        viewModel = viewModel,
                                        onImageClick = { url ->
                                            val now = SystemClock.elapsedRealtime()
                                            if (now - lastImagePreviewAt > 500) {
                                                lastImagePreviewAt = now
                                                onImageClick(url)
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageFooter -> {
                            AiMessageFooterItem(
                                message = item.message,
                                viewModel = viewModel,
                            )
                        }


                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.ErrorMessage -> {
                            val message = viewModel.getMessageById(item.messageId)
                            if (message != null) {
                                UserOrErrorMessageContent(
                                    message = message,
                                    displayedText = item.text,
                                    showLoadingDots = false,
                                    bubbleColor = MaterialTheme.chatColors.aiBubble,
                                    contentColor = MaterialTheme.chatColors.errorContent,
                                    isError = true,
                                    maxWidth = bubbleMaxWidth,
                                    onLongPress = { msg, offset ->
                                        contextMenuMessage = msg
                                        contextMenuPressOffset = offset
                                        isContextMenuVisible = true
                                    },
                                    scrollStateManager = scrollStateManager
                                )
                            }
                        }

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.LoadingIndicator -> {
                            Row(
                                modifier = Modifier
                                    .padding(
                                        start = ChatDimensions.HORIZONTAL_PADDING,
                                        top = ChatDimensions.VERTICAL_PADDING,
                                        bottom = ChatDimensions.VERTICAL_PADDING
                                    ),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.Start
                            ) {
                                val defaultText = stringResource(id = R.string.connecting_to_model)
                                val displayText = resolveLoadingStageDisplayText(item.text, defaultText)
                                LoadingStageIndicator(text = displayText)
                            }
                        }
                        
                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.StatusIndicator -> {
                            Row(
                                modifier = Modifier
                                    .padding(
                                        start = ChatDimensions.HORIZONTAL_PADDING,
                                        top = ChatDimensions.VERTICAL_PADDING,
                                        bottom = ChatDimensions.VERTICAL_PADDING
                                    ),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
                
            if (dynamicBottomPadding > 0.dp) {
                item(key = "dynamic_padding_spacer") {
                    Spacer(modifier = Modifier.height(dynamicBottomPadding))
                }
            }
        }

        contextMenuMessage?.let { message ->
            MessageContextMenu(
                isVisible = isContextMenuVisible,
                message = message,
                pressOffset = with(density) {
                    if (message.sender == com.android.everytalk.data.DataClass.Sender.User) {
                        // 文本模式用户气泡：进一步下移以贴近手指
                        Offset(contextMenuPressOffset.x, contextMenuPressOffset.y)
                    } else {
                        Offset(contextMenuPressOffset.x, contextMenuPressOffset.y)
                    }
                },
                onDismiss = { isContextMenuVisible = false },
                onCopy = {
                    viewModel.copyToClipboard(it.text)
                    isContextMenuVisible = false
                },
                onEdit = {
                    viewModel.requestEditMessage(it)
                    isContextMenuVisible = false
                },
                onRegenerate = { regeneratedMessage ->
                    scrollStateManager.lockAutoScroll()
                    viewModel.regenerateAiResponse(regeneratedMessage, isImageGeneration = false, scrollToNewMessage = true)
                    isContextMenuVisible = false
                }
            )
        }
    }
}

enum class ContentType {
    SIMPLE         // 普通内容，使用正常内边距
}

fun detectContentTypeForPadding(text: String): ContentType {
    // 所有内容都使用正常内边距
    return ContentType.SIMPLE
}

internal fun resolveLoadingStageDisplayText(text: String?, defaultText: String): String {
    return text?.takeIf { it.isNotBlank() } ?: defaultText
}

internal fun loadingStageViewportHeightDp(): Float = 34f

internal fun loadingStageMaskHeightDp(): Float = 10f

internal fun loadingStageBreathingDotSizeDp(): Float = 6f

@Composable
private fun LoadingStageIndicator(
    text: String,
    modifier: Modifier = Modifier,
) {
    val viewportHeight = loadingStageViewportHeightDp().dp
    val maskHeight = loadingStageMaskHeightDp().dp
    val breathingDotSize = loadingStageBreathingDotSizeDp().dp
    val infiniteTransition = rememberInfiniteTransition(label = "loadingStage")
    val lineAlpha by infiniteTransition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.52f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loadingStageLineAlpha",
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loadingStageGlowAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(viewportHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
                            Color.Transparent,
                        ),
                        radius = 220f,
                        center = Offset(120f, viewportHeight.value * 1.6f),
                    )
                )
        )

        Box(
            modifier = Modifier
                .padding(start = 6.dp)
                .size(breathingDotSize)
                .background(
                    color = Color.White.copy(alpha = lineAlpha),
                    shape = CircleShape
                )
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(viewportHeight)
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background,
                                    MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                )
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .padding(vertical = maskHeight)
                ) {
                    AnimatedContent(
                        targetState = text,
                        transitionSpec = {
                            (slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(animationSpec = tween(260)) + scaleIn(initialScale = 0.985f, animationSpec = tween(260)))
                                .togetherWith(
                                    slideOutVertically(targetOffsetY = { -it / 2 }) + fadeOut(animationSpec = tween(220)) + scaleOut(targetScale = 0.985f, animationSpec = tween(220))
                                )
                        },
                        label = "LoadingStageTextAnimation"
                    ) { stageText ->
                        Text(
                            text = stageText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                            maxLines = 1,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(maskHeight)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background,
                                    MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                )
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(maskHeight)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                    MaterialTheme.colorScheme.background,
                                )
                            )
                        )
                )
            }
        }
    }
}


@Composable
fun AiMessageItem(
    message: Message,
    text: String,
    blocks: List<StreamBlock> = emptyList(),
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    isStreaming: Boolean,
    messageOutputType: String,
    viewModel: AppViewModel,
    onImageClick: ((String) -> Unit)? = null
) {
    val shape = RectangleShape
    val aiReplyMessageDescription = stringResource(id = R.string.ai_reply_message)

    var previewCode by remember { mutableStateOf<String?>(null) }
    var previewLanguage by remember { mutableStateOf("text") }

    if (previewCode != null) {
        WebPreviewDialog(
            code = previewCode!!,
            language = previewLanguage,
            onDismiss = {
                previewCode = null
                previewLanguage = "text"
            }
        )
    }

    val density = LocalDensity.current
    var lastMeasuredHeightPx by remember(message.id) { mutableStateOf(0) }

    Row(
        modifier = modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(max = maxWidth)
                .semantics { contentDescription = aiReplyMessageDescription },
            shape = shape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 0.dp
        ) {
            // 流式期间保持 minHeight 只增不减，防止向上跳；流式结束后不再约束
            val minHeightModifier = if (isStreaming && lastMeasuredHeightPx > 0) {
                Modifier.heightIn(min = with(density) { lastMeasuredHeightPx.toDp() })
            } else {
                Modifier
            }
            Box(
                modifier = minHeightModifier
                    .padding(
                        horizontal = ChatDimensions.BUBBLE_INNER_PADDING_HORIZONTAL,
                        vertical = ChatDimensions.BUBBLE_INNER_PADDING_VERTICAL
                    )
                    .onSizeChanged { size ->
                        if (isStreaming && size.height > lastMeasuredHeightPx) {
                            lastMeasuredHeightPx = size.height
                        }
                        if (!isStreaming) {
                            lastMeasuredHeightPx = size.height
                        }
                    }
            ) {
                val streamingRenderState by remember(message.id, viewModel) {
                    viewModel.getStreamingRenderState(message.id)
                }.collectAsState()

                val shouldPreferStreamingContent =
                    isStreaming ||
                        streamingRenderState.isStreaming

                val effectiveContent = if (shouldPreferStreamingContent) {
                    streamingRenderState.content.ifBlank { message.text }
                } else {
                    // 流式结束后，优先使用 message.text；但如果 message.text 为空或明显短于
                    // streamingRenderState.content，说明存在同步竞态，使用流式内容兜底防止闪烁
                    if (message.text.isBlank() && streamingRenderState.content.isNotBlank()) {
                        streamingRenderState.content
                    } else if (message.text.length < streamingRenderState.content.length * 0.8 && streamingRenderState.content.isNotBlank()) {
                        streamingRenderState.content
                    } else {
                        message.text
                    }
                }

                val renderMessage = if (effectiveContent == message.text) {
                    message
                } else {
                    message.copy(text = effectiveContent)
                }

                val useStreamingBlocks =
                    streamingRenderState.content == effectiveContent && streamingRenderState.blocks.isNotEmpty()

                val renderBlocks = when {
                    messageOutputType == "code" -> emptyList()
                    useStreamingBlocks -> streamingRenderState.blocks
                    blocks.isNotEmpty() && (text == effectiveContent || message.text == effectiveContent) -> blocks
                    else -> emptyList()
                }

                if (renderBlocks.isNotEmpty()) {
                    StreamBlocksRenderer(
                        message = renderMessage,
                        blocks = renderBlocks,
                        committedBlocks = if (useStreamingBlocks) streamingRenderState.committedBlocks else emptyList(),
                        tailBlocks = if (useStreamingBlocks) streamingRenderState.tailBlocks else emptyList(),
                        committedBlocksHash = if (useStreamingBlocks) streamingRenderState.committedBlocksHash else "",
                        tailBlocksHash = if (useStreamingBlocks) streamingRenderState.tailBlocksHash else "",
                        nativeMarkdownBlocks = if (useStreamingBlocks) streamingRenderState.nativeMarkdownBlocks else emptyList(),
                        committedNativeMarkdownBlocks = if (useStreamingBlocks) {
                            streamingRenderState.committedNativeMarkdownBlocks
                        } else {
                            emptyList()
                        },
                        tailNativeMarkdownBlocks = if (useStreamingBlocks) {
                            streamingRenderState.tailNativeMarkdownBlocks
                        } else {
                            emptyList()
                        },
                        nativeMarkdownBlocksHash = if (useStreamingBlocks) streamingRenderState.nativeMarkdownBlocksHash else "",
                        committedNativeMarkdownBlocksHash = if (useStreamingBlocks) {
                            streamingRenderState.committedNativeMarkdownBlocksHash
                        } else {
                            ""
                        },
                        tailNativeMarkdownBlocksHash = if (useStreamingBlocks) {
                            streamingRenderState.tailNativeMarkdownBlocksHash
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        messageOutputType = messageOutputType,
                        viewModel = viewModel,
                        onLongPress = {},
                        onImageClick = onImageClick,
                        onCodePreviewRequested = { lang, code ->
                            previewLanguage = lang
                            previewCode = code
                        },
                        onCodeCopied = {
                            viewModel.showSnackbar("已复制代码")
                        },
                    )
                } else {
                    EnhancedMarkdownText(
                        message = renderMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        isStreaming = shouldPreferStreamingContent,
                        messageOutputType = messageOutputType,
                        onImageClick = onImageClick,
                        onCodePreviewRequested = { lang, code ->
                            previewLanguage = lang
                            previewCode = code
                        },
                        onCodeCopied = {
                            viewModel.showSnackbar("已复制代码")
                        },
                        viewModel = viewModel,
                        contentOverride = effectiveContent,
                        contentKeyOverride = message.id,
                        disableStreamingSubscription = true
                    )
                }
            }
        }
    }
}

@Composable
fun AiMessageFooterItem(
    message: Message,
    viewModel: AppViewModel,
    onShowOptions: (Message) -> Unit = {},
) {
    var showPopupMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ChatDimensions.HORIZONTAL_PADDING)
    ) {
        if (!message.webSearchResults.isNullOrEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                TextButton(
                    onClick = {
                        viewModel.showSourcesDialog(message.webSearchResults)
                    },
                ) {
                    Text(stringResource(id = R.string.view_sources, message.webSearchResults.size))
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val latestMessage = viewModel.getMessageById(message.id) ?: message
                    viewModel.copyToClipboard(latestMessage.text)
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = "复制",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            IconButton(
                onClick = {
                    val latestMessage = viewModel.getMessageById(message.id) ?: message
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, latestMessage.text)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "分享"))
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_share),
                    contentDescription = "分享",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Box {
                IconButton(
                    onClick = { showPopupMenu = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_dots_horizontal),
                        contentDescription = "更多",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                AiMessagePopupMenu(
                    expanded = showPopupMenu,
                    onDismiss = { showPopupMenu = false },
                    onRegenerate = {
                        val latestMessage = viewModel.getMessageById(message.id) ?: message
                        viewModel.regenerateAiResponse(latestMessage, scrollToNewMessage = true)
                    },
                    modelName = message.modelName,
                    availableModels = viewModel.apiConfigs.collectAsState().value,
                    selectedModelId = viewModel.selectedApiConfig.collectAsState().value?.id,
                    onChangeModelConfirm = { config ->
                        val latestMessage = viewModel.getMessageById(message.id) ?: message
                        viewModel.regenerateAiResponseWithConfig(latestMessage, config, scrollToNewMessage = true)
                    },
                    onExport = {
                        val latestMessage = viewModel.getMessageById(message.id) ?: message
                        viewModel.exportMessageText(latestMessage.text)
                    }
                )
            }
        }
    }
}

@Composable
private fun AiMessagePopupMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRegenerate: () -> Unit,
    modelName: String?,
    availableModels: List<ApiConfig>,
    selectedModelId: String?,
    onChangeModelConfirm: (ApiConfig) -> Unit,
    onExport: () -> Unit,
) {
    var showPopup by remember { mutableStateOf(false) }
    val scaleAnim = remember { Animatable(0.8f) }
    val alphaAnim = remember { Animatable(0f) }

    val emphasizedDecelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val decelerateEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    LaunchedEffect(expanded) {
        if (expanded) {
            showPopup = true
            scaleAnim.snapTo(0.8f)
            alphaAnim.snapTo(0f)
            coroutineScope {
                launch { scaleAnim.animateTo(1f, tween(120, easing = emphasizedDecelerate)) }
                launch { alphaAnim.animateTo(1f, tween(30, easing = decelerateEasing)) }
            }
        } else if (showPopup) {
            coroutineScope {
                launch { alphaAnim.animateTo(0f, tween(75, easing = decelerateEasing)) }
                launch {
                    delay(74)
                    scaleAnim.snapTo(0.8f)
                }
            }
            showPopup = false
        }
    }

    var showModelPicker by remember { mutableStateOf(false) }
    var pendingConfirmModel by remember { mutableStateOf<ApiConfig?>(null) }

    if (!showPopup) return

    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF212121) else Color(0xFFFFFFFF)
    val textColor = MaterialTheme.colorScheme.onSurface
    val iconTint = textColor
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF0D0D0D).copy(alpha = 0.05f)

    Popup(
        alignment = Alignment.BottomStart,
        onDismissRequest = {
            showModelPicker = false
            onDismiss()
        },
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(min = 200.dp)
                .graphicsLayer {
                    this.scaleX = scaleAnim.value
                    this.scaleY = scaleAnim.value
                    this.alpha = alphaAnim.value
                    this.transformOrigin = TransformOrigin(0f, 1f)
                }
                .shadow(8.dp, RoundedCornerShape(28.dp))
                .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            color = cardBg
        ) {
            if (showModelPicker) {
                ModelPickerPopupContent(
                    availableModels = availableModels,
                    selectedModelId = selectedModelId,
                    textColor = textColor,
                    iconTint = iconTint,
                    onModelSelected = { pendingConfirmModel = it }
                )
            } else {
                Column(modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(vertical = 12.dp)
                ) {
                    PopupMenuItem(
                        painter = painterResource(R.drawable.ic_regenerate),
                        text = "重新回答",
                        textColor = textColor,
                        iconTint = iconTint,
                        onClick = { onRegenerate(); onDismiss() }
                    )
                    PopupMenuItem(
                        painter = painterResource(R.drawable.ic_robot_head),
                        text = modelName ?: "切换模型",
                        textColor = textColor,
                        iconTint = iconTint,
                        onClick = { showModelPicker = true }
                    )
                    PopupMenuItem(
                        painter = painterResource(R.drawable.ic_export),
                        text = "导出文本",
                        textColor = textColor,
                        iconTint = iconTint,
                        onClick = { onExport(); onDismiss() }
                    )
                }
            }
        }
    }

    pendingConfirmModel?.let { config ->
        ConfirmModelRegenerateDialog(
            modelName = config.name.takeIf { it.isNotBlank() } ?: config.model,
            onBack = { pendingConfirmModel = null },
            onConfirm = {
                pendingConfirmModel = null
                showModelPicker = false
                onChangeModelConfirm(config)
                onDismiss()
            }
        )
    }
}

@Composable
private fun ModelPickerPopupContent(
    availableModels: List<ApiConfig>,
    selectedModelId: String?,
    textColor: Color,
    iconTint: Color,
    onModelSelected: (ApiConfig) -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 320.dp)
            .heightIn(max = 360.dp)
            .padding(vertical = 12.dp)
    ) {
        if (availableModels.isEmpty()) {
            Text(
                text = "当前无可用模型",
                color = textColor.copy(alpha = 0.7f),
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        } else {
            LazyColumn {
                items(availableModels, key = { it.id }) { config ->
                    val displayName = config.name.takeIf { it.isNotBlank() } ?: config.model
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onModelSelected(config) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_robot_head),
                            contentDescription = null,
                            tint = if (config.id == selectedModelId) Color(0xFF66B5FF) else iconTint,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayName,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (config.model != displayName) {
                                Text(
                                    text = config.model,
                                    fontSize = 12.sp,
                                    color = textColor.copy(alpha = 0.65f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmModelRegenerateDialog(
    modelName: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cardBg = appDialogContainerColor()
    val textColor = appDialogContentColor()
    val subtextColor = appDialogSubtextColor()

    AlertDialog(
        onDismissRequest = onBack,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = cardBg,
        title = {
            Text(
                text = "切换模型重新回答",
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Text(
                text = "将使用“$modelName”重新回答这个问题。",
                color = subtextColor,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确定", color = Color(0xFF66B5FF), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onBack) {
                Text("返回", color = textColor)
            }
        }
    )
}

@Composable
private fun PopupMenuItem(
    painter: androidx.compose.ui.graphics.painter.Painter,
    text: String,
    textColor: Color,
    iconTint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

