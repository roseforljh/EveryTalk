package com.android.everytalk.ui.screens.MainScreen.chat

import androidx.compose.foundation.gestures.scrollBy
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

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) {
                if (!_isAtBottom.value && !_showScrollToBottomButton.value) {
                    showScrollToBottomButtonWithTimeout()
                }
                // 实时记录用户锚点，不再受 isAtBottom 限制
                onUserScrollSnapshot(listState)
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

    fun jumpToBottom() {
        logger.debug("Jumping to bottom.")
        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
        }
        autoScrollJob = coroutineScope.launch {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0) {
                val lastIndex = totalItems - 1
                
                // First scroll to the item to ensure it's laid out
                listState.scrollToItem(index = lastIndex)
                
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
                             listState.scrollBy(remainingScroll.toFloat())
                         }
                    } else {
                        // If for some reason we are not even at the last index, try scrolling again
                        listState.scrollToItem(index = lastIndex)
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
        autoScrollJob = coroutineScope.launch {
            // Wait a bit to ensure list is updated
            delay(50)
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0 && index in 0 until totalItems) {
                // Scroll to the top of the item (offset 0)
                // Use scrollToItem for instant snap to avoid animation conflict with ongoing updates
                listState.scrollToItem(index, 0)
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