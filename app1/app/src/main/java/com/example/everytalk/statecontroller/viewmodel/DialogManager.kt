package com.example.everytalk.statecontroller.viewmodel

import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.WebSearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 管理所有对话框状态
 */
class DialogManager {
    // 编辑对话框
    private val _showEditDialog = MutableStateFlow(false)
    val showEditDialog: StateFlow<Boolean> = _showEditDialog.asStateFlow()
    
    private val _editingMessageId = MutableStateFlow<String?>(null)
    val editingMessageId: StateFlow<String?> = _editingMessageId.asStateFlow()
    
    private val _editingMessage = MutableStateFlow<Message?>(null)
    val editingMessage: StateFlow<Message?> = _editingMessage.asStateFlow()
    
    // 可选择文本对话框
    private val _showSelectableTextDialog = MutableStateFlow(false)
    val showSelectableTextDialog: StateFlow<Boolean> = _showSelectableTextDialog.asStateFlow()
    
    private val _textForSelectionDialog = MutableStateFlow("")
    val textForSelectionDialog: StateFlow<String> = _textForSelectionDialog.asStateFlow()
    
    // 系统提示对话框
    private val _showSystemPromptDialog = MutableStateFlow(false)
    val showSystemPromptDialog: StateFlow<Boolean> = _showSystemPromptDialog.asStateFlow()
    
    var originalSystemPrompt: String? = null
        private set
    
    // 关于对话框
    private val _showAboutDialog = MutableStateFlow(false)
    val showAboutDialog: StateFlow<Boolean> = _showAboutDialog.asStateFlow()
    
    // 清除图像历史对话框
    private val _showClearImageHistoryDialog = MutableStateFlow(false)
    val showClearImageHistoryDialog: StateFlow<Boolean> = _showClearImageHistoryDialog.asStateFlow()
    
    // 编辑对话框方法
    fun showEditDialog(messageId: String, message: Message) {
        _editingMessageId.value = messageId
        _editingMessage.value = message
        _showEditDialog.value = true
    }
    
    fun dismissEditDialog() {
        _showEditDialog.value = false
        _editingMessageId.value = null
        _editingMessage.value = null  // 🔥 修复：清除编辑消息状态，避免下次发送时误判为编辑模式
    }
    
    fun cancelEditing() {
        _editingMessage.value = null
    }
    
    // 可选择文本对话框方法
    fun showSelectableTextDialog(text: String) {
        _textForSelectionDialog.value = text
        _showSelectableTextDialog.value = true
    }
    
    fun dismissSelectableTextDialog() {
        _showSelectableTextDialog.value = false
        _textForSelectionDialog.value = ""
    }
    
    // 系统提示对话框方法
    fun showSystemPromptDialog(currentPrompt: String) {
        originalSystemPrompt = currentPrompt
        _showSystemPromptDialog.value = true
    }
    
    fun dismissSystemPromptDialog() {
        _showSystemPromptDialog.value = false
        originalSystemPrompt = null
    }
    
    // 关于对话框方法
    fun showAboutDialog() {
        _showAboutDialog.value = true
    }
    
    fun dismissAboutDialog() {
        _showAboutDialog.value = false
    }
    
    // 清除图像历史对话框方法
    fun showClearImageHistoryDialog() {
        _showClearImageHistoryDialog.value = true
    }
    
    fun dismissClearImageHistoryDialog() {
        _showClearImageHistoryDialog.value = false
    }
}
