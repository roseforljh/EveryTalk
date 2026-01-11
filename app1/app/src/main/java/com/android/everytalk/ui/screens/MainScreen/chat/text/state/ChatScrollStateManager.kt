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
    
    // Bug 2 Fix: Hysteresis for bottom detection
    private var consecutiveBottomFrames = 0
    private var lastStreamingTransitionTime = 0L
    private val BOTTOM_DETECTION_THRESHOLD = 2
    private val STREAMING_TRANSITION_FREEZE_MS = 150L

    private val _isAtBottom = mutableStateOf(true)
    val isAtBottom: State<Boolean> = _isAtBottom

    private val _showScrollToBottomButton = mutableStateOf(false)
    val showScrollToBottomButton: State<Boolean> = _showScrollToBottomButton

    // User anchor state
    private var userAnchored by mutableStateOf(false)
    private var anchorIndex by mutableStateOf(0)
    private var anchorScrollOffset by mutableStateOf(0)
    private var lastRestoreTime = 0L
    
    // Flag to prevent auto-scroll (e.g. from image loading) when we want to stay anchored to top
    private var preventAutoScroll = false
    
    // Flag to indicate a programmatic scroll is in progress, so we shouldn't update the anchor from scroll snapshots
    private var isProgrammaticScroll = false

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) {
                if (!_isAtBottom.value && !_showScrollToBottomButton.value) {
                    showScrollToBottomButtonWithTimeout()
                }
                // 实时记录用户锚点，不再受 isAtBottom 限制
                onUserScrollSnapshot(listState)
                // User interaction re-enables auto-scroll
                preventAutoScroll = false
            }
            return Offset.Zero
        }
    }

    init {
        coroutineScope.launch {
            snapshotFlow {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                // With reverseLayout = false, last index is at the bottom
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                
                // Strict check for "At Bottom"
                // In reverseLayout = false, "forward" means scrolling towards the end (index N / bottom).
                // If we cannot scroll forward, we are at the bottom.
                val isStrictlyAtBottom = !listState.canScrollForward || totalItems == 0

                ScrollSnapshot(
                    isScrollInProgress = listState.isScrollInProgress,
                    isStrictlyAtBottom = isStrictlyAtBottom,
                    totalItems = totalItems,
                    lastIndex = lastVisibleItem?.index ?: 0,
                    lastSize = lastVisibleItem?.size ?: 0,
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

    fun onUserScrollSnapshot(listState: LazyListState) {
        // If we are scrolling programmatically (e.g. scrollItemToTop animation), ignore snapshots.
        // The snapshot might capture an intermediate state during the animation, overwriting our target anchor.
        if (isProgrammaticScroll) return

        // 只要用户在手动滚动，就实时更新锚点，无论是否在底部
        // 这样能确保锚点始终跟随用户的最新视线，避免在贴底滑动时锚点滞后
        userAnchored = true
        anchorIndex = listState.firstVisibleItemIndex
        anchorScrollOffset = listState.firstVisibleItemScrollOffset
        // logger.debug("User anchored at index=$anchorIndex, offset=$anchorScrollOffset")
    }

    private fun onReachedBottom() {
        if (userAnchored) {
            userAnchored = false
            // logger.debug("Reached bottom, clearing anchor")
        }
    }
    
    fun updateStreamingState(streaming: Boolean) {
        if (isStreaming && !streaming) {
            lastStreamingTransitionTime = System.currentTimeMillis()
            consecutiveBottomFrames = 0
        }
        isStreaming = streaming
    }

    suspend fun restoreAnchorIfNeeded(listState: LazyListState) {
        if (userAnchored && !_isAtBottom.value) {
            val now = System.currentTimeMillis()
            // Throttle restore to avoid fighting with layout
            if (now - lastRestoreTime > 100) {
                // logger.debug("Restoring anchor to index=$anchorIndex, offset=$anchorScrollOffset")
                listState.scrollToItem(anchorIndex, anchorScrollOffset)
                lastRestoreTime = now
            }
        }
    }

    /**
     * 锁定自动滚动，防止 jumpToBottom 被自动触发。
     * 用于发送消息时，在 scrollItemToTop 被调用前就阻止 onNewAiMessageAdded 等触发的自动滚动。
     */
    fun lockAutoScroll() {
        preventAutoScroll = true
        logger.debug("Auto-scroll locked")
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

        logger.debug("Jumping to bottom (smooth=$smooth).")
        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
        }
        autoScrollJob = coroutineScope.launch {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0) {
                val lastIndex = totalItems - 1
                
                // First scroll to the item to ensure it's laid out
                // Even for smooth scroll, we snap first if we are far away to avoid long animations
                if (!smooth || totalItems - listState.firstVisibleItemIndex > 10) {
                    listState.scrollToItem(index = lastIndex)
                }
                
                // Wait for layout to update
                withFrameNanos { }
                
                // Retry scroll to ensure we are truly at the bottom
                // Sometimes layout changes (like keyboard or content resize) happen after the first scroll
                delay(50)
                
                val layoutInfo = listState.layoutInfo
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                
                if (lastVisible != null) {
                    // Calculate the distance to the very bottom of the content
                    val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                    val contentHeight = lastVisible.offset + lastVisible.size
                    
                    // If the last item is visible but its bottom is below the viewport, scroll more
                    if (lastVisible.index == lastIndex) {
                         val remainingScroll = (lastVisible.offset + lastVisible.size) - layoutInfo.viewportEndOffset
                         if (remainingScroll > 0) {
                             if (smooth) {
                                 listState.animateScrollBy(remainingScroll.toFloat())
                             } else {
                                 listState.scrollBy(remainingScroll.toFloat())
                             }
                         }
                    } else {
                        // If for some reason we are not even at the last index, try scrolling again
                        if (smooth) {
                            listState.animateScrollToItem(index = lastIndex)
                        } else {
                            listState.scrollToItem(index = lastIndex)
                        }
                    }
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
            
            val desiredItemOffsetPx = listState.layoutInfo.beforeContentPadding
            
            userAnchored = true
            anchorIndex = index
            anchorScrollOffset = desiredItemOffsetPx
            
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

    // Kept for compatibility
    fun handleStreamingScroll() {
        // No-op: logic is now fully reactive in init block
    }

    private fun showScrollToBottomButtonWithTimeout() {
        cancelHideButtonJob()
        _showScrollToBottomButton.value = true
        hideButtonJob = coroutineScope.launch {
            delay(3000)
            _showScrollToBottomButton.value = false
        }
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