package com.android.everytalk.statecontroller.controller.conversation

import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.ConversationScrollState

/**
 * 负责管理会话滚动状态的保存与读取，避免在 AppViewModel 内直接操作映射。
 */
class ScrollStateController(
    private val stateHolder: ViewModelStateHolder
) {

    fun saveScrollState(conversationId: String, scrollState: ConversationScrollState) {
        if (scrollState.firstVisibleItemIndex >= 0) {
            stateHolder.conversationScrollStates[conversationId] = scrollState
        }
    }

    fun getScrollState(conversationId: String): ConversationScrollState? {
        return stateHolder.conversationScrollStates[conversationId]
    }
}