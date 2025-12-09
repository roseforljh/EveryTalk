package com.android.everytalk.statecontroller.controller.conversation

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.ViewModelStateHolder
import com.android.everytalk.statecontroller.viewmodel.DialogManager
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 统一管理消息编辑相关逻辑（文本与图像模式）
 * - 打开/关闭编辑对话框
 * - 编辑文案输入状态
 * - 确认编辑（文本/图像）
 * - 取消编辑
 *
 * 说明：内部不直接触达 UI，所有状态写入通过 stateHolder 完成。
 */
class EditMessageController(
    private val stateHolder: ViewModelStateHolder,
    private val dialogManager: DialogManager,
    private val historyManager: HistoryManager,
    private val scope: CoroutineScope,
    private val messagesMutex: Mutex,
    private val clearMessageCache: (String, Boolean) -> Unit
) {

    fun onEditDialogTextChanged(newText: String) {
        stateHolder._editDialogInputText.value = newText
    }

    fun requestEditMessage(message: Message, isImageGeneration: Boolean = false) {
        if (message.sender != Sender.User) return
        val current = getMessageById(message.id)
        stateHolder._editDialogInputText.value = current?.text ?: message.text
        dialogManager.showEditDialog(message.id, message)
    }

    fun confirmMessageEdit() {
        val messageIdToEdit = dialogManager.editingMessageId.value ?: return
        val updatedText = stateHolder._editDialogInputText.value.trim()
        scope.launch {
            var needsHistorySave = false
            messagesMutex.withLock {
                val messageIndex = stateHolder.messages.indexOfFirst { it.id == messageIdToEdit }
                if (messageIndex != -1) {
                    val originalMessage = stateHolder.messages[messageIndex]
                    if (originalMessage.text != updatedText) {
                        val updatedMessage = originalMessage.copy(
                            text = updatedText,
                            timestamp = System.currentTimeMillis()
                        )
                        val newMessages = stateHolder.messages.toMutableList()
                        newMessages[messageIndex] = updatedMessage
                        stateHolder.messages.clear()
                        stateHolder.messages.addAll(newMessages)
                        if (stateHolder.textMessageAnimationStates[updatedMessage.id] != true) {
                            stateHolder.textMessageAnimationStates[updatedMessage.id] = true
                        }
                        needsHistorySave = true

                        // 🎯 新增：清除缓存以强制UI更新
                        clearMessageCache(messageIdToEdit, false)
                        android.util.Log.d("EditMessageController", "✅ Message edited and cache cleared: ${messageIdToEdit.take(8)}")
                    }
                }
            }
            stateHolder.isTextConversationDirty.value = true
            if (needsHistorySave) {
                scope.launch(Dispatchers.IO) {
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true)
                }
            }
            withContext(Dispatchers.Main.immediate) { dismissEditDialog() }
        }
    }

    fun confirmImageGenerationMessageEdit() {
        val messageToEdit = dialogManager.editingMessage.value
        val updatedText = stateHolder._editDialogInputText.value.trim()
        
        if (messageToEdit == null) {
            return
        }
        
        scope.launch {
            var needsHistorySave = false
            messagesMutex.withLock {
                val messageIndex = stateHolder.imageGenerationMessages.indexOfFirst { it.id == messageToEdit.id }
                
                if (messageIndex != -1) {
                    val originalMessage = stateHolder.imageGenerationMessages[messageIndex]
                    if (originalMessage.text != updatedText) {
                        val updatedMessage = originalMessage.copy(
                            text = updatedText,
                            timestamp = System.currentTimeMillis()
                        )
                        stateHolder.imageGenerationMessages[messageIndex] = updatedMessage
                        needsHistorySave = true

                        // 🎯 新增：清除缓存以强制UI更新
                        clearMessageCache(messageToEdit.id, true)
                        android.util.Log.d("EditMessageController", "✅ Image message edited and cache cleared: ${messageToEdit.id.take(8)}")
                    }
                }
            }
            if (needsHistorySave) {
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = true)
            }
            stateHolder.isImageConversationDirty.value = true
            dialogManager.dismissEditDialog()
        }
    }

    fun dismissEditDialog() {
        dialogManager.dismissEditDialog()
        stateHolder._editDialogInputText.value = ""
    }

    fun cancelEditing() {
        dialogManager.dismissEditDialog()
    }

    private fun getMessageById(id: String): Message? {
        return stateHolder.messages.find { it.id == id }
            ?: stateHolder.imageGenerationMessages.find { it.id == id }
    }
}