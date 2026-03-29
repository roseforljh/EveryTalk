package com.android.everytalk.statecontroller.controller.systemprompt

import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.viewmodel.DialogManager
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SystemPromptController
 * 抽离 AppViewModel 中“系统提示”相关的全部逻辑：
 * - 显示/隐藏系统提示对话框
 * - 修改/清空/保存系统提示
 * - 扩展/接入状态切换
 */
class SystemPromptController(
    private val stateHolder: ViewModelStateHolder,
    private val dialogManager: DialogManager,
    private val historyManager: HistoryManager,
    private val scope: CoroutineScope
) {
    private val historyMutex = Mutex()

    private var originalSystemPrompt: String? = null

    val systemPromptExpandedState get() = stateHolder.systemPromptExpandedState

    fun showSystemPromptDialog(currentPrompt: String) {
        originalSystemPrompt = currentPrompt
        dialogManager.showSystemPromptDialog(currentPrompt)
    }

    fun dismissSystemPromptDialog() {
        dialogManager.dismissSystemPromptDialog()
        originalSystemPrompt?.let {
            val conversationId = stateHolder._currentConversationId.value
            stateHolder.systemPrompts[conversationId] = it
        }
        originalSystemPrompt = null
        val conversationId = stateHolder._currentConversationId.value
        stateHolder.systemPromptExpandedState[conversationId] = false
    }

    fun onSystemPromptChange(newPrompt: String) {
        val conversationId = stateHolder._currentConversationId.value
        stateHolder.systemPrompts[conversationId] = newPrompt
    }

    /**
     * 清空系统提示（并立即保存）
     */
    fun clearSystemPrompt() {
        val conversationId = stateHolder._currentConversationId.value
        stateHolder.systemPrompts[conversationId] = ""
        originalSystemPrompt = "" // 防止 dismiss 时恢复
        saveSystemPrompt()
    }

    fun saveSystemPrompt() {
        val conversationId = stateHolder._currentConversationId.value

        dialogManager.dismissSystemPromptDialog()
        originalSystemPrompt = null
        stateHolder.systemPromptExpandedState[conversationId] = false

        scope.launch {
            historyMutex.withLock {
                stateHolder.isTextConversationDirty.value = true
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
            }
        }
    }

    fun toggleSystemPromptExpanded() {
        val conversationId = stateHolder._currentConversationId.value
        val currentState = stateHolder.systemPromptExpandedState[conversationId] ?: false
        stateHolder.systemPromptExpandedState[conversationId] = !currentState
    }

    /**
     * 切换“系统提示接入”状态（开始/暂停）
     */
    fun toggleSystemPromptEngaged() {
        val conversationId = stateHolder._currentConversationId.value
        val current = stateHolder.systemPromptEngagedState[conversationId] ?: false
        stateHolder.systemPromptEngagedState[conversationId] = !current
    }

    /**
     * 显式设置接入状态
     */
    fun setSystemPromptEngaged(enabled: Boolean) {
        val conversationId = stateHolder._currentConversationId.value
        stateHolder.systemPromptEngagedState[conversationId] = enabled
    }
}
