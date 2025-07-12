package com.example.everytalk.ui.screens.MainScreen.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.example.everytalk.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
            // Any user scroll (not just upwards) should immediately interrupt the auto-scroll.
            // This makes the interruption much more sensitive and responsive.
            if (source == NestedScrollSource.UserInput) {
                if (!userInteracted) {
                    logger.debug("User interaction detected. Interrupting auto-scroll.")
                    userInteracted = true
                    cancelAutoScroll()
                }
                // If they are scrolling up and not at the bottom, show the button.
                if (available.y < -0.1 && !_isAtBottom.value) {
                    showAndThenHideButton()
                }
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (available.y < -100) { // Detect a clear upward fling
                logger.debug("User flung upwards.")
                userInteracted = true
                cancelAutoScroll()
            }
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            // After a fling, we don't want to re-enable auto-scroll even if we land at the bottom.
            // The user must explicitly press the "scroll to bottom" button to re-enable it.
            logger.debug("onPostFling executed. Auto-scroll state is not changed.")
            return super.onPostFling(consumed, available)
        }
    }

    init {
        coroutineScope.launch {
            var prevItemCount = listState.layoutInfo.totalItemsCount

            // Using a tuple (Triple) ensures that distinctUntilChanged works on structural equality,
            // preventing the infinite loop that was causing the app to freeze.
            // The anonymous object `object { ... }` used previously has reference equality,
            // so it would create a new non-equal object on every check.
            snapshotFlow {
                val layoutInfo = listState.layoutInfo
                Triple(
                    listState.isScrollInProgress, // a
                    layoutInfo.totalItemsCount,   // b
                    layoutInfo.visibleItemsInfo.lastOrNull()?.size // c
                )
            }.distinctUntilChanged().collect { (isScrolling, itemCount, _) ->
                val atBottom = checkIfAtBottom()

                // Part 1: Handle UI state based on user scrolling
                if (isScrolling) {
                    if (!atBottom) {
                        showAndThenHideButton()
                    }
                } else {
                    _isAtBottom.value = atBottom
                    if (atBottom) {
                        cancelHideButtonJob()
                        _showScrollToBottomButton.value = false
                    }
                }

                // Part 2: Handle auto-scrolling based on new content
                val isNewItemAdded = itemCount > prevItemCount
                prevItemCount = itemCount

                // Only auto-scroll if the user hasn't interrupted AND is not currently scrolling.
                if (!userInteracted && !isScrolling) {
                    if (isNewItemAdded) {
                        smoothScrollToBottom()
                    } else if (isStreaming) {
                        handleStreamingScroll()
                    }
                }
            }
        }
    }

    private fun checkIfAtBottom(): Boolean {
        val layoutInfo = listState.layoutInfo
        if (layoutInfo.visibleItemsInfo.isEmpty()) return true
        val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
        return lastVisibleItem.index >= layoutInfo.totalItemsCount - 1 &&
                lastVisibleItem.offset + lastVisibleItem.size <= layoutInfo.viewportEndOffset + 100 // Generous tolerance
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                logger.debug("Smooth scroll animation cancelled.")
            } catch (_: Exception) {
                logger.error("Error during smooth scroll")
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
        }
        _isAtBottom.value = true
        _showScrollToBottomButton.value = false
        cancelHideButtonJob()
    }

    fun handleStreamingScroll() {
        if (userInteracted) return

        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
        }
        autoScrollJob = coroutineScope.launch {
            listState.scrollBy(10000f) // Scroll a large amount to ensure it hits the bottom
        }
    }

    private fun cancelAutoScroll() {
        if (autoScrollJob?.isActive == true) {
            autoScrollJob?.cancel()
            logger.debug("Auto-scroll job cancelled.")
        }
        autoScrollJob = null
    }

    private fun showAndThenHideButton() {
        cancelHideButtonJob()
        _showScrollToBottomButton.value = true
        hideButtonJob = coroutineScope.launch {
            delay(2000)
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
        if (!userInteracted) { }
    }

    fun resetScrollState() {
        logger.debug("Resetting scroll state.")
        userInteracted = false
        isStreaming = false
        jumpToBottom()
    }
}