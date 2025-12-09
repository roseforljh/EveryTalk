package com.android.everytalk.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 手势冲突管理器
 * 
 * 功能：
 * - 管理代码块水平滚动状态
 * - 协调抽屉手势和代码块滚动
 * - 防止手势冲突
 * 
 * 使用场景：
 * - 代码块内水平滚动时，禁用抽屉右滑手势
 * - 代码块滚动结束后，重新启用抽屉手势
 */
class GestureConflictManager {
    // 代码块滚动状态
    private val _isCodeBlockScrolling = MutableStateFlow(false)
    val isCodeBlockScrolling: StateFlow<Boolean> = _isCodeBlockScrolling.asStateFlow()
    
    // 当前滚动的代码块数量（支持多个代码块同时存在）
    private val _activeScrollCount = MutableStateFlow(0)
    
    /**
     * 通知代码块开始滚动
     */
    fun onCodeBlockScrollStart() {
        _activeScrollCount.value++
        if (_activeScrollCount.value > 0) {
            _isCodeBlockScrolling.value = true
        }
    }
    
    /**
     * 通知代码块结束滚动
     */
    fun onCodeBlockScrollEnd() {
        _activeScrollCount.value = (_activeScrollCount.value - 1).coerceAtLeast(0)
        if (_activeScrollCount.value == 0) {
            _isCodeBlockScrolling.value = false
        }
    }
    
    /**
     * 重置状态（用于清理）
     */
    fun reset() {
        _activeScrollCount.value = 0
        _isCodeBlockScrolling.value = false
    }
}

