@file:OptIn(ExperimentalFoundationApi::class)
package com.android.everytalk.ui.screens.MainScreen.chat.text.ui
import com.android.everytalk.R

import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.ui.screens.BubbleMain.Main.AttachmentsContent
import com.android.everytalk.ui.screens.BubbleMain.Main.ReasoningToggleAndContent
import com.android.everytalk.ui.screens.BubbleMain.Main.UserOrErrorMessageContent
import com.android.everytalk.ui.screens.BubbleMain.Main.MessageContextMenu
import com.android.everytalk.ui.theme.ChatDimensions
import com.android.everytalk.ui.theme.chatColors

import com.android.everytalk.ui.components.EnhancedMarkdownText
import com.android.everytalk.ui.components.StableMarkdownText
import com.android.everytalk.ui.components.markdown.MarkdownRenderer
import com.android.everytalk.ui.components.streaming.StreamBlocksRenderer
import com.android.everytalk.ui.components.WebPreviewDialog
import kotlinx.coroutines.delay
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.first

@Composable
fun ChatMessagesList(
    chatItems: List<com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem>,
    viewModel: AppViewModel,
    listState: LazyListState,
    scrollStateManager: com.android.everytalk.ui.screens.MainScreen.chat.text.state.ChatScrollStateManager,
    scrollSessionKey: String,
    bubbleMaxWidth: Dp,
    onShowAiMessageOptions: (Message) -> Unit,
    onImageLoaded: () -> Unit,
    onImageClick: (String) -> Unit,
    additionalBottomPadding: Dp = 0.dp
) {
    val haptic = LocalHapticFeedback.current
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
        val saved = viewModel.getScrollState(scrollSessionKey)?.firstBubbleScreenY ?: -1
        mutableStateOf(saved)
    }

    var skipAnimation by remember(scrollSessionKey) { mutableStateOf(true) }

    val dynamicBottomPaddingAnimated by androidx.compose.animation.core.animateDpAsState(
        targetValue = dynamicBottomPaddingTarget,
        animationSpec = if (skipAnimation) {
            androidx.compose.animation.core.snap()
        } else {
            androidx.compose.animation.core.tween(
                durationMillis = 200,
                easing = androidx.compose.animation.core.CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f)
            )
        },
        finishedListener = { if (skipAnimation) skipAnimation = false },
        label = "dynamicBottomPadding"
    )

    val dynamicBottomPadding = if (isApiCalling) {
        dynamicBottomPaddingImmediate
    } else {
        dynamicBottomPaddingAnimated
    }

    LaunchedEffect(scrollSessionKey) {
        skipAnimation = true
        grokScrollCompleted = true
        pinnedUserMessageId = null
        dynamicBottomPaddingTarget = 0.dp
        dynamicBottomPaddingImmediate = 0.dp
        firstBubbleScreenY = viewModel.getScrollState(scrollSessionKey)?.firstBubbleScreenY ?: -1
        android.util.Log.d("GrokScroll", "Session changed, reset state, restored firstBubbleScreenY=$firstBubbleScreenY")
        
        // 等待 chatItems 稳定（MessageItemsController 后台处理完成）
        if (chatItems.isEmpty()) {
            kotlinx.coroutines.withTimeoutOrNull(500) {
                snapshotFlow { chatItems.size }.first { it > 0 }
            }
        }
        
        if (firstBubbleScreenY > 0 && chatItems.isNotEmpty()) {
            val lastUserIdx = chatItems.indexOfLast { 
                it is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage 
            }
            if (lastUserIdx > 0) {
                kotlinx.coroutines.delay(100)
                val viewportHeight = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                val paddingDp = with(density) { viewportHeight.toDp() }
                dynamicBottomPaddingImmediate = paddingDp
                dynamicBottomPaddingTarget = paddingDp
                kotlinx.coroutines.delay(100)
                listState.scrollToItem(lastUserIdx, scrollOffset = -firstBubbleScreenY)
                kotlinx.coroutines.delay(50)
                val li = listState.layoutInfo
                val item = li.visibleItemsInfo.firstOrNull { it.index == lastUserIdx }
                if (item != null) {
                    val actualY = item.offset - li.viewportStartOffset
                    val correction = actualY - firstBubbleScreenY
                    if (correction != 0) {
                        listState.scrollBy(correction.toFloat())
                    }
                }
                // 等待 layout 更新后缩减多余 padding
                kotlinx.coroutines.delay(100)
                val liAfter = listState.layoutInfo
                val lastReal = liAfter.visibleItemsInfo.lastOrNull { it.key != "dynamic_padding_spacer" }
                if (lastReal != null) {
                    val gapPx = (liAfter.viewportEndOffset - (lastReal.offset + lastReal.size) - liAfter.afterContentPadding)
                        .coerceAtLeast(0)
                    val gapDp = with(density) { gapPx.toDp() }
                    dynamicBottomPaddingImmediate = gapDp
                    dynamicBottomPaddingTarget = gapDp
                } else {
                    dynamicBottomPaddingImmediate = 0.dp
                    dynamicBottomPaddingTarget = 0.dp
                }
                android.util.Log.d("GrokScroll", "Session restore shrink: lastReal=${lastReal != null}, padding=${dynamicBottomPaddingImmediate}")
                android.util.Log.d("GrokScroll", "Session restore: scrolled to lastUser=$lastUserIdx at Y=$firstBubbleScreenY")
            }
        } else if (chatItems.isNotEmpty()) {
            // 没有保存的滚动位置，默认滚动到底部
            kotlinx.coroutines.delay(100)
            listState.scrollToItem(chatItems.size - 1)
            android.util.Log.d("GrokScroll", "Session changed: no saved position, scrolled to bottom")
        }
    }
    
    LaunchedEffect(isApiCalling) {
        if (!isApiCalling && dynamicBottomPaddingTarget > 0.dp) {
            dynamicBottomPaddingTarget = 0.dp
            dynamicBottomPaddingImmediate = 0.dp
            android.util.Log.d("GrokScroll", "API done, cleared padding")
        }
        if (!isApiCalling) {
            pinnedUserMessageId = null
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
            if (pinnedUserMessageId != null) return@collect
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
        if (!isApiCalling) return@LaunchedEffect
        if (!grokScrollCompleted) return@LaunchedEffect
        val targetY = firstBubbleScreenY
        if (targetY <= 0) return@LaunchedEffect

        val stableWindowNanos = 50_000_000L
        val clampBufferPx = 1
        var stableSinceNanos = 0L
        var lastLayoutVersion = listState.layoutInfo.totalItemsCount
        while (true) {
            val frameNanos = withFrameNanos { it }
            val li = listState.layoutInfo
            val layoutVersion = li.totalItemsCount + li.visibleItemsInfo.sumOf { it.size }
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
                    snapshotFlow { listState.layoutInfo.totalItemsCount + listState.layoutInfo.visibleItemsInfo.sumOf { it.size } }
                        .first { it != lastLayoutVersion }
                    stableSinceNanos = 0L
                }
            }
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
            val topPadding = 85.dp
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
                userScrollEnabled = grokScrollCompleted,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollStateManager.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = 4.dp,
                    end = 4.dp,
                    top = topPadding,
                    bottom = additionalBottomPadding + 10.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
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
                                val hasReasoning = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage) item.hasReasoning else (item as com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageStreaming).hasReasoning
                                val isStreaming = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageStreaming) true else (currentStreamingId == message.id)
                                val blockPayload = item as? com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage
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
                                        maxWidth = bubbleMaxWidth,
                                        hasReasoning = hasReasoning,
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onShowAiMessageOptions(message)
                                        },
                                        isStreaming = isStreaming,
                                        messageOutputType = message.outputType,
                                        viewModel = viewModel,
                                        blockPayload = blockPayload,
                                        showMenuButton = false,
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
                                val hasReasoning = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode) item.hasReasoning else (item as com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCodeStreaming).hasReasoning
                                val isStreaming = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCodeStreaming) true else (currentStreamingId == message.id)

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    AiMessageItem(
                                        message = message,
                                        text = text,
                                        maxWidth = bubbleMaxWidth,
                                        hasReasoning = hasReasoning,
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onShowAiMessageOptions(message)
                                        },
                                        isStreaming = isStreaming,
                                        messageOutputType = message.outputType,
                                        viewModel = viewModel,
                                        showMenuButton = false,
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
                        
                        else -> { /* no-op */ }
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
    maxWidth: Dp,
    hasReasoning: Boolean,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    isStreaming: Boolean,
    isFastScroll: Boolean = false,
    messageOutputType: String,
    viewModel: AppViewModel,
    blockPayload: com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage? = null,
    showMenuButton: Boolean = true,
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
    var heightProtectionActive by remember(message.id) { mutableStateOf(false) }

    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            heightProtectionActive = true
        } else if (heightProtectionActive) {
            kotlinx.coroutines.delay(500L)
            heightProtectionActive = false
        }
    }

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
            val minHeightModifier = if ((isStreaming || heightProtectionActive) && lastMeasuredHeightPx > 0) {
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
                        if (!isStreaming && size.height > 0) {
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

                // 流式阶段也始终对累计全文做结构化分块解析，
                // UI 依靠块级 stable key 复用前面已稳定的 block，
                // 而不是让单个 MarkdownRenderer 重排整段文本。
                EnhancedMarkdownText(
                    message = renderMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    isStreaming = shouldPreferStreamingContent,
                    messageOutputType = messageOutputType,
                    onLongPress = { _ -> onLongPress() },
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

@Composable
fun AiMessageFooterItem(
    message: Message,
    viewModel: AppViewModel,
) {
    if (!message.webSearchResults.isNullOrEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ChatDimensions.HORIZONTAL_PADDING),
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
}

