package com.android.everytalk.service

import android.content.Context
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ImageGenerationResponse
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.models.SelectedMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ChatService {
    val isTextGenerating: StateFlow<Boolean>
    val isImageGenerating: StateFlow<Boolean>
    val currentTextStreamingMessageId: StateFlow<String?>
    val currentImageStreamingMessageId: StateFlow<String?>
    
    fun streamChatResponse(
        request: ChatRequest,
        attachments: List<SelectedMediaItem>,
        context: Context,
        isImageGeneration: Boolean = false
    ): Flow<StreamResult>
    
    suspend fun generateImage(request: ChatRequest): ImageGenerationResponse
    
    fun cancelCurrentJob(reason: String, isImageGeneration: Boolean = false)
    
    fun getActiveJob(isImageGeneration: Boolean): Job?
}

sealed class StreamResult {
    data class Event(val event: AppStreamEvent) : StreamResult()
    data class MessageCreated(val message: Message) : StreamResult()
    data class Error(val throwable: Throwable) : StreamResult()
    object Completed : StreamResult()
}
