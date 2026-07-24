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
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.android.everytalk.ui.topanchor.BottomScrollReason
import com.android.everytalk.ui.topanchor.shouldAllowBottomScroll
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

internal enum class BottomCorrection {
    None,
    ScrollBy,
    AnchorLastItem,
}

internal fun resolveBottomCorrection(
    remainingPx: Int?,
    tolerancePx: Int = 0,
    canScrollForward: Boolean = remainingPx == null || remainingPx > tolerancePx,
): BottomCorrection = when {
    !canScrollForward -> BottomCorrection.None
    remainingPx == null -> BottomCorrection.AnchorLastItem
    remainingPx <= tolerancePx -> BottomCorrection.AnchorLastItem
    else -> BottomCorrection.ScrollBy
}

@Composable
fun rememberChatScrollStateManager(
    listState: LazyListState,
    coroutineScope: CoroutineScope
): ChatScrollStateManager {
    val manager = remember(listState, coroutineScope) {
        ChatScrollStateManager(listState, coroutineScope)
    }
    DisposableEffect(manager) {
        onDispose { manager.dispose() }
    }
    return manager
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

    private val _isScrollInProgress = mutableStateOf(false)
    val isScrollInProgress: State<Boolean> = _isScrollInProgress

    private val _showScrollToBottomButton = mutableStateOf(false)
    val showScrollToBottomButton: State<Boolean> = _showScrollToBottomButton

    private var preventAutoScroll = false
    private var isProgrammaticScroll = false
    private var isStopBottomPinActive = false
    private var stopBottomPinGeneration = 0L
    private var suppressTopAnchorBottomScroll by mutableStateOf(false)
    private var topAnchorRuntimeClearer: (() -> Unit)? = null
    private var topAnchorUserScrollReleaser: (() -> Unit)? = null
    private var lastLoggedStrictBottom: Boolean? = null
    private val stateObserverJob: Job

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) {
                logger.debug(
                    "User scroll input: dy=${available.y}, " +
                        "pinActive=$isStopBottomPinActive, " +
                        "anchorSuppressed=$suppressTopAnchorBottomScroll, " +
                        "preventAutoScroll=$preventAutoScroll, ${scrollDebugSnapshot()}"
                )
            }
            if (source == NestedScrollSource.UserInput && isStopBottomPinActive) {
                cancelAutoScrollJob()
                preventAutoScroll = true
                logger.debug(
                    "User scroll took control from bottom pin: generation=$stopBottomPinGeneration, " +
                        scrollDebugSnapshot()
                )
            }
            if (source == NestedScrollSource.UserInput && suppressTopAnchorBottomScroll) {
                topAnchorUserScrollReleaser?.invoke()
                suppressTopAnchorBottomScroll = false
                logger.debug("User scroll released top-anchor runtime: ${scrollDebugSnapshot()}")
            }
            return Offset.Zero
        }
    }

    init {
        stateObserverJob = coroutineScope.launch {
            snapshotFlow {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                
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
                _isScrollInProgress.value = snapshot.isScrollInProgress
                _showScrollToBottomButton.value =
                    !snapshot.isStrictlyAtBottom && !isStopBottomPinActive

                if (lastLoggedStrictBottom != snapshot.isStrictlyAtBottom) {
                    lastLoggedStrictBottom = snapshot.isStrictlyAtBottom
                    logger.debug(
                        "Bottom state changed: strictlyAtBottom=${snapshot.isStrictlyAtBottom}, " +
                            "scrollInProgress=${snapshot.isScrollInProgress}, " +
                            "first=${snapshot.firstVisibleIndex}:${snapshot.firstVisibleOffset}, " +
                            "last=${snapshot.lastIndex}:${snapshot.lastSize}, " +
                            "total=${snapshot.totalItems}, ${scrollDebugSnapshot()}"
                    )
                }

                val now = System.currentTimeMillis()
                val inFreezeWindow = now - lastStreamingTransitionTime < STREAMING_TRANSITION_FREEZE_MS
                
                if (snapshot.isStrictlyAtBottom && !inFreezeWindow) {
                    consecutiveBottomFrames++
                } else {
                    consecutiveBottomFrames = 0
                }
                
                val confirmedAtBottom = consecutiveBottomFrames >= BOTTOM_DETECTION_THRESHOLD && !inFreezeWindow
                _isAtBottom.value = confirmedAtBottom
                preventAutoScroll = resolvePreventAutoScroll(
                    currentValue = preventAutoScroll,
                    isProgrammaticScroll = isProgrammaticScroll,
                    isStrictlyAtBottom = snapshot.isStrictlyAtBottom
                )

                if (confirmedAtBottom) {
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

    private data class BottomVisibleItemRevision(
        val key: Any?,
        val size: Int,
    )

    private data class BottomContentRevision(
        val totalItems: Int,
        val viewportEndOffset: Int,
        val visibleItems: List<BottomVisibleItemRevision>,
        val remainingPx: Int?,
        val canScrollForward: Boolean,
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

    fun updateTopAnchorBottomScrollSuppression(suppressed: Boolean) {
        if (suppressTopAnchorBottomScroll != suppressed) {
            logger.debug(
                "Top-anchor bottom-scroll suppression changed: $suppressTopAnchorBottomScroll -> $suppressed, " +
                    scrollDebugSnapshot()
            )
        }
        suppressTopAnchorBottomScroll = suppressed
        if (suppressed) cancelAutoScrollJob()
    }

    fun setTopAnchorRuntimeClearer(clearer: (() -> Unit)?) {
        topAnchorRuntimeClearer = clearer
    }

    fun setTopAnchorUserScrollReleaser(releaser: (() -> Unit)?) {
        topAnchorUserScrollReleaser = releaser
    }

    fun lockAutoScroll() {
        cancelAutoScrollJob()
        preventAutoScroll = true
        logger.debug("Auto-scroll locked: generation=$stopBottomPinGeneration, ${scrollDebugSnapshot()}")
    }

    private fun cancelAutoScrollJob() {
        val hadActivePin = isStopBottomPinActive
        val hadJob = autoScrollJob != null
        val previousGeneration = stopBottomPinGeneration
        stopBottomPinGeneration++
        isStopBottomPinActive = false
        isProgrammaticScroll = false
        autoScrollJob?.cancel()
        autoScrollJob = null
        if (hadActivePin || hadJob) {
            logger.debug(
                "Auto-scroll job cancelled: generation=$previousGeneration->$stopBottomPinGeneration, " +
                    "hadPin=$hadActivePin, ${scrollDebugSnapshot()}"
            )
        }
    }

    /**
     * 滚动到用户消息的 index，确保其显示在屏幕顶部区域
     * 使用简单的 scrollToItem 而不是复杂的 easing 动画，避免被 canScrollForward/canScrollBackward 阻断
     */
    fun scrollToUserMessage(index: Int) {
        logger.debug("Scrolling to user message at index $index")
        cancelAutoScrollJob()
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
        cancelAutoScrollJob()
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
        logger.debug(
            "jumpToBottom requested: userAction=$isUserAction, " +
                "pinActive=$isStopBottomPinActive, preventAutoScroll=$preventAutoScroll, " +
                "anchorSuppressed=$suppressTopAnchorBottomScroll, ${scrollDebugSnapshot()}"
        )
        if (isUserAction) {
            pinToRealBottomUntilUserScroll(clearTopAnchorRuntime = true)
            return
        }
        jumpToBottomInternal(isUserAction = isUserAction, smooth = false)
    }

    fun smoothScrollToBottom(isUserAction: Boolean = false) {
        logger.debug(
            "smoothScrollToBottom requested: userAction=$isUserAction, " +
                "pinActive=$isStopBottomPinActive, ${scrollDebugSnapshot()}"
        )
        jumpToBottomInternal(isUserAction = isUserAction, smooth = true)
    }

    /**
     * 停止回答后清理锚点占位，并在终态内容继续重排时守住真实底部。
     * 用户主动滑动、开始下一次滚动操作或超时后会立即释放守护。
     */
    fun stopStreamingAndJumpToRealBottom() {
        pinToRealBottomUntilUserScroll(clearTopAnchorRuntime = true)
    }

    /**
     * 内容仍可能因公式或图片异步扩高时，持续守住真实底部。
     * 用户主动滑动、开始下一次滚动操作或管理器销毁后会立即释放守护。
     */
    fun pinToRealBottomUntilUserScroll(clearTopAnchorRuntime: Boolean = false) {
        cancelAutoScrollJob()
        preventAutoScroll = false
        suppressTopAnchorBottomScroll = false
        val pinGeneration = ++stopBottomPinGeneration
        isStopBottomPinActive = true
        logger.debug(
            "Bottom pin requested: clearTopAnchorRuntime=$clearTopAnchorRuntime, " +
                "generation=$pinGeneration, ${scrollDebugSnapshot()}"
        )

        autoScrollJob = coroutineScope.launch {
            try {
                snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { totalItems -> totalItems > 0 }
                performBottomCorrection(BottomCorrection.AnchorLastItem)
                if (clearTopAnchorRuntime) {
                    // 先到达包含动态占位的虚拟底部，再清除占位。
                    // 直接删除占位会让 LazyColumn 沿用旧坐标钳制位置，停在真实底部上方。
                    val revisionBeforeClear = bottomContentRevision()
                    logger.debug(
                        "Manual/stop pin reached virtual bottom before reserve clear: " +
                            "generation=$pinGeneration, ${scrollDebugSnapshot()}"
                    )
                    topAnchorRuntimeClearer?.invoke()
                    logger.debug(
                        "Manual/stop pin invoked top-anchor runtime clear: " +
                            "generation=$pinGeneration, ${scrollDebugSnapshot()}"
                    )
                    awaitAnchorClearLayout(revisionBeforeClear)
                    performBottomCorrection(BottomCorrection.AnchorLastItem)
                }
                _showScrollToBottomButton.value = false
                cancelHideButtonJob()
                keepRealBottomPinned(pinGeneration)
            } finally {
                if (pinGeneration == stopBottomPinGeneration) {
                    isStopBottomPinActive = false
                    isProgrammaticScroll = false
                }
                logger.debug(
                    "Bottom pin job ended: generation=$pinGeneration, " +
                        "pinActive=$isStopBottomPinActive, ${scrollDebugSnapshot()}"
                )
            }
        }
    }

    private suspend fun awaitAnchorClearLayout(revisionBeforeClear: BottomContentRevision) {
        repeat(2) { withFrameNanos { } }
        logger.debug(
            "Anchor clear layout observed: changed=${bottomContentRevision() != revisionBeforeClear}, " +
                scrollDebugSnapshot()
        )
    }

    private suspend fun keepRealBottomPinned(pinGeneration: Long) {
        var handledRevision: BottomContentRevision? = null
        while (pinGeneration == stopBottomPinGeneration && isStopBottomPinActive) {
            val changedRevision = snapshotFlow { bottomContentRevision() }
                .distinctUntilChanged()
                .first { revision -> revision != handledRevision }
            if (pinGeneration != stopBottomPinGeneration || !isStopBottomPinActive) return

            val remainingPx = changedRevision.remainingPx
            val correction = resolveBottomCorrection(
                remainingPx = remainingPx,
                canScrollForward = changedRevision.canScrollForward,
            )
            logger.debug(
                "Bottom content revision changed: generation=$pinGeneration, " +
                    "total=${changedRevision.totalItems}, remainingPx=$remainingPx, " +
                    "correction=$correction"
            )
            performBottomCorrection(correction, remainingPx)
            if (correction != BottomCorrection.None) withFrameNanos { }
            handledRevision = bottomContentRevision()
        }
    }

    private fun bottomContentRevision(): BottomContentRevision {
        val layoutInfo = listState.layoutInfo
        return BottomContentRevision(
            totalItems = layoutInfo.totalItemsCount,
            viewportEndOffset = layoutInfo.viewportEndOffset,
            visibleItems = layoutInfo.visibleItemsInfo.map { item ->
                BottomVisibleItemRevision(key = item.key, size = item.size)
            },
            remainingPx = remainingDistanceToBottomPx(layoutInfo),
            canScrollForward = listState.canScrollForward,
        )
    }

    private fun remainingDistanceToBottomPx(): Int? =
        remainingDistanceToBottomPx(listState.layoutInfo)

    private fun remainingDistanceToBottomPx(layoutInfo: LazyListLayoutInfo): Int? {
        val lastIndex = layoutInfo.totalItemsCount - 1
        if (lastIndex < 0) return 0
        val lastItem = layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == lastIndex }
            ?: return null
        val contentEndOffset = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
        return (lastItem.offset + lastItem.size - contentEndOffset).coerceAtLeast(0)
    }

    private suspend fun performBottomCorrection(
        correction: BottomCorrection,
        remainingPx: Int? = remainingDistanceToBottomPx(),
    ) {
        if (correction == BottomCorrection.None) return
        isProgrammaticScroll = true
        try {
            when (correction) {
                BottomCorrection.None -> Unit
                BottomCorrection.AnchorLastItem -> scrollToRealBottom()
                BottomCorrection.ScrollBy -> {
                    listState.scrollBy(requireNotNull(remainingPx).toFloat())
                    if (resolveBottomCorrection(remainingDistanceToBottomPx()) != BottomCorrection.None) {
                        scrollToRealBottom()
                    }
                }
            }
        } finally {
            isProgrammaticScroll = false
        }
    }

    fun dispose() {
        cancelAutoScrollJob()
        cancelHideButtonJob()
        stateObserverJob.cancel()
        topAnchorRuntimeClearer = null
        topAnchorUserScrollReleaser = null
    }

    private fun jumpToBottomInternal(isUserAction: Boolean, smooth: Boolean) {
        if (!isUserAction && isStopBottomPinActive) {
            logger.debug(
                "Ignoring external bottom scroll because real-bottom pin is active: " +
                    "${scrollDebugSnapshot()}"
            )
            return
        }
        val reason = if (isUserAction) {
            BottomScrollReason.Button
        } else {
            BottomScrollReason.ExternalEvent
        }
        if (!shouldAllowBottomScroll(
                isUserAction = isUserAction,
                suppressesBottomScroll = suppressTopAnchorBottomScroll,
                isAtBottom = _isAtBottom.value,
                reason = reason
            )
        ) {
            logger.debug(
                "Ignoring bottom scroll because top anchor is active: " +
                    "reason=$reason, ${scrollDebugSnapshot()}"
            )
            return
        }

        if (!isUserAction && preventAutoScroll) {
            logger.debug(
                "Ignoring auto jumpToBottom because preventAutoScroll is active: " +
                    "${scrollDebugSnapshot()}"
            )
            return
        }
        
        if (isUserAction) {
            topAnchorRuntimeClearer?.invoke()
            suppressTopAnchorBottomScroll = false
            preventAutoScroll = false
        }

        logger.debug("Jumping to bottom (smooth=$smooth).")
        cancelAutoScrollJob()
        autoScrollJob = coroutineScope.launch {
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0) {
                if (smooth) {
                    listState.animateScrollToItem(index = lastIndex)
                    listState.scrollBy(Float.MAX_VALUE)
                } else {
                    scrollToRealBottom()
                }
            }
            _showScrollToBottomButton.value = false
            cancelHideButtonJob()
        }
    }

    private suspend fun scrollToRealBottom() {
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex < 0) return
        listState.scrollToItem(index = lastIndex)
        listState.scrollBy(Float.MAX_VALUE)
    }

    private fun scrollDebugSnapshot(): String {
        val layoutInfo = listState.layoutInfo
        val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()
        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
        return "total=${layoutInfo.totalItemsCount}, " +
            "first=${firstVisible?.index}:${firstVisible?.offset}, " +
            "last=${lastVisible?.index}:${lastVisible?.offset}+${lastVisible?.size}, " +
            "canBack=${listState.canScrollBackward}, canForward=${listState.canScrollForward}, " +
            "viewport=${layoutInfo.viewportStartOffset}..${layoutInfo.viewportEndOffset}"
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
        cancelAutoScrollJob()
        
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
        cancelAutoScrollJob()
        
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

internal fun resolvePreventAutoScroll(
    currentValue: Boolean,
    isProgrammaticScroll: Boolean,
    isStrictlyAtBottom: Boolean
): Boolean {
    if (isProgrammaticScroll) return currentValue
    return !isStrictlyAtBottom
}
