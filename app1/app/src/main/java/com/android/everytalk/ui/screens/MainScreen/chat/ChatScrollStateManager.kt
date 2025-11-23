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

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) {
                if (!_isAtBottom.value && !_showScrollToBottomButton.value) {
                    showScrollToBottomButtonWithTimeout()
                }
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
                    lastSize = lastVisibleItem?.size ?: 0
                )
            }.collect { snapshot ->
                _isAtBottom.value = snapshot.isStrictlyAtBottom

                if (snapshot.isStrictlyAtBottom) {
                    _showScrollToBottomButton.value = false
                    cancelHideButtonJob()
                }
            }
        }
    }

    private data class ScrollSnapshot(
        val isScrollInProgress: Boolean,
        val isStrictlyAtBottom: Boolean,
        val totalItems: Int,
        val lastIndex: Int,
        val lastSize: Int
    )

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