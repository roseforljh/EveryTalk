package com.android.everytalk.statecontroller

import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.PromptLeakGuard
import com.android.everytalk.util.messageprocessor.MessageProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal class ApiHandlerResourceController(
    private val stateHolder: ViewModelStateHolder,
    private val viewModelScope: CoroutineScope,
    private val messageProcessorMap: ConcurrentHashMap<String, MessageProcessor>,
    private val processedMessageIds: MutableSet<String>,
    private val generatedImageSourceFingerprints: ConcurrentHashMap<String, MutableSet<String>>,
    private val promptLeakDetectors: ConcurrentHashMap<String, PromptLeakGuard.StreamingDetector>,
    private val retryCountMap: ConcurrentHashMap<String, Int>,
    private val logger: AppLogger.ComponentLogger,
    private val onAiMessageFullTextChanged: (messageId: String, currentFullText: String) -> Unit,
) {
    fun removeMessageResources(messageId: String) {
        messageProcessorMap.remove(messageId)
        processedMessageIds.remove(messageId)
        promptLeakDetectors.remove(messageId)
        generatedImageSourceFingerprints.remove(messageId)
        retryCountMap.remove(messageId)
    }

    fun clearTextChatResources() {
        logger.debug("=== TEXT CHAT RESOURCE CLEANUP START ===")
        logger.debug("Clearing text chat resources for session isolation (Requirements: 6.1, 6.2)")
        
        // 获取当前活跃的消息ID
        val currentMessageIds = stateHolder.messages.map { it.id }.toSet()
        val currentStreamingId = stateHolder._currentTextStreamingAiMessageId.value
        
        // 识别需要清理的处理器（不在当前消息列表中的）
        val inactiveProcessorIds = messageProcessorMap.keys.filter { id ->
            !currentMessageIds.contains(id) && id != currentStreamingId
        }
        
        logger.debug("Current active message count: ${currentMessageIds.size}")
        logger.debug("Current streaming message ID: $currentStreamingId")
        logger.debug("Total processors before cleanup: ${messageProcessorMap.size}")
        logger.debug("Inactive processors to remove: ${inactiveProcessorIds.size}")
        
        // 清理不活跃的处理器
        var removedCount = 0
        inactiveProcessorIds.forEach { messageId ->
            messageProcessorMap.remove(messageId)?.let {
                removedCount++
                logger.debug("✓ Removed inactive processor: $messageId")
            }
            // 🛡️ 清理 prompt 泄露检测器
            promptLeakDetectors.remove(messageId)
            generatedImageSourceFingerprints.remove(messageId)
        }
        
        // 清理已处理的消息ID集合
        val processedIdsBeforeCleanup = processedMessageIds.size
        processedMessageIds.clear()
        
        logger.debug("Removed $removedCount inactive message processors")
        logger.debug("Cleared $processedIdsBeforeCleanup processed message IDs")
        logger.debug("Remaining active processors: ${messageProcessorMap.size}")
        logger.debug("Active processor IDs: ${messageProcessorMap.keys}")
        
        // 🎯 触发会话参数清理（Requirements: 6.4）
        stateHolder.cleanupOldConversationParameters()
        logger.debug("Triggered conversation parameter cleanup (keep last 50)")
        
        logger.debug("=== TEXT CHAT RESOURCE CLEANUP END ===")
    }
    
    // 为兼容调用方，提供带 sessionId 的重载，内部忽略参数
    fun clearTextChatResources(@Suppress("UNUSED_PARAMETER") sessionId: String?) {
        clearTextChatResources()
    }
    
    /**
     * 清理图像聊天相关的资源，确保会话间完全隔离
     * 
     * 🎯 优化策略（Requirements: 6.1, 6.2, 6.3）：
     * - 只清理不在当前消息列表中的处理器（inactive processors）
     * - 保留当前活跃会话的所有处理器
     * - 清理已处理的消息ID集合
     * - 触发会话参数清理（保留最近50个）
     */
    fun clearImageChatResources() {
        logger.debug("=== IMAGE CHAT RESOURCE CLEANUP START ===")
        logger.debug("Clearing image chat resources for session isolation (Requirements: 6.1, 6.2)")
        
        // 获取当前活跃的消息ID
        val currentMessageIds = stateHolder.imageGenerationMessages.map { it.id }.toSet()
        val currentStreamingId = stateHolder._currentImageStreamingAiMessageId.value
        
        // 识别需要清理的处理器（不在当前消息列表中的）
        val inactiveProcessorIds = messageProcessorMap.keys.filter { id ->
            !currentMessageIds.contains(id) && id != currentStreamingId
        }
        
        logger.debug("Current active image message count: ${currentMessageIds.size}")
        logger.debug("Current streaming image message ID: $currentStreamingId")
        logger.debug("Total processors before cleanup: ${messageProcessorMap.size}")
        logger.debug("Inactive processors to remove: ${inactiveProcessorIds.size}")
        
        // 清理不活跃的处理器
        var removedCount = 0
        inactiveProcessorIds.forEach { messageId ->
            messageProcessorMap.remove(messageId)?.let {
                removedCount++
                logger.debug("✓ Removed inactive image processor: $messageId")
            }
            // 🛡️ 清理 prompt 泄露检测器
            promptLeakDetectors.remove(messageId)
            generatedImageSourceFingerprints.remove(messageId)
        }
        
        // 清理已处理的消息ID集合
        val processedIdsBeforeCleanup = processedMessageIds.size
        processedMessageIds.clear()
        
        logger.debug("Removed $removedCount inactive image message processors")
        logger.debug("Cleared $processedIdsBeforeCleanup processed message IDs")
        logger.debug("Remaining active processors: ${messageProcessorMap.size}")
        logger.debug("Active processor IDs: ${messageProcessorMap.keys}")
        
        // 🎯 触发会话参数清理（Requirements: 6.4）
        stateHolder.cleanupOldConversationParameters()
        logger.debug("Triggered conversation parameter cleanup (keep last 50)")
        
        logger.debug("=== IMAGE CHAT RESOURCE CLEANUP END ===")
    }
    
    // 为兼容调用方，提供带 sessionId 的重载，内部忽略参数
    fun clearImageChatResources(@Suppress("UNUSED_PARAMETER") sessionId: String?) {
        clearImageChatResources()
    }
    
    /**
     * 当暂停恢复时，将当前流式消息的累积文本一次性刷新到UI。
     */
    fun flushPausedStreamingUpdate(isImageGeneration: Boolean = false) {
        val messageId = if (isImageGeneration)
            stateHolder._currentImageStreamingAiMessageId.value
        else
            stateHolder._currentTextStreamingAiMessageId.value
    
        if (messageId.isNullOrBlank()) return
    
        viewModelScope.launch(Dispatchers.Main.immediate) {
            stateHolder.syncStreamingSnapshotToList(messageId, isImageGeneration)
            val messageList = if (isImageGeneration) stateHolder.imageGenerationMessages else stateHolder.messages
            val idx = messageList.indexOfFirst { it.id == messageId }
            if (idx != -1) {
                val fullText = messageList[idx].text
                try {
                    if (fullText.isNotBlank()) {
                        onAiMessageFullTextChanged(messageId, fullText)
                    }
                } catch (_: Exception) {
                    // 忽略刷新失败，避免影响恢复流程
                }
            }
        }
    }
    
    /**
     * 检测内存压力并触发清理
     * 
     * 🎯 内存管理策略（Requirements: 6.5）：
     * - 监控消息处理器数量
     * - 当处理器数量超过阈值时触发清理
     * - 优先清理不活跃的处理器
     * - 清理旧的会话参数
     * 
     * @return true if cleanup was triggered, false otherwise
     */
    fun checkMemoryPressureAndCleanup(): Boolean {
        val processorCount = messageProcessorMap.size
        val threshold = 100 // 当处理器数量超过100时触发清理
        
        if (processorCount > threshold) {
            logger.debug("=== MEMORY PRESSURE DETECTED ===")
            logger.debug("Processor count ($processorCount) exceeds threshold ($threshold)")
            logger.debug("Triggering aggressive cleanup (Requirement: 6.5)")
            
            // 获取当前活跃的消息ID（文本和图像）
            val activeTextMessageIds = stateHolder.messages.map { it.id }.toSet()
            val activeImageMessageIds = stateHolder.imageGenerationMessages.map { it.id }.toSet()
            val currentTextStreamingId = stateHolder._currentTextStreamingAiMessageId.value
            val currentImageStreamingId = stateHolder._currentImageStreamingAiMessageId.value
            
            val allActiveIds = activeTextMessageIds + activeImageMessageIds + 
                listOfNotNull(currentTextStreamingId, currentImageStreamingId)
            
            // 清理所有不活跃的处理器
            val inactiveProcessorIds = messageProcessorMap.keys.filter { id ->
                !allActiveIds.contains(id)
            }
            
            logger.debug("Active message IDs: ${allActiveIds.size}")
            logger.debug("Inactive processors to remove: ${inactiveProcessorIds.size}")
            
            var removedCount = 0
            inactiveProcessorIds.forEach { messageId ->
                messageProcessorMap.remove(messageId)?.let {
                    removedCount++
                }
                promptLeakDetectors.remove(messageId)
                generatedImageSourceFingerprints.remove(messageId)
            }
            
            // 清理已处理的消息ID集合
            processedMessageIds.clear()
            
            // 清理旧的会话参数
            stateHolder.cleanupOldConversationParameters()
            
            logger.debug("Memory pressure cleanup complete:")
            logger.debug("  - Removed $removedCount inactive processors")
            logger.debug("  - Remaining processors: ${messageProcessorMap.size}")
            logger.debug("  - Cleared processed message IDs")
            logger.debug("  - Cleaned up old conversation parameters")
            logger.debug("=== MEMORY PRESSURE CLEANUP END ===")
            
            return true
        }
        
        return false
    }
    
    /**
     * 获取当前资源使用统计信息
     * 用于调试和监控
     */
    fun getResourceStats(): String {
        return buildString {
            appendLine("=== Resource Statistics ===")
            appendLine("Message Processors: ${messageProcessorMap.size}")
            appendLine("Processed Message IDs: ${processedMessageIds.size}")
            appendLine("Active Text Messages: ${stateHolder.messages.size}")
            appendLine("Active Image Messages: ${stateHolder.imageGenerationMessages.size}")
            appendLine("Conversation Parameters: ${stateHolder.conversationGenerationConfigs.value.size}")
            appendLine("Streaming Buffers: ${stateHolder.getStreamingBufferCount()}")
        }
    }
}

