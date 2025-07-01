package com.example.everytalk.ui.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.everytalk.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 统一的滚动控制类，用于解决滚动行为冲突
 * 提供一致的滚动策略和用户交互体验
 */
class ScrollController(
    private val listState: LazyListState,
    private val coroutineScope: CoroutineScope
) {
    private val logger = AppLogger.forComponent("ScrollController")
    
    // 是否正在自动滚动
    var isAutoScrolling by mutableStateOf(false)
        private set
    
    // 用户是否手动滚动离开底部
    var userManuallyScrolledAwayFromBottom by mutableStateOf(false)
        private set
    
    // 是否在底部
    val isAtBottom: Boolean
        get() = if (listState.layoutInfo.totalItemsCount == 0) {
            true
        } else {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            val lastItem = listState.layoutInfo.totalItemsCount - 1
            
            if (lastVisibleItem == null) {
                false
            } else {
                lastVisibleItem.index == lastItem && lastVisibleItem.offset + lastVisibleItem.size <= listState.layoutInfo.viewportEndOffset
            }
        }
    
    /**
     * 用户滚动事件处理
     * 当用户手动滚动时调用
     */
    fun onUserScroll() {
        if (!isAutoScrolling) {
            userManuallyScrolledAwayFromBottom = !isAtBottom
            if (userManuallyScrolledAwayFromBottom) {
                logger.debug("User manually scrolled away from bottom")
            }
        }
    }
    
    /**
     * 滚动到底部
     * @param force 是否强制滚动，忽略用户手动滚动状态
     * @param smooth 是否使用平滑滚动
     */
    fun scrollToBottom(force: Boolean = false, smooth: Boolean = false) {
        if (force || !userManuallyScrolledAwayFromBottom) {
            logger.debug("Scrolling to bottom (force=$force, smooth=$smooth)")
            coroutineScope.launch {
                try {
                    isAutoScrolling = true
                    if (smooth) {
                        listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                    } else {
                        listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
                    }
                    userManuallyScrolledAwayFromBottom = false
                } finally {
                    isAutoScrolling = false
                }
            }
        } else {
            logger.debug("Scroll to bottom ignored due to user manual scroll")
        }
    }
    
    /**
     * 立即跳转到底部
     * 无论用户是否手动滚动，都会立即跳转到底部
     */
    fun jumpToBottom() {
        logger.debug("Jumping to bottom")
        coroutineScope.launch {
            try {
                isAutoScrolling = true
                listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
                userManuallyScrolledAwayFromBottom = false
            } finally {
                isAutoScrolling = false
            }
        }
    }
    
    /**
     * 重置滚动状态
     * 清除用户手动滚动状态
     */
    fun resetScrollState() {
        userManuallyScrolledAwayFromBottom = false
    }
}