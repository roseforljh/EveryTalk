package com.android.everytalk.statecontroller

import android.util.Log
import com.android.everytalk.util.debug.PerformanceMonitor
import kotlinx.coroutines.*

/**
 * StreamingBuffer - Throttled content accumulator for smooth streaming display
 * 
 * This class implements a buffering mechanism that accumulates streaming content chunks
 * and triggers UI updates at controlled intervals to prevent excessive recomposition.
 * 
 * Key Features:
 * - Throttles updates to maximum once per 300ms
 * - Batches content until 30 characters accumulated
 * - Provides immediate flush for stream completion
 * - Coroutine-based delayed flush mechanism
 * 
 * Requirements addressed: 1.4, 3.2, 3.3, 3.4
 * 
 * @param messageId Unique identifier for the message being streamed
 * @param updateInterval Minimum time between UI updates in milliseconds (default: 300ms)
 * @param batchThreshold Minimum characters to accumulate before triggering update (default: 30)
 * @param onUpdate Callback invoked when buffer content should be committed to UI
 * @param coroutineScope Scope for managing delayed flush coroutines
 */
class StreamingBuffer(
    private val messageId: String,
    private val updateInterval: Long = 16L,  // 🔥 修复：改为16ms（60fps），接近实时
    private val batchThreshold: Int = 1,    // 🔥 修复：改为1个字符，立即刷新
    private val onUpdate: (String) -> Unit,
    private val coroutineScope: CoroutineScope
) {
    private val TAG = "StreamingBuffer"
    
    /**
     * Internal buffer for accumulating content chunks
     */
    private val buffer = StringBuilder()
    
    /**
     * Accumulated content (never cleared, only grows)
     * This ensures onUpdate always receives the complete text
     */
    private val accumulatedContent = StringBuilder()
    
    /**
     * Timestamp of the last UI update
     */
    private var lastUpdateTime = 0L
    
    /**
     * Job for pending delayed flush operation
     */
    private var pendingFlushJob: Job? = null
    
    /**
     * Total number of characters processed through this buffer
     */
    private var totalCharsProcessed = 0
    
    /**
     * Number of flush operations performed
     */
    private var flushCount = 0
    
    /**
     * Append content chunk to the buffer
     * 
     * This method accumulates content and triggers UI updates based on:
     * 1. Time threshold: 300ms since last update
     * 2. Size threshold: 30 characters accumulated
     * 
     * If neither threshold is met, schedules a delayed flush to ensure
     * content is eventually displayed even if stream slows down.
     * 
     * @param chunk Text content to append
     */
    fun append(chunk: String) {
        if (chunk.isEmpty()) return
        
        synchronized(buffer) {
            buffer.append(chunk)
            totalCharsProcessed += chunk.length
            
            val currentTime = System.currentTimeMillis()
            val timeSinceLastUpdate = currentTime - lastUpdateTime
            val currentBufferSize = buffer.length
            
            // Check if we should flush based on thresholds
            if (currentBufferSize >= batchThreshold || timeSinceLastUpdate >= updateInterval) {
                // Cancel any pending delayed flush since we're flushing now
                pendingFlushJob?.cancel()
                pendingFlushJob = null
                
                performFlush(currentTime)
                
                Log.d(TAG, "[$messageId] Threshold flush: chunk_len=${chunk.length}, " +
                        "bufferSize=$currentBufferSize, timeSince=${timeSinceLastUpdate}ms")
            } else {
                // Schedule delayed flush if not already scheduled
                if (pendingFlushJob == null) {
                    scheduleDelayedFlush()
                }
            }
        }
    }
    
    /**
     * Schedule a delayed flush operation
     * 
     * This ensures content is eventually displayed even if the stream
     * slows down and thresholds are not met. The delay is calculated
     * to trigger at the next update interval boundary.
     */
    private fun scheduleDelayedFlush() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastUpdate = currentTime - lastUpdateTime
        val delayUntilNextUpdate = (updateInterval - timeSinceLastUpdate).coerceAtLeast(0)
        
        pendingFlushJob = coroutineScope.launch {
            delay(delayUntilNextUpdate)
            synchronized(buffer) {
                if (buffer.isNotEmpty()) {
                    performFlush(System.currentTimeMillis())
                    Log.d(TAG, "[$messageId] Delayed flush executed")
                }
            }
        }
    }
    
    /**
     * Perform the actual flush operation
     * 
     * Must be called within synchronized(buffer) block
     * 
     * @param currentTime Current timestamp for tracking
     */
    private fun performFlush(currentTime: Long) {
        if (buffer.isEmpty()) return
        
        // 🎯 核心修复：将buffer内容追加到累积内容，然后传递完整的累积内容
        // 这样onUpdate总是收到完整文本，而不是增量
        val incrementalContent = buffer.toString()
        accumulatedContent.append(incrementalContent)
        val fullContent = accumulatedContent.toString()
        
        buffer.clear()
        lastUpdateTime = currentTime
        flushCount++
        
        // 🔍 [STREAM_DEBUG_ANDROID] 每次flush都记录
        Log.i("STREAM_DEBUG", "[StreamingBuffer] ✅ FLUSH #$flushCount: msgId=$messageId, incrementalLen=${incrementalContent.length}, totalLen=${fullContent.length}")
        // 统一采样与聚合：由 PerformanceMonitor 决定采样输出，避免分散日志
        PerformanceMonitor.recordBufferFlush(messageId, incrementalContent.length, fullContent.length)
        
        // 🎯 Task 11: Add logging for buffer flush frequency
        // Log every 5th flush to track performance without overwhelming logs
        // Requirements: 1.4, 3.4
        if (flushCount % 5 == 0) {
            val avgCharsPerFlush = if (flushCount > 0) totalCharsProcessed / flushCount else 0
            Log.d(TAG, "[$messageId] Buffer flush #$flushCount: " +
                    "incrementalLen=${incrementalContent.length}, " +
                    "accumulatedLen=${fullContent.length}, " +
                    "totalChars=$totalCharsProcessed, " +
                    "avgPerFlush=$avgCharsPerFlush")
        }
        
        // Invoke callback outside synchronized block to prevent deadlock
        // 传递完整的累积内容给onUpdate
        try {
            // 🔍 [STREAM_DEBUG_ANDROID] 记录onUpdate调用
            Log.i("STREAM_DEBUG", "[StreamingBuffer] Calling onUpdate callback: msgId=$messageId, contentLen=${fullContent.length}")
            onUpdate(fullContent)
        } catch (e: Exception) {
            Log.e("STREAM_DEBUG", "[$messageId] ❌ onUpdate callback ERROR", e)
        }
    }
    
    /**
     * Immediately flush all buffered content to UI
     * 
     * This method should be called when:
     * - Stream completes successfully
     * - Stream encounters an error
     * - User cancels the stream
     * 
     * Cancels any pending delayed flush operations.
     */
    fun flush() {
        synchronized(buffer) {
            // Cancel pending delayed flush
            pendingFlushJob?.cancel()
            pendingFlushJob = null
            
            if (buffer.isNotEmpty()) {
                performFlush(System.currentTimeMillis())
                Log.d(TAG, "[$messageId] Manual flush: ${buffer.length} chars")
            }
        }
    }
    
    /**
     * Get current buffered content without flushing
     * 
     * @return Current accumulated content (includes flushed + pending)
     */
    fun getCurrentContent(): String {
        synchronized(buffer) {
            return accumulatedContent.toString() + buffer.toString()
        }
    }
    
    /**
     * Get total accumulated content length
     * 
     * @return Number of characters currently in buffer
     */
    fun getCurrentLength(): Int {
        synchronized(buffer) {
            return buffer.length
        }
    }
    
    /**
     * Clear buffer and reset state
     * 
     * This method should be called when:
     * - Starting a new stream for the same message
     * - Cleaning up after stream completion
     * - Handling errors or cancellation
     * 
     * Cancels any pending delayed flush operations.
     */
    fun clear() {
        synchronized(buffer) {
            // Cancel pending delayed flush
            pendingFlushJob?.cancel()
            pendingFlushJob = null
            
            buffer.clear()
            accumulatedContent.clear()
            lastUpdateTime = 0L
            
            // 🎯 Task 11: Add performance metrics to debug logs
            // Log final statistics when buffer is cleared
            // Requirements: 1.4, 3.4
            val avgCharsPerFlush = if (flushCount > 0) totalCharsProcessed / flushCount else 0
            val avgFlushInterval = if (flushCount > 1) {
                (System.currentTimeMillis() - lastUpdateTime) / flushCount
            } else 0L
            
            Log.d(TAG, "[$messageId] Buffer cleared. Performance stats: " +
                    "totalChars=$totalCharsProcessed, " +
                    "flushes=$flushCount, " +
                    "avgCharsPerFlush=$avgCharsPerFlush, " +
                    "avgFlushInterval=${avgFlushInterval}ms")
        }
    }
    
    /**
     * Get buffer statistics for debugging and monitoring
     * 
     * @return Map containing buffer statistics
     */
    fun getStats(): Map<String, Any> {
        synchronized(buffer) {
            return mapOf(
                "messageId" to messageId,
                "currentBufferSize" to buffer.length,
                "accumulatedSize" to accumulatedContent.length,
                "totalCharsProcessed" to totalCharsProcessed,
                "flushCount" to flushCount,
                "hasPendingFlush" to (pendingFlushJob?.isActive == true),
                "timeSinceLastUpdate" to (System.currentTimeMillis() - lastUpdateTime)
            )
        }
    }
    
    /**
     * Check if buffer has pending content
     * 
     * @return true if buffer contains unflushed content
     */
    fun hasPendingContent(): Boolean {
        synchronized(buffer) {
            return buffer.isNotEmpty()
        }
    }
}
