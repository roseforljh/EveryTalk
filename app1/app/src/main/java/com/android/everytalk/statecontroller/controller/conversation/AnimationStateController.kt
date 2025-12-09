package com.android.everytalk.statecontroller.controller.conversation

import com.android.everytalk.statecontroller.ViewModelStateHolder

/**
 * 统一管理消息动画播放状态（文本/图像模式）。
 * 由外部提供 isInImageMode() 判定当前模式。
 */
class AnimationStateController(
    private val stateHolder: ViewModelStateHolder,
    private val isInImageMode: () -> Boolean
) {
    fun onAnimationComplete(messageId: String) {
        val map = if (isInImageMode()) stateHolder.imageMessageAnimationStates
                  else stateHolder.textMessageAnimationStates
        if (map[messageId] != true) {
            map[messageId] = true
        }
    }

    fun hasAnimationBeenPlayed(messageId: String): Boolean {
        val map = if (isInImageMode()) stateHolder.imageMessageAnimationStates
                  else stateHolder.textMessageAnimationStates
        return map[messageId] ?: false
    }
}