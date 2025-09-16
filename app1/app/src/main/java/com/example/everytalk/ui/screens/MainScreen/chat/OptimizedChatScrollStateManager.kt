package com.example.everytalk.ui.screens.MainScreen.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.example.everytalk.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * 优化的聊天滚动状态管理器 - 简化逻辑，提升性能
 */
@Composable
fun rememberOptimizedChatScrollStateManager(
    listState: LazyListState,
    coroutineScope: CoroutineScope
): OptimizedChatScrollStateManager {
    return remember(listState, coroutineScope) {
        OptimizedChatScrollStateManager(listState, coroutineScope)
    }
}

class OptimizedChatScrollStateManager(
    private val listState: LazyListState,
    private val coroutineScope: CoroutineScope
) {
    private val logger = AppLogger.forComponent("OptimizedChatScrollStateManager")

    private var autoScrollJob: Job? = null
    private var userInteracted by mutableStateOf(false)
    private var hideButtonJob: Job? = null

    private val _isAtBottom = mutableStateOf(true)
    val isAtBottom: State<Boolean> = _isAtBottom

    private val _showScrollToBottomButton = mutableStateOf(false)
    val showScrollToBottomButton: State<Boolean> = _showScrollToBottomButton

    // 简化的嵌套滚动连接 - 减少复杂的判断逻辑
    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y < -5) {
                // 用户向上滚动超过阈值，标记为已交互
                if (!userInteracted) {
                    logger.debug("User scrolled up. Marking as interacted.")
                    userInteracted = true
                    cancelAutoScroll()
                }
                // 如果不在底部，显示滚动到底部按钮
                if (!_isAtBottom.value) {
                    showScrollToBottomButtonWithTimeout()
                }
            }
            return Offset.Zero
        }
    }

    init {
        // 简化的状态监听 - 只关注关键变化
        coroutineScope.launch {
            snapshotFlow { 
                listState.layoutInfo.let { info ->
                    Triple(
                        info.totalItemsCount,
                        info.visibleItemsInfo.lastOrNull()?.index,
                        listState.isScrollInProgress
                    )
                }
            }.collect { (totalItems, lastVisibleIndex, isScrolling) ->
                val atBottom = checkIfAtBottom(totalItems, lastVisibleIndex)
                
                if (!isScrolling) {
                    _isAtBottom.value = atBottom
                    if (atBottom) {
                        _showScrollToBottomButton.value = false
                        hideButtonJob?.cancel()
                    }
                }
                
                // 自动滚动逻辑 - 只在未交互且有新内容时触发
                if (!userInteracted && !isScrolling && !atBottom) {
                    smoothScrollToBottom()
                }
            }
        }
    }

    private fun checkIfAtBottom(totalItems: Int, lastVisibleIndex: Int?): Boolean {
        if (totalItems == 0 || lastVisibleIndex == null) return true
        return lastVisibleIndex >= totalItems - 2 // 允许一定容差
    }

    private fun smoothScrollToBottom() {
        autoScrollJob?.cancel()
        autoScrollJob = coroutineScope.launch {
            try {
                val targetIndex = listState.layoutInfo.totalItemsCount - 1
                if (targetIndex >= 0) {
                    listState.animateScrollToItem(targetIndex)
                }
            } catch (e: Exception) {
                logger.error("自动滚动失败: ${e.message}")
            }
        }
    }

    fun jumpToBottom() {
        logger.debug("手动跳转到底部")
        userInteracted = false
        autoScrollJob?.cancel()
        
        coroutineScope.launch {
            try {
                val targetIndex = listState.layoutInfo.totalItemsCount - 1
                if (targetIndex >= 0) {
                    listState.scrollToItem(targetIndex)
                }
                _isAtBottom.value = true
                _showScrollToBottomButton.value = false
            } catch (e: Exception) {
                logger.error("跳转到底部失败: ${e.message}")
            }
        }
    }

    private fun showScrollToBottomButtonWithTimeout() {
        hideButtonJob?.cancel()
        _showScrollToBottomButton.value = true
        hideButtonJob = coroutineScope.launch {
            delay(3000) // 3秒后自动隐藏
            _showScrollToBottomButton.value = false
        }
    }

    private fun cancelAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    fun resetScrollState() {
        logger.debug("重置滚动状态")
        userInteracted = false
        jumpToBottom()
    }

    fun onNewContentAdded() {
        // 当有新内容添加时，如果用户未交互且在底部附近，自动滚动
        if (!userInteracted && _isAtBottom.value) {
            smoothScrollToBottom()
        }
    }
}