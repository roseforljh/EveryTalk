package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.network.NetworkUtils
import com.android.everytalk.ui.screens.viewmodel.HistoryManager
import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.debug.PerformanceMonitor
import com.android.everytalk.util.messageprocessor.MessageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal class ApiHandlerErrorController(
    private val stateHolder: ViewModelStateHolder,
    private val historyManager: HistoryManager,
    private val messageProcessorMap: ConcurrentHashMap<String, MessageProcessor>,
    private val retryCountMap: ConcurrentHashMap<String, Int>,
    private val logger: AppLogger.ComponentLogger,
) {
    private val maxRetryAttempts = 3
    private val errorVisualPrefix = "⚠️ "

    suspend fun updateMessageWithError(messageId: String, error: Throwable, isImageGeneration: Boolean = false, allowRetry: Boolean = true) {
        logger.error("Updating message with error", error)
        // Emit abort summary on error
        PerformanceMonitor.onAbort(messageId, reason = "error:${error.message ?: error.javaClass.simpleName}")
        
        // 🎯 刷新 StreamingBuffer 保留部分内容（Requirements: 7.1, 7.2, 7.5）
        stateHolder.flushStreamingBuffer(messageId)
        logger.debug("Flushed StreamingBuffer before error for message: $messageId")
        
        // 🎯 检查是否应该重试（Requirements: 7.3）
        val currentRetryCount = retryCountMap.getOrDefault(messageId, 0)
        if (shouldReturnEarlyForNetworkRetry(
                allowRetry = allowRetry,
                isNetworkError = isNetworkError(error),
                currentRetryCount = currentRetryCount,
                maxRetryAttempts = maxRetryAttempts,
                hasRetryAction = false,
            )) {
            logger.debug("Network error detected, attempting retry ${currentRetryCount + 1}/$maxRetryAttempts for message: $messageId")
            retryCountMap[messageId] = currentRetryCount + 1
            return
        }
    
        if (allowRetry && isNetworkError(error) && currentRetryCount < maxRetryAttempts) {
            logger.warn(
                "Network error detected but no retry action is implemented; handling as terminal error for message: $messageId"
            )
            retryCountMap.remove(messageId)
        } else if (allowRetry && isNetworkError(error)) {
            logger.debug("Max retry attempts reached for message: $messageId")
            retryCountMap.remove(messageId)
        }
    
        stateHolder.syncStreamingMessageToList(messageId, isImageGeneration)
        
        // 获取当前消息ID对应的处理器并重置
        val currentMessageProcessor = messageProcessorMap[messageId] ?: MessageProcessor()
        currentMessageProcessor.reset()
        
        withContext(Dispatchers.Main.immediate) {
            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            val idx = messageList.indexOfFirst { it.id == messageId }
            if (idx != -1) {
                val msg = messageList[idx]
                if (!msg.isError) {
                    val existingContent = (msg.text.takeIf { it.isNotBlank() }
                        ?: msg.reasoning?.takeIf { it.isNotBlank() && msg.text.isBlank() } ?: "")
                    val errorPrefix = if (existingContent.isNotBlank()) "\n\n" else ""
                    val errorTextContent = errorVisualPrefix + when (error) {
                        is IOException -> {
                            val message = NetworkUtils.sanitizeMessage(error.message ?: "IO 错误")
                            if (message.contains("服务器错误") || message.contains("HTTP 错误")) {
                                message
                            } else {
                                "网络通讯故障: $message"
                            }
                        }
                        else -> "处理时发生错误: ${NetworkUtils.sanitizeMessage(error.message ?: "未知应用错误")}"
                    }
                    val errorMsg = msg.copy(
                        text = existingContent + errorPrefix + errorTextContent,
                        isError = true,
                        contentStarted = true,
                        reasoning = if (existingContent == msg.reasoning && errorPrefix.isNotBlank()) null else msg.reasoning,
                        currentWebSearchStage = null,
                        executionStatus = null
                    )
                    messageList[idx] = errorMsg
                    val animationMap = if (isImageGeneration) stateHolder.imageMessageAnimationStates else stateHolder.textMessageAnimationStates
                    if (animationMap[messageId] != true) {
                        animationMap[messageId] = true
                    }
                    historyManager.saveCurrentChatToHistoryIfNeeded(forceSave = true, isImageGeneration = isImageGeneration)
                }
            }
            val currentStreamingId = if (isImageGeneration) 
                stateHolder._currentImageStreamingAiMessageId.value 
            else 
                stateHolder._currentTextStreamingAiMessageId.value
            val isApiCalling = if (isImageGeneration) 
                stateHolder._isImageApiCalling.value 
            else 
                stateHolder._isTextApiCalling.value
                
            if (currentStreamingId == messageId && isApiCalling) {
                if (isImageGeneration) {
                    stateHolder._isImageApiCalling.value = false
                    stateHolder._currentImageStreamingAiMessageId.value = null
                } else {
                    stateHolder._isTextApiCalling.value = false
                    stateHolder._currentTextStreamingAiMessageId.value = null
                }
            }
            
            // 🎯 清理 StreamingBuffer（Requirements: 7.1, 7.2）
            stateHolder.clearStreamingBuffer(messageId)
            logger.debug("Cleared StreamingBuffer after error for message: $messageId")
        }
    }

    private fun isNetworkError(error: Throwable): Boolean {
        return when (error) {
            is IOException -> {
                val message = error.message?.lowercase() ?: ""
                message.contains("network") ||
                    message.contains("timeout") ||
                    message.contains("connection") ||
                    message.contains("unreachable") ||
                    message.contains("failed to connect") ||
                    message.contains("socket") ||
                    message.contains("interrupted")
            }
            else -> false
        }
    }
}
