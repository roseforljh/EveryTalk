package com.example.everytalk.statecontroller

import android.util.Log
import com.example.everytalk.util.PerformanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * StreamingMessageStateManager
 *
 * Manages real-time streaming state for messages during streaming output.
 * This component provides efficient state observation for UI components,
 * allowing them to observe only the streaming content changes without
 * triggering recomposition of the entire message list.
 *
 * Key Features:
 * - Maintains separate StateFlow for each streaming message
 * - Provides efficient content updates during streaming
 * - Automatically cleans up state when streaming completes
 * - Supports both text and image generation modes
 *
 * Requirements: 1.4, 3.4
 *
 * @see ViewModelStateHolder
 * @see StreamingBuffer
 */
class StreamingMessageStateManager {
    
    // Map of message ID to its streaming content StateFlow
    private val streamingStates = ConcurrentHashMap<String, MutableStateFlow<String>>()
    
    // Track which messages are currently streaming
    private val activeStreamingMessages = ConcurrentHashMap.newKeySet<String>()

    // -------- 新增：最小批量与防抖合并 --------
    private val scope = CoroutineScope(Dispatchers.Default)
    private val pendingBuffers: MutableMap<String, StringBuilder> = ConcurrentHashMap()
    private val pendingJobs: MutableMap<String, Job> = ConcurrentHashMap()

    // 阈值：最小字符数、最大等待时间（毫秒）
    private val MIN_CHARS_TO_FLUSH = 30  // 提升到30字符
    private val DEBOUNCE_MS = 120L  // 提升到120ms
    private val MAX_BUFFER_BEFORE_FORCE = 1024  // 防止无限累计

    // 刷新时间限制：最小刷新间隔，避免主线程高频重组
    private val lastFlushTime = ConcurrentHashMap<String, Long>()
    private val MIN_FLUSH_INTERVAL_MS = 120L  // 提升到120ms
    
    // 自适应策略：根据内容长度动态调整刷新间隔
    private val contentLengthTracker = ConcurrentHashMap<String, Int>()

    /**
     * Get or create a StateFlow for a message's streaming content
     */
    fun getOrCreateStreamingState(messageId: String): StateFlow<String> {
        return streamingStates.getOrPut(messageId) {
            MutableStateFlow("")
        }.asStateFlow()
    }
    
    /**
     * Start streaming for a message
     */
    fun startStreaming(messageId: String) {
        activeStreamingMessages.add(messageId)
        streamingStates.getOrPut(messageId) {
            MutableStateFlow("")
        }
        pendingBuffers.remove(messageId)
        pendingJobs.remove(messageId)?.cancel()
        Log.d("StreamingMessageStateManager", "Started streaming for message: $messageId")
    }
    
    /**
     * Append text to a streaming message（带最小批量与防抖）
     */
    fun appendText(messageId: String, text: String) {
        if (text.isEmpty()) return

        val stateFlow = streamingStates[messageId]
        if (stateFlow == null) {
            Log.w("StreamingMessageStateManager",
                "Attempted to append to non-existent streaming state: $messageId")
            return
        }

        val buf = pendingBuffers.getOrPut(messageId) { StringBuilder() }
        buf.append(text)

        // 条件1：达到最小长度 -> 立即刷
        // 条件2：缓冲过大 -> 强制刷
        if (buf.length >= MIN_CHARS_TO_FLUSH || buf.length >= MAX_BUFFER_BEFORE_FORCE) {
            flushNow(messageId)
            return
        }

        // 否则启动/刷新防抖任务
        pendingJobs.remove(messageId)?.cancel()
        pendingJobs[messageId] = scope.launch {
            delay(DEBOUNCE_MS)
            flushNow(messageId)
        }
    }

    private fun flushNow(messageId: String) {
        val stateFlow = streamingStates[messageId] ?: return
        val buf = pendingBuffers[messageId] ?: return
        if (buf.isEmpty()) return

        // 自适应策略：根据当前内容长度动态调整刷新间隔
        val currentLength = stateFlow.value.length
        contentLengthTracker[messageId] = currentLength
        
        // 内容越长，刷新间隔越大（减少大文本重组开销）
        val adaptiveInterval = when {
            currentLength < 500 -> MIN_FLUSH_INTERVAL_MS  // 短文本：120ms
            currentLength < 2000 -> MIN_FLUSH_INTERVAL_MS + 30L  // 中等：150ms
            currentLength < 5000 -> MIN_FLUSH_INTERVAL_MS + 60L  // 较长：180ms
            else -> MIN_FLUSH_INTERVAL_MS + 100L  // 超长：220ms
        }

        // 限制刷新频率，避免主线程高频重组导致掉帧/ANR
        val now = System.currentTimeMillis()
        val lastTime = lastFlushTime[messageId] ?: 0L
        if ((now - lastTime) < adaptiveInterval && buf.length < MAX_BUFFER_BEFORE_FORCE) {
            // 未到自适应刷新间隔且缓冲未过大 -> 暂不刷新
            return
        }

        // 执行一次合并更新（减少recomposition次数）
        val delta = buf.toString()
        buf.setLength(0)
        
        // 少量日志，避免刷屏
        if (delta.length >= 50 || currentLength > 3000) {
            Log.i("STREAM_DEBUG", "[StreamingMessageStateManager] ✅ Flush: len=${currentLength}, delta=${delta.length}, interval=${adaptiveInterval}ms")
        }
        // 统一采样：由 PerformanceMonitor 聚合并按策略打印，减少零散日志
        PerformanceMonitor.recordStateFlowFlush(messageId, delta.length, currentLength + delta.length)
        
        stateFlow.value = stateFlow.value + delta
        lastFlushTime[messageId] = now
    }

    /**
     * Update the full content of a streaming message
     */
    fun updateContent(messageId: String, content: String) {
        val stateFlow = streamingStates[messageId]
        if (stateFlow != null) {
            // 🔍 [STREAM_DEBUG_ANDROID]
            Log.i("STREAM_DEBUG", "[StreamingMessageStateManager] ✅ Content updated: msgId=$messageId, len=${content.length}, preview='${content.take(50)}'")
            // 在全量替换前清空挂起缓冲，避免乱序
            pendingBuffers.remove(messageId)
            pendingJobs.remove(messageId)?.cancel()
            stateFlow.value = content
        } else {
            // Create new state if it doesn't exist
            Log.w("STREAM_DEBUG", "[StreamingMessageStateManager] ⚠️ Creating new state: msgId=$messageId, len=${content.length}")
            streamingStates[messageId] = MutableStateFlow(content)
        }
    }
    
    /**
     * Finish streaming for a message
     */
    fun finishStreaming(messageId: String): String {
        // 结束前进行一次最终flush
        pendingJobs.remove(messageId)?.cancel()
        flushNow(messageId)

        activeStreamingMessages.remove(messageId)
        val finalContent = streamingStates[messageId]?.value ?: ""
        Log.d("StreamingMessageStateManager",
            "Finished streaming for message: $messageId, final length: ${finalContent.length}")
        return finalContent
    }
    
    /**
     * Clear streaming state for a message
     */
    fun clearStreamingState(messageId: String) {
        activeStreamingMessages.remove(messageId)
        pendingJobs.remove(messageId)?.cancel()
        pendingBuffers.remove(messageId)
        streamingStates.remove(messageId)
        Log.d("StreamingMessageStateManager", "Cleared streaming state for message: $messageId")
    }
    
    /**
     * Clear all streaming states
     */
    fun clearAll() {
        val count = streamingStates.size
        activeStreamingMessages.clear()
        // 取消所有防抖任务并清空缓冲
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
        pendingBuffers.clear()
        streamingStates.clear()
        Log.d("StreamingMessageStateManager", "Cleared all streaming states (count: $count)")
    }
    
    fun isStreaming(messageId: String): Boolean {
        return activeStreamingMessages.contains(messageId)
    }
    
    fun getCurrentContent(messageId: String): String {
        // 读取时附带未flush缓冲，提升一致性
        val base = streamingStates[messageId]?.value ?: ""
        val buf = pendingBuffers[messageId]?.toString().orEmpty()
        return base + buf
    }
    
    fun getActiveStreamingCount(): Int {
        return activeStreamingMessages.size
    }
    
    fun getStats(): Map<String, Any> {
        return mapOf(
            "activeStreamingCount" to activeStreamingMessages.size,
            "totalStatesCount" to streamingStates.size,
            "activeMessageIds" to activeStreamingMessages.toList(),
            "pendingBuffers" to pendingBuffers.mapValues { min(it.value.length, 256) } // 仅输出长度（上限）
        )
    }
    
    /**
     * Cleanup all resources when the manager is no longer needed
     * MUST be called when the parent ViewModel is cleared to prevent memory leaks
     */
    fun cleanup() {
        Log.d("StreamingMessageStateManager", "🧹 Cleaning up StreamingMessageStateManager")
        
        // Cancel all pending jobs
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
        
        // Clear all buffers
        pendingBuffers.clear()
        
        // Clear streaming states
        activeStreamingMessages.clear()
        streamingStates.clear()
        
        // Cancel the coroutine scope
        scope.cancel()
        
        Log.d("StreamingMessageStateManager", "✅ StreamingMessageStateManager cleanup complete")
    }
}
