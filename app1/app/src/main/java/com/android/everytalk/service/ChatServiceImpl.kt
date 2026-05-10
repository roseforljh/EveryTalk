package com.android.everytalk.service

import android.content.Context
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ImageGenerationResponse
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.network.ApiClient
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.models.SelectedMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatServiceImpl(
    private val applicationContext: Context
) : ChatService {
    
    private val _isTextGenerating = MutableStateFlow(false)
    override val isTextGenerating: StateFlow<Boolean> = _isTextGenerating.asStateFlow()
    
    private val _isImageGenerating = MutableStateFlow(false)
    override val isImageGenerating: StateFlow<Boolean> = _isImageGenerating.asStateFlow()
    
    private val _currentTextStreamingMessageId = MutableStateFlow<String?>(null)
    override val currentTextStreamingMessageId: StateFlow<String?> = _currentTextStreamingMessageId.asStateFlow()
    
    private val _currentImageStreamingMessageId = MutableStateFlow<String?>(null)
    override val currentImageStreamingMessageId: StateFlow<String?> = _currentImageStreamingMessageId.asStateFlow()
    
    private var textJob: Job? = null
    private var imageJob: Job? = null
    
    init {
        ApiClient.initialize(applicationContext)
    }
    
    override fun streamChatResponse(
        request: ChatRequest,
        attachments: List<SelectedMediaItem>,
        context: Context,
        isImageGeneration: Boolean
    ): Flow<StreamResult> = flow {
        // 后端/服务层流式回复入口。
        // 先创建一条空 AI 消息，让前端立刻显示回复气泡，再把网络层返回的流式事件持续转发出去。
        val messageId = UUID.randomUUID().toString()
        val newMessage = Message(
            id = messageId,
            text = "",
            sender = Sender.AI,
            contentStarted = false,
            modelName = request.model,
            providerName = request.provider
        )
        
        emit(StreamResult.MessageCreated(newMessage))
        
        if (isImageGeneration) {
            _currentImageStreamingMessageId.value = messageId
        } else {
            _currentTextStreamingMessageId.value = messageId
        }
        
        // ApiClient 负责真正请求模型接口；这里把每个流式事件包装成 StreamResult.Event 交给 ViewModel 更新界面。
        ApiClient.streamChatResponse(request, attachments, context)
            .collect { event ->
                emit(StreamResult.Event(event))
            }
        }
        .onStart {
            // 请求开始和结束时同步 StateFlow，界面据此显示“正在生成”和停止按钮。
            if (isImageGeneration) {
                _isImageGenerating.value = true
            } else {
                _isTextGenerating.value = true
            }
        }
        .onCompletion { cause ->
            if (isImageGeneration) {
                _isImageGenerating.value = false
                _currentImageStreamingMessageId.value = null
            } else {
                _isTextGenerating.value = false
                _currentTextStreamingMessageId.value = null
            }
            if (cause == null) {
                emit(StreamResult.Completed)
            }
        }
        .catch { e ->
            if (e !is CancellationException) {
                emit(StreamResult.Error(e))
            }
        }
        .flowOn(Dispatchers.IO)
    
    override suspend fun generateImage(request: ChatRequest): ImageGenerationResponse {
        return withContext(Dispatchers.IO) {
            _isImageGenerating.value = true
            try {
                ApiClient.generateImage(request)
            } finally {
                _isImageGenerating.value = false
            }
        }
    }
    
    override fun cancelCurrentJob(reason: String, isImageGeneration: Boolean) {
        val job = if (isImageGeneration) imageJob else textJob
        job?.cancel(CancellationException(reason))
        
        if (isImageGeneration) {
            _isImageGenerating.value = false
            _currentImageStreamingMessageId.value = null
            imageJob = null
        } else {
            _isTextGenerating.value = false
            _currentTextStreamingMessageId.value = null
            textJob = null
        }
    }
    
    override fun getActiveJob(isImageGeneration: Boolean): Job? {
        return if (isImageGeneration) imageJob else textJob
    }
    
    fun setActiveJob(job: Job, isImageGeneration: Boolean) {
        if (isImageGeneration) {
            imageJob = job
        } else {
            textJob = job
        }
    }
}
