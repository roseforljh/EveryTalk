package com.android.everytalk.statecontroller.controller

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
        if (isImageGeneration) {
            dialogManager.showEditDialog(message.id, message)
            stateHolder._text.value = message.text
        } else {
            val current = getMessageById(message.id)
            stateHolder._editDialogInputText.value = current?.text ?: message.text
            dialogManager.showEditDialog(message.id, message)
        }
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

    fun confirmImageGenerationMessageEdit(updatedText: String) {
        val messageToEdit = dialogManager.editingMessage.value
        android.util.Log.d("EditMessageController", "🔥 confirmImageGenerationMessageEdit called")
        android.util.Log.d("EditMessageController", "   messageToEdit: ${messageToEdit?.id}")
        android.util.Log.d("EditMessageController", "   updatedText: '$updatedText'")
        android.util.Log.d("EditMessageController", "   imageGenerationMessages.size: ${stateHolder.imageGenerationMessages.size}")
        
        if (messageToEdit == null) {
            android.util.Log.e("EditMessageController", "❌ messageToEdit is null! This should not happen.")
            return
        }
        
        scope.launch {
            var needsHistorySave = false
            messagesMutex.withLock {
                val messageIndex = stateHolder.imageGenerationMessages.indexOfFirst { it.id == messageToEdit.id }
                android.util.Log.d("EditMessageController", "   messageIndex: $messageIndex")
                
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
                    } else {
                        android.util.Log.d("EditMessageController", "⚠️ Text unchanged, skipping update")
                    }
                } else {
                    android.util.Log.e("EditMessageController", "❌ Message not found in list! messageId=${messageToEdit.id}")
                    android.util.Log.d("EditMessageController", "   Current message IDs: ${stateHolder.imageGenerationMessages.map { it.id }}")
                }
            }
            if (needsHistorySave) {
                historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = true)
            }
            stateHolder.isImageConversationDirty.value = true
            dialogManager.dismissEditDialog()
            stateHolder._text.value = ""
        }
    }

    fun dismissEditDialog() {
        dialogManager.dismissEditDialog()
        stateHolder._editDialogInputText.value = ""
    }

    fun cancelEditing() {
        dialogManager.dismissEditDialog()
        stateHolder._text.value = ""
    }

    private fun getMessageById(id: String): Message? {
        return stateHolder.messages.find { it.id == id }
            ?: stateHolder.imageGenerationMessages.find { it.id == id }
    }
}