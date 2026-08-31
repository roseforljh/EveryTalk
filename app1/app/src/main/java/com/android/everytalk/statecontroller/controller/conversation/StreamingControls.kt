package com.android.everytalk.statecontroller.controller.conversation

import com.android.everytalk.statecontroller.ApiHandler
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.data.agent.AgentRunControlState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * StreamingControls
 * 统一连接 Composer 与应用级 AgentRun Safe Pause gate。
 *
 * 职责：
 * - 向当前 AgentRun 登记 Pause / Resume
 * - 真实进入 PAUSED 后才冻结 UI 投影
 * - Resume 后对当前流式消息执行一次性 flush
 * - 通过回调报告 UI 提示（Snackbar）
 */
class StreamingControls(
    private val stateHolder: ViewModelStateHolder,
    private val apiHandler: ApiHandler,
    private val scope: CoroutineScope,
    private val agentRunControlState: StateFlow<AgentRunControlState>,
    private val requestPause: () -> Boolean,
    private val resumeAgent: () -> Boolean,
    private val isImageModeProvider: () -> Boolean,
    private val triggerScrollToBottom: () -> Unit,
    private val showSnackbar: (String) -> Unit
) {
    init {
        scope.launch {
            agentRunControlState.collect { state ->
                // 该全局标记只保留为渲染优化镜像，Runtime control state 才是暂停事实。
                stateHolder._isStreamingPaused.value = state == AgentRunControlState.PAUSED
            }
        }
    }

    fun togglePause() {
        when (agentRunControlState.value) {
            AgentRunControlState.RUNNING -> pause()
            AgentRunControlState.PAUSE_REQUESTED -> Unit
            AgentRunControlState.PAUSED -> resume()
        }
    }

    fun pause() {
        if (agentRunControlState.value == AgentRunControlState.RUNNING && requestPause()) {
            showSnackbar("正在安全暂停")
        }
    }

    fun resume() {
        if (agentRunControlState.value != AgentRunControlState.RUNNING && resumeAgent()) {
            flushIfResumed()
        }
    }

    private fun flushIfResumed() {
        val isImageMode = isImageModeProvider()
        scope.launch {
            // Agent gate 已先解除，这里只负责让 UI 立即追上暂停点快照。
            apiHandler.flushPausedStreamingUpdate(isImageGeneration = isImageMode)
            showSnackbar("已继续")
        }
    }
}
