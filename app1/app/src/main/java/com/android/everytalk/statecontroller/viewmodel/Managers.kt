package com.android.everytalk.statecontroller.viewmodel

import android.app.Application
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.ApiHandler
import com.android.everytalk.statecontroller.ViewModelStateHolder
import kotlinx.coroutines.CoroutineScope

/**
 * MessageSender: 统一封装发送消息（最小占位实现）
 */
class MessageSender(
    private val application: Application,
    private val viewModelScope: CoroutineScope,
    private val stateHolder: ViewModelStateHolder,
    private val apiHandler: ApiHandler,
    private val historyManager: com.android.everytalk.ui.screens.viewmodel.HistoryManager,
    private val showSnackbar: (String) -> Unit,
    private val triggerScrollToBottom: () -> Unit,
    private val uriToBase64Encoder: (android.net.Uri) -> String?
) {
    fun sendMessage(
        messageText: String,
        isFromRegeneration: Boolean = false,
        attachments: List<SelectedMediaItem> = emptyList(),
        audioBase64: String? = null,
        mimeType: String? = null,
        systemPrompt: String? = null,
        isImageGeneration: Boolean = false
    ) {
        val msg = Message(
            id = java.util.UUID.randomUUID().toString(),
            text = messageText,
            sender = Sender.User,
            timestamp = System.currentTimeMillis(),
            attachments = attachments
        )
        if (isImageGeneration) {
            stateHolder.imageGenerationMessages.add(msg)
            stateHolder.isImageConversationDirty.value = true
        } else {
            stateHolder.messages.add(msg)
            stateHolder.isTextConversationDirty.value = true
        }
        triggerScrollToBottom()
    }
}