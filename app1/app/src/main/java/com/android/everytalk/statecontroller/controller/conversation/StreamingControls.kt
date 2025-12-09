package com.android.everytalk.statecontroller.controller.conversation

import com.android.everytalk.statecontroller.ApiHandler
import com.android.everytalk.statecontroller.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * StreamingControls
 * 统一封装流式“暂停/恢复/flush”控制逻辑，避免 AppViewModel 内联流程。
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
        // 恢复显示：将当前流式消息累积文本一次性刷新
        scope.launch {
            apiHandler.flushPausedStreamingUpdate(isImageGeneration = isImageMode)
            // 移除流式输出时的自动滚动触发
            // triggerScrollToBottom()
            showSnackbar("已继续")
        }
    }
}