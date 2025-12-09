package com.android.everytalk.ui.screens.MainScreen.chat.text.state

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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
                _isAtBottom.value = snapshot.isStrictlyAtBottom

                if (snapshot.isStrictlyAtBottom) {
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

    fun scrollItemToTop(index: Int) {
        logger.debug("Scrolling item $index to top.")
        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
        }
        
        // Lock auto-scroll to prevent image loading etc from jumping to bottom
        preventAutoScroll = true
        
        autoScrollJob = coroutineScope.launch {
            // Wait for layout to update and contain the index
            var attempts = 0
            while (attempts < 20) { // Retry for up to 1 second
                val totalItems = listState.layoutInfo.totalItemsCount
                if (totalItems > index) {
                    break
                }
                delay(50)
                attempts++
            }

            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0 && index in 0 until totalItems) {
                // Manually update anchor to prevent "jumping up" when restoreAnchorIfNeeded triggers
                // This ensures that if the list recomposes (e.g. streaming finishes), we stay at this new position
                userAnchored = true
                anchorIndex = index
                anchorScrollOffset = 0
                
                // Scroll to the top of the item (offset 0)
                isProgrammaticScroll = true
                try {
                    // Calculate distance to scroll if item is visible
                    val layoutInfo = listState.layoutInfo
                    val visibleItem = layoutInfo.visibleItemsInfo.find { it.index == index }
                    
                    if (visibleItem != null) {
                        // Item is visible, we can use animateScrollBy for custom control
                        val currentOffset = visibleItem.offset
                        // We want offset to be 0 (top of screen), so we scroll by currentOffset
                        // Use a longer duration (e.g. 800ms) and CubicBezierEasing for a "heavy/slow" natural feel
                        listState.animateScrollBy(
                            value = currentOffset.toFloat(),
                            animationSpec = tween(
                                durationMillis = 800,
                                easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
                            )
                        )
                    } else {
                        // Fallback if item is somehow not visible (should be rare in this flow)
                        listState.animateScrollToItem(index, 0)
                    }
                } finally {
                    isProgrammaticScroll = false
                }
            } else {
                logger.warn("Failed to scroll item $index to top: Index out of bounds (total=$totalItems)")
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