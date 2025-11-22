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
    var userInteracted by mutableStateOf(false)
        private set
    private var hideButtonJob: Job? = null
    private var isStreaming by mutableStateOf(false)

    private val _isAtBottom = mutableStateOf(true)
    val isAtBottom: State<Boolean> = _isAtBottom

    private val _showScrollToBottomButton = mutableStateOf(false)
    val showScrollToBottomButton: State<Boolean> = _showScrollToBottomButton

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Detect user interaction on any significant scroll
            if (source == NestedScrollSource.UserInput && kotlin.math.abs(available.y) > 2) {
                if (!userInteracted) {
                    logger.debug("User scrolled (drag). Marking as interacted.")
                    userInteracted = true
                    cancelAutoScroll()
                }
                if (!_isAtBottom.value) {
                    showScrollToBottomButtonWithTimeout()
                }
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            // Detect user interaction on fling
            if (kotlin.math.abs(available.y) > 50) {
                if (!userInteracted) {
                    logger.debug("User scrolled (fling). Marking as interacted.")
                    userInteracted = true
                    cancelAutoScroll()
                }
            }
            return Velocity.Zero
        }
    }

    init {
        coroutineScope.launch {
            snapshotFlow {
                val layoutInfo = listState.layoutInfo
                Triple(
                    listState.isScrollInProgress,
                    layoutInfo.totalItemsCount,
                    layoutInfo.visibleItemsInfo.lastOrNull()?.index
                )
            }.distinctUntilChanged().collect { (isScrolling, itemCount, lastVisibleIndex) ->
                val atBottom = checkIfAtBottom(itemCount, lastVisibleIndex)

                if (!isScrolling) {
                    _isAtBottom.value = atBottom
                    if (atBottom) {
                        cancelHideButtonJob()
                        _showScrollToBottomButton.value = false
                    }
                }

                // Auto-scroll logic
                // Only auto-scroll if the user hasn't interrupted AND is not currently scrolling.
                if (!userInteracted && !isScrolling && !atBottom) {
                    smoothScrollToBottom()
                }
            }
        }
    }

    private fun checkIfAtBottom(totalItems: Int, lastVisibleIndex: Int?): Boolean {
        if (totalItems == 0 || lastVisibleIndex == null) return true
        return lastVisibleIndex >= totalItems - 2 // Allow some tolerance
    }

    private fun smoothScrollToBottom() {
        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
        }
        autoScrollJob = coroutineScope.launch {
            try {
                val targetIndex = listState.layoutInfo.totalItemsCount - 1
                if (targetIndex >= 0) {
                    listState.animateScrollToItem(index = targetIndex)
                }
            } catch (e: Exception) {
                logger.error("Error during smooth scroll: ${e.message}")
            }
        }
    }

    fun jumpToBottom() {
        logger.debug("Jumping to bottom.")
        userInteracted = false
        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
        }
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

    // Kept for compatibility, though logic is simplified
    fun handleStreamingScroll() {
        if (userInteracted) return
        if (_isAtBottom.value) return
        smoothScrollToBottom()
    }

    private fun cancelAutoScroll() {
        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
            logger.debug("Auto-scroll job cancelled.")
        }
        autoScrollJob = null
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

    fun onStreamingStarted() {
        logger.debug("Streaming started.")
        isStreaming = true
    }

    fun onStreamingFinished() {
        logger.debug("Streaming finished.")
        isStreaming = false
    }

    fun resetScrollState() {
        logger.debug("Resetting scroll state.")
        userInteracted = false
        isStreaming = false
        jumpToBottom()
    }
}