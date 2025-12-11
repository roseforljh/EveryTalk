package com.android.everytalk.statecontroller.controller.conversation

import com.android.everytalk.statecontroller.ApiHandler
import com.android.everytalk.statecontroller.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * StreamingControls
 * 统一封装流式"暂停/恢复/flush"控制逻辑，避免 AppViewModel 内联流程。
 *
 * 职责：
 * - 切换暂停/恢复状态（文本/图像共用暂停标记）
 * - 恢复时对当前流式消息执行一次性 flush 并滚动到底部
 * - 通过回调报告 UI 提示（Snackbar）
 */
class StreamingControls(
    private val stateHolder: ViewModelStateHolder,
    private val apiHandler: ApiHandler,
    private val scope: CoroutineScope,
    private val isImageModeProvider: () -> Boolean,
    private val triggerScrollToBottom: () -> Unit,
    private val showSnackbar: (String) -> Unit
) {

    fun togglePause() {
        val newState = !stateHolder._isStreamingPaused.value
        stateHolder._isStreamingPaused.value = newState
        if (newState) {
            showSnackbar("已暂停显示")
        } else {
            flushIfResumed()
        }
    }

    fun pause() {
        if (!stateHolder._isStreamingPaused.value) {
            stateHolder._isStreamingPaused.value = true
            showSnackbar("已暂停显示")
        }
    }

    fun resume() {
        if (stateHolder._isStreamingPaused.value) {
            stateHolder._isStreamingPaused.value = false
            flushIfResumed()
        }
    }

    private fun flushIfResumed() {
        val isImageMode = isImageModeProvider()
        scope.launch {
            // 获取当前流式消息ID
            val currentStreamingId = if (isImageMode) {
                stateHolder._currentImageStreamingAiMessageId.value
            } else {
                stateHolder._currentTextStreamingAiMessageId.value
            }
            
            // 先同步 StreamingMessageStateManager 中的累积内容到 messages 列表
            if (!currentStreamingId.isNullOrBlank()) {
                stateHolder.syncStreamingMessageToList(currentStreamingId, isImageMode)
            }
            
            // 然后调用 ApiHandler 的 flush 方法
            apiHandler.flushPausedStreamingUpdate(isImageGeneration = isImageMode)
            showSnackbar("已继续")
        }
    }
}