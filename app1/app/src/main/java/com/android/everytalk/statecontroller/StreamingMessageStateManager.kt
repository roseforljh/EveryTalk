package com.android.everytalk.statecontroller

import android.util.Log
import com.android.everytalk.util.debug.PerformanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * Key Features (参考 Cherry Studio、Lobe Chat、RikkaHub):
 * - Maintains separate StateFlow for each streaming message
 * - Adaptive flush intervals based on content length (类似 Cherry Studio 的动态 chunk 策略)
 * - Debounce mechanism to batch high-frequency updates (16ms = ~60fps)
 * - Automatic cleanup when streaming completes
 * - Code block detection to avoid partial flushes
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
    // 🔧 修复内存泄漏：使用 SupervisorJob 确保子协程失败不影响父协程，并绑定到可控生命周期
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisorJob)
    private val pendingBuffers: MutableMap<String, StringBuilder> = ConcurrentHashMap()
    private val pendingJobs: MutableMap<String, Job> = ConcurrentHashMap()

    // 阈值：最小字符数、最大等待时间（毫秒）
    private val MIN_CHARS_TO_FLUSH = 1  // 降低阈值，依赖 StreamingBuffer 和 adaptiveInterval 控制
    private val DEBOUNCE_MS = 16L  // 降低防抖时间，仅用于合并同一帧的多次更新
    private val MAX_BUFFER_BEFORE_FORCE = 1024

    // 刷新时间限制：最小刷新间隔，避免主线程高频重组
    private val lastFlushTime = ConcurrentHashMap<String, Long>()
    private val MIN_FLUSH_INTERVAL_MS = 50L  // 降低最小间隔，允许更流畅的动画 (20fps)
    
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
        // 尝试立即刷新（flushNow 内部有时间间隔限制）
        if (buf.length >= MAX_BUFFER_BEFORE_FORCE) {
            flushNow(messageId)
            return
        }

        // 使用短防抖合并高频调用，主要依赖 flushNow 的时间限制
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

        // ⛔ 增量解析策略：若整体文本处于未闭合的围栏代码块中，则暂缓刷新，避免半成品导致重解析/重组
        val combined = stateFlow.value + buf.toString()
        if (isInsideUnclosedFence(combined) && buf.length < MAX_BUFFER_BEFORE_FORCE) {
            // 等待更多内容或强制阈值达到
            return
        }

        // 自适应策略：根据当前内容长度动态调整刷新间隔
        val currentLength = stateFlow.value.length
        contentLengthTracker[messageId] = currentLength
        
        // 内容越长，刷新间隔越大（减少大文本重组开销）
        val adaptiveInterval = when {
            currentLength < 500 -> MIN_FLUSH_INTERVAL_MS  // 短文本：50ms
            currentLength < 2000 -> MIN_FLUSH_INTERVAL_MS + 30L  // 中等：80ms
            currentLength < 5000 -> MIN_FLUSH_INTERVAL_MS + 60L  // 较长：110ms
            else -> MIN_FLUSH_INTERVAL_MS + 100L  // 超长：150ms
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
        return finalizeMessage(messageId)
    }

    /**
     * Finalize streaming for a message:
     * 1. Force flush any pending buffers ignoring debounce and safety checks
     * 2. Mark as finished (remove from active list)
     * 3. Return the final complete content
     */
    fun finalizeMessage(messageId: String): String {
        // Cancel pending debounce jobs
        pendingJobs.remove(messageId)?.cancel()
        
        // Force flush pending buffer WITHOUT safety checks (isInsideUnclosedFence)
        val stateFlow = streamingStates[messageId]
        val buf = pendingBuffers[messageId]
        
        if (stateFlow != null && buf != null && buf.isNotEmpty()) {
             val delta = buf.toString()
             stateFlow.value = stateFlow.value + delta
             // Clear buffer
             buf.setLength(0)
             
             Log.i("STREAM_DEBUG", "[StreamingMessageStateManager] 🏁 Final flush for $messageId: deltaLen=${delta.length}, totalLen=${stateFlow.value.length}")
        }
        
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

    // ===== Helpers =====
    private fun isInsideUnclosedFence(text: String): Boolean {
        var idx = 0
        var count = 0
        while (true) {
            val p = text.indexOf("```", idx)
            if (p < 0) break
            count++
            idx = p + 3
        }
        return (count % 2) == 1
    }
}