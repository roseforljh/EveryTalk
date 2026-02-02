package com.android.everytalk.ui.screens.MainScreen.chat.text.state

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.android.everytalk.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import androidx.compose.runtime.withFrameNanos

@Composable
fun rememberChatScrollStateManager(
    listState: LazyListState,
    coroutineScope: CoroutineScope
): ChatScrollStateManager {
    return remember(listState, coroutineScope) {
        ChatScrollStateManager(listState, coroutineScope)
    }
}

class ChatScrollStateManager(
    private val listState: LazyListState,
    private val coroutineScope: CoroutineScope
) {
    private val logger = AppLogger.forComponent("ChatScrollStateManager")

    private var autoScrollJob: Job? = null
    
    private var hideButtonJob: Job? = null
    private var isStreaming by mutableStateOf(false)
    
    private var consecutiveBottomFrames = 0
    private var lastStreamingTransitionTime = 0L
    private val BOTTOM_DETECTION_THRESHOLD = 2
    private val STREAMING_TRANSITION_FREEZE_MS = 150L

    private val _isAtBottom = mutableStateOf(true)
    val isAtBottom: State<Boolean> = _isAtBottom

    private val _showScrollToBottomButton = mutableStateOf(false)
    val showScrollToBottomButton: State<Boolean> = _showScrollToBottomButton

    private var preventAutoScroll = false
    private var isProgrammaticScroll = false

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput || source == NestedScrollSource.SideEffect) {
                preventAutoScroll = false
                updateScrollToBottomButton(available.y)
            }
            return Offset.Zero
        }
        
        private fun updateScrollToBottomButton(availableY: Float) {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            
            val isCloseToBottom = if (lastVisibleItem != null && totalItems > 0) {
                lastVisibleItem.index >= totalItems - 1
            } else {
                true
            }

            if (isCloseToBottom) {
                _showScrollToBottomButton.value = false
            } else {
                if (availableY < -1f) { 
                    _showScrollToBottomButton.value = false
                } else if (availableY > 1f) {
                    _showScrollToBottomButton.value = true
                }
            }
        }
    }

    init {
        coroutineScope.launch {
            snapshotFlow {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val firstVisibleItem = layoutInfo.visibleItemsInfo.firstOrNull()
                
                // reverseLayout=true: index 0 在顶部，canScrollBackward 表示能否向上滚（到 index 0）
                val isAtNewest = !listState.canScrollBackward || totalItems == 0

                ScrollSnapshot(
                    isScrollInProgress = listState.isScrollInProgress,
                    isStrictlyAtBottom = isAtNewest,
                    totalItems = totalItems,
                    lastIndex = firstVisibleItem?.index ?: 0,
                    lastSize = firstVisibleItem?.size ?: 0,
                    firstVisibleIndex = listState.firstVisibleItemIndex,
                    firstVisibleOffset = listState.firstVisibleItemScrollOffset
                )
        }.collect { snapshot ->
                val now = System.currentTimeMillis()
                val inFreezeWindow = now - lastStreamingTransitionTime < STREAMING_TRANSITION_FREEZE_MS
                
                if (snapshot.isStrictlyAtBottom && !inFreezeWindow) {
                    consecutiveBottomFrames++
                } else {
                    consecutiveBottomFrames = 0
                }
                
                val confirmedAtBottom = consecutiveBottomFrames >= BOTTOM_DETECTION_THRESHOLD && !inFreezeWindow
                _isAtBottom.value = confirmedAtBottom

                if (confirmedAtBottom) {
                    _showScrollToBottomButton.value = false
                    cancelHideButtonJob()
                    onReachedBottom()
                }
            }
        }
    }

    private data class ScrollSnapshot(
        val isScrollInProgress: Boolean,
        val isStrictlyAtBottom: Boolean,
        val totalItems: Int,
        val lastIndex: Int,
        val lastSize: Int,
        val firstVisibleIndex: Int,
        val firstVisibleOffset: Int
    )

    private fun onReachedBottom() {
        // Grok 风格：到达底部时无需额外处理
    }
    
    fun updateStreamingState(streaming: Boolean) {
        if (isStreaming && !streaming) {
            lastStreamingTransitionTime = System.currentTimeMillis()
            consecutiveBottomFrames = 0
        }
        isStreaming = streaming
    }

    fun lockAutoScroll() {
        preventAutoScroll = true
        logger.debug("Auto-scroll locked")
    }

    /**
     * 滚动到用户消息的 index，确保其显示在屏幕顶部区域
     * 使用简单的 scrollToItem 而不是复杂的 easing 动画，避免被 canScrollForward/canScrollBackward 阻断
     */
    fun scrollToUserMessage(index: Int) {
        logger.debug("Scrolling to user message at index $index")
        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
        }
        preventAutoScroll = true
        
        autoScrollJob = coroutineScope.launch {
            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .first { total -> total > index }
            
            isProgrammaticScroll = true
            try {
                listState.scrollToItem(index = index, scrollOffset = 0)
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    /**
     * Grok/Intercom-style: 滚动到 index 0 (配合 IntercomArrangement 实现新消息置顶效果)
     * 当新用户消息发送时调用此方法
     */
    fun animateScrollToTop() {
        logger.debug("Animating scroll to top (Grok-style)")
        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
        }
        preventAutoScroll = true
        
        autoScrollJob = coroutineScope.launch {
            isProgrammaticScroll = true
            try {
                listState.animateScrollToItem(index = 0, scrollOffset = 0)
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    fun jumpToBottom(isUserAction: Boolean = false) {
        jumpToBottomInternal(isUserAction = isUserAction, smooth = false)
    }

    fun smoothScrollToBottom(isUserAction: Boolean = false) {
        jumpToBottomInternal(isUserAction = isUserAction, smooth = true)
    }

    private fun jumpToBottomInternal(isUserAction: Boolean, smooth: Boolean) {
        if (!isUserAction && preventAutoScroll) {
            logger.debug("Ignoring auto jumpToBottom because preventAutoScroll is active")
            return
        }
        
        if (isUserAction) {
            preventAutoScroll = false
        }

        logger.debug("Jumping to newest (smooth=$smooth).")
        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
        }
        autoScrollJob = coroutineScope.launch {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0) {
                // reverseLayout=true: index 0 是最新消息
                if (smooth) {
                    listState.animateScrollToItem(index = 0)
                } else {
                    listState.scrollToItem(index = 0)
                }
            }
            _showScrollToBottomButton.value = false
            cancelHideButtonJob()
        }
    }

    /**
     * 将指定 index 的 item 滚动到屏幕顶部（紧贴内容区顶部，即 contentPadding.top 之后）
     * 
     * 修复两个问题：
     * 1. 对齐精确：使用 beforeContentPadding 而非写死 0，确保 item 正好在 AppBar 下方
     * 2. 全程动画：无论距离远近都使用同一个 CubicBezierEasing，保证加速/减速手感一致
     */
    fun scrollItemToTop(index: Int, scrollDurationMs: Int = 300) {
        logger.debug("Scrolling item $index to top.")
        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
        }
        
        preventAutoScroll = true
        
        autoScrollJob = coroutineScope.launch {
            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .first { total -> total > index }
            
            val desiredItemOffsetPx = listState.layoutInfo.beforeContentPadding.coerceAtLeast(0)

            isProgrammaticScroll = true
            try {
                listState.animateScrollToItemWithEasing(
                    index = index,
                    desiredItemOffsetPx = desiredItemOffsetPx,
                    baseDurationMs = scrollDurationMs
                )
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    fun scrollToTop(smooth: Boolean = true) {
        logger.debug("Scrolling to top (Intercom style, smooth=$smooth).")
        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
        }
        
        preventAutoScroll = true
        
        autoScrollJob = coroutineScope.launch {
            isProgrammaticScroll = true
            try {
                if (smooth) {
                    listState.animateScrollToItem(index = 0, scrollOffset = 0)
                } else {
                    listState.scrollToItem(index = 0, scrollOffset = 0)
                }
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    // Kept for compatibility
    fun handleStreamingScroll() {
        // No-op: logic is now fully reactive in init block
    }

    private fun cancelHideButtonJob() {
        hideButtonJob?.cancel()
        hideButtonJob = null
    }


    fun resetScrollState() {
        logger.debug("Resetting scroll state.")
        isStreaming = false
        jumpToBottom()
    }
}

private val DefaultScrollEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f)

private suspend fun LazyListState.animateScrollToItemWithEasing(
    index: Int,
    desiredItemOffsetPx: Int,
    baseDurationMs: Int = 300,
    maxDurationMs: Int = 1600,
    easing: Easing = DefaultScrollEasing,
    maxPasses: Int = 3
) {
    val totalCount = layoutInfo.totalItemsCount
    if (index !in 0 until totalCount) return

    val scrollOffsetForToItem = (layoutInfo.beforeContentPadding - desiredItemOffsetPx).coerceAtLeast(0)

    repeat(maxPasses) {
        findVisibleItem(index)?.let { item ->
            val deltaPx = item.offset - desiredItemOffsetPx
            if (deltaPx != 0) {
                animateScrollBy(
                    value = deltaPx.toFloat(),
                    animationSpec = tween(durationMillis = baseDurationMs, easing = easing)
                )
            }
            return
        }

        val initialEstimate = estimateDistanceToItemPx(index, desiredItemOffsetPx)
        if (initialEstimate == 0f) return

        val viewportPx = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1)
        val durationMs = computeScrollDurationMs(
            distancePx = initialEstimate,
            viewportPx = viewportPx,
            baseDurationMs = baseDurationMs,
            maxDurationMs = maxDurationMs
        )

        scroll {
            val anim = AnimationState(initialValue = 0f)
            var scrolledSoFar = 0f
            var directionSign = initialEstimate.sign.takeIf { it != 0f } ?: 1f

            anim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
            ) {
                val t = value.coerceIn(0f, 1f)
                val easedT = easing.transform(t)

                val remainingPx = this@animateScrollToItemWithEasing.remainingDistanceToItemPx(index, desiredItemOffsetPx)
                val totalPx = scrolledSoFar + remainingPx

                if (totalPx != 0f) directionSign = totalPx.sign

                val desiredScrolledByNow = totalPx * easedT
                var delta = desiredScrolledByNow - scrolledSoFar

                delta = if (directionSign > 0f) max(0f, delta) else min(0f, delta)

                if (delta > 0f && !this@animateScrollToItemWithEasing.canScrollForward) return@animateTo
                if (delta < 0f && !this@animateScrollToItemWithEasing.canScrollBackward) return@animateTo

                val consumed = scrollBy(delta)
                scrolledSoFar += consumed

                if (consumed == 0f) return@animateTo
            }
        }
    }

    scrollToItem(index, scrollOffsetForToItem)
    findVisibleItem(index)?.let { item ->
        val finalDeltaPx = item.offset - desiredItemOffsetPx
        if (finalDeltaPx != 0) {
            animateScrollBy(
                value = finalDeltaPx.toFloat(),
                animationSpec = tween(durationMillis = baseDurationMs, easing = easing)
            )
        }
    }
}

private fun LazyListState.findVisibleItem(index: Int): LazyListItemInfo? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }

private fun LazyListState.remainingDistanceToItemPx(index: Int, desiredItemOffsetPx: Int): Float {
    findVisibleItem(index)?.let { item ->
        return (item.offset - desiredItemOffsetPx).toFloat()
    }
    return estimateDistanceToItemPx(index, desiredItemOffsetPx)
}

private fun LazyListState.estimateDistanceToItemPx(index: Int, desiredItemOffsetPx: Int): Float {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return 0f

    val avgSizePx = visible.sumOf { it.size }.toFloat() / visible.size.coerceAtLeast(1)
    val deltaIndex = index - firstVisibleItemIndex

    val estimatedTargetStartOffsetPx = deltaIndex * avgSizePx - firstVisibleItemScrollOffset
    return estimatedTargetStartOffsetPx - desiredItemOffsetPx
}

private fun computeScrollDurationMs(
    distancePx: Float,
    viewportPx: Int,
    baseDurationMs: Int,
    maxDurationMs: Int
): Int {
    val vp = viewportPx.toFloat().coerceAtLeast(1f)
    val pages = (abs(distancePx) / vp).coerceIn(1f, 6f)
    return (baseDurationMs * pages).roundToInt().coerceAtMost(maxDurationMs)
}
