package com.example.everytalk.ui.state

import android.util.Log

/**
 * AI 消息气泡的可视化状态与状态机
 * 用于消除“连接中/思考/流式/完成/错误”展示的条件分支地狱，统一驱动 UI。
 */
sealed class AiBubbleState {
    object Idle : AiBubbleState()
    object Connecting : AiBubbleState()
    data class Reasoning(
        val content: String,
        val isComplete: Boolean = false
    ) : AiBubbleState()
    data class Streaming(
        val content: String,
        val hasReasoning: Boolean = false,
        val reasoningComplete: Boolean = false
    ) : AiBubbleState()
    data class Complete(
        val content: String,
        val reasoning: String? = null
    ) : AiBubbleState()
    data class Error(val message: String) : AiBubbleState()
}

/**
 * 状态转换事件
 */
sealed class BubbleEvent {
    object Connect : BubbleEvent()
    data class ReasoningStart(val content: String) : BubbleEvent()
    data class ReasoningUpdate(val content: String) : BubbleEvent()
    object ReasoningFinish : BubbleEvent()
    data class ContentStart(val content: String) : BubbleEvent()
    data class ContentUpdate(val content: String) : BubbleEvent()
    object StreamFinish : BubbleEvent()
    data class ErrorOccurred(val message: String) : BubbleEvent()
}

/**
 * 简单状态机：将外界事件映射为 AiBubbleState
 * 注意：该状态机为轻量纯逻辑，不做任何 UI 绑定，便于单测。
 */
class AiBubbleStateMachine(
    initialState: AiBubbleState = AiBubbleState.Idle
) {
    private var _currentState: AiBubbleState = initialState
    val currentState: AiBubbleState get() = _currentState

    private val tag = "AiBubbleStateMachine"

    fun handle(event: BubbleEvent): AiBubbleState {
        val before = _currentState
        _currentState = when (val s = _currentState) {
            is AiBubbleState.Idle -> when (event) {
                is BubbleEvent.Connect -> AiBubbleState.Connecting
                is BubbleEvent.ReasoningStart -> AiBubbleState.Reasoning(event.content, isComplete = false)
                is BubbleEvent.ContentStart -> AiBubbleState.Streaming(content = event.content)
                is BubbleEvent.ErrorOccurred -> AiBubbleState.Error(event.message)
                else -> s
            }
            is AiBubbleState.Connecting -> when (event) {
                is BubbleEvent.ReasoningStart -> AiBubbleState.Reasoning(event.content, isComplete = false)
                is BubbleEvent.ContentStart -> AiBubbleState.Streaming(content = event.content)
                is BubbleEvent.ErrorOccurred -> AiBubbleState.Error(event.message)
                else -> s
            }
            is AiBubbleState.Reasoning -> when (event) {
                is BubbleEvent.ReasoningUpdate -> s.copy(content = event.content)
                is BubbleEvent.ReasoningFinish -> s.copy(isComplete = true)
                is BubbleEvent.ContentStart -> AiBubbleState.Streaming(
                    content = event.content,
                    hasReasoning = true,
                    reasoningComplete = s.isComplete
                )
                is BubbleEvent.ErrorOccurred -> AiBubbleState.Error(event.message)
                else -> s
            }
            is AiBubbleState.Streaming -> when (event) {
                is BubbleEvent.ContentUpdate -> s.copy(content = event.content)
                is BubbleEvent.StreamFinish -> AiBubbleState.Complete(
                    content = s.content,
                    reasoning = if (s.hasReasoning) "" else null
                )
                is BubbleEvent.ErrorOccurred -> AiBubbleState.Error(event.message)
                else -> s
            }
            is AiBubbleState.Complete -> when (event) {
                is BubbleEvent.Connect -> AiBubbleState.Connecting
                else -> s
            }
            is AiBubbleState.Error -> when (event) {
                is BubbleEvent.Connect -> AiBubbleState.Connecting
                else -> s
            }
        }
        if (before != _currentState) {
            Log.d(tag, "State: ${before::class.simpleName} -> ${_currentState::class.simpleName}")
        }
        return _currentState
    }

    fun reset() {
        _currentState = AiBubbleState.Idle
    }
}