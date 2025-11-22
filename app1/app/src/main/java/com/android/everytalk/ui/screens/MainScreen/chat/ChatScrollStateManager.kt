package com.android.everytalk.ui.screens.MainScreen.chat

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
    
    // We assume sticky by default so the initial load goes to bottom if applicable
    private var shouldStickToBottom = true
    private var isAutoScrolling = false

    private var hideButtonJob: Job? = null
    private var isStreaming by mutableStateOf(false)

    private val _isAtBottom = mutableStateOf(true)
    val isAtBottom: State<Boolean> = _isAtBottom

    private val _showScrollToBottomButton = mutableStateOf(false)
    val showScrollToBottomButton: State<Boolean> = _showScrollToBottomButton

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!_isAtBottom.value && !_showScrollToBottomButton.value && source == NestedScrollSource.UserInput) {
                showScrollToBottomButtonWithTimeout()
            }
            return Offset.Zero
        }
    }

    init {
        coroutineScope.launch {
            snapshotFlow {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                
                // Strict check for "At Bottom"
                // Must be viewing the last item, AND the bottom of that item must be visible
                val isStrictlyAtBottom = if (lastVisibleItem == null || totalItems == 0) {
                    true
                } else {
                    val viewportEnd = layoutInfo.viewportEndOffset + layoutInfo.afterContentPadding
                    val lastItemBottom = lastVisibleItem.offset + lastVisibleItem.size
                    // Tolerance of 20px for margins/padding/rounding
                    lastVisibleItem.index == totalItems - 1 && lastItemBottom <= viewportEnd + 20
                }

                ScrollSnapshot(
                    isScrollInProgress = listState.isScrollInProgress,
                    isStrictlyAtBottom = isStrictlyAtBottom,
                    totalItems = totalItems,
                    lastIndex = lastVisibleItem?.index ?: 0,
                    lastSize = lastVisibleItem?.size ?: 0
                )
            }.collect { snapshot ->
                _isAtBottom.value = snapshot.isStrictlyAtBottom

                if (snapshot.isScrollInProgress) {
                    if (!isAutoScrolling) {
                        // User is scrolling (or system fling).
                        // If they are currently at the bottom, we latch "sticky".
                        // If they leave the bottom, we unlatch "sticky".
                        shouldStickToBottom = snapshot.isStrictlyAtBottom
                    }
                    // If it IS auto-scrolling, we don't touch 'shouldStickToBottom'
                    // because we expect it to be false during the animation towards the bottom,
                    // but we don't want to cancel our intent.
                } else {
                    // Not scrolling (Idle)
                    isAutoScrolling = false
                    
                    if (shouldStickToBottom) {
                        // We WANT to be at bottom. Are we?
                        if (!snapshot.isStrictlyAtBottom && snapshot.totalItems > 0) {
                            // Content changed or something pushed us up. Scroll back down.
                            smoothScrollToBottom()
                        }
                    } else {
                        // We are free roaming.
                        // But if the user accidentally landed at the bottom, stick again.
                        if (snapshot.isStrictlyAtBottom) {
                            shouldStickToBottom = true
                            _showScrollToBottomButton.value = false
                            cancelHideButtonJob()
                        }
                    }
                    
                    if (snapshot.isStrictlyAtBottom) {
                        _showScrollToBottomButton.value = false
                        cancelHideButtonJob()
                    }
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

    private fun smoothScrollToBottom() {
        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
        }
        isAutoScrolling = true
        autoScrollJob = coroutineScope.launch {
            try {
                val targetIndex = listState.layoutInfo.totalItemsCount - 1
                if (targetIndex >= 0) {
                    // Use animateScrollToItem for smooth "following" effect
                    listState.animateScrollToItem(index = targetIndex)
                }
            } catch (e: Exception) {
                logger.error("Error during smooth scroll: ${e.message}")
            } finally {
                // Do NOT set isAutoScrolling = false here immediately,
                // let the next snapshot (scrolling stopped) handle it.
            }
        }
    }

    fun jumpToBottom() {
        logger.debug("Jumping to bottom.")
        shouldStickToBottom = true
        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
        }
        isAutoScrolling = true
        autoScrollJob = coroutineScope.launch {
            val targetIndex = listState.layoutInfo.totalItemsCount - 1
            if (targetIndex >= 0) {
                listState.scrollToItem(index = targetIndex)
            }
            _isAtBottom.value = true
            _showScrollToBottomButton.value = false
            cancelHideButtonJob()
        }
    }

    // Kept for compatibility
    fun handleStreamingScroll() {
        // No-op: logic is now fully reactive in init block
    }

    private fun cancelAutoScroll() {
        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
        }
        autoScrollJob = null
        isAutoScrolling = false
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
        shouldStickToBottom = true
        jumpToBottom()
    }
}