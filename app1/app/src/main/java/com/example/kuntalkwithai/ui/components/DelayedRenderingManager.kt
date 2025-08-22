package com.example.kuntalkwithai.ui.components

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 延迟渲染管理器，用于控制数学公式和表格的渲染时机
 * 解决实时渲染导致的闪白、重影和卡顿问题
 */
class DelayedRenderingManager {
    private val _renderingEnabled = MutableStateFlow(false)
    val renderingEnabled: StateFlow<Boolean> = _renderingEnabled.asStateFlow()
    
    private val _pendingContent = MutableStateFlow<String>("") 
    val pendingContent: StateFlow<String> = _pendingContent.asStateFlow()
    
    private val _isContentComplete = MutableStateFlow(false)
    val isContentComplete: StateFlow<Boolean> = _isContentComplete.asStateFlow()
    
    /**
     * 更新待渲染内容
     */
    fun updateContent(content: String) {
        _pendingContent.value = content
        _isContentComplete.value = false
        // 暂时禁用渲染，等待内容完整
        _renderingEnabled.value = false
    }
    
    /**
     * 标记内容输出完成，启用渲染
     */
    suspend fun markContentComplete() {
        _isContentComplete.value = true
        // 延迟一小段时间确保UI稳定后再渲染
        delay(100)
        _renderingEnabled.value = true
    }
    
    /**
     * 立即启用渲染（用于非流式输出场景）
     */
    fun enableRenderingImmediately() {
        _isContentComplete.value = true
        _renderingEnabled.value = true
    }
    
    /**
     * 重置状态
     */
    fun reset() {
        _renderingEnabled.value = false
        _pendingContent.value = ""
        _isContentComplete.value = false
    }
    
    /**
     * 检查是否应该渲染数学公式和表格
     */
    fun shouldRender(): Boolean {
        return renderingEnabled.value
    }
}

/**
 * Composable函数，提供延迟渲染管理器的状态
 */
@Composable
fun rememberDelayedRenderingManager(): DelayedRenderingManager {
    return remember { DelayedRenderingManager() }
}

/**
 * 延迟渲染状态数据类
 */
data class DelayedRenderingState(
    val isRenderingEnabled: Boolean = false,
    val content: String = "",
    val isContentComplete: Boolean = false
)

/**
 * Composable函数，收集延迟渲染状态
 */
@Composable
fun DelayedRenderingManager.collectAsState(): DelayedRenderingState {
    val isRenderingEnabled by renderingEnabled.collectAsState()
    val content by pendingContent.collectAsState()
    val isContentComplete by isContentComplete.collectAsState()
    
    return DelayedRenderingState(
        isRenderingEnabled = isRenderingEnabled,
        content = content,
        isContentComplete = isContentComplete
    )
}