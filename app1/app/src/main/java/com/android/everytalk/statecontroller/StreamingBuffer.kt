package com.android.everytalk.statecontroller

import android.util.Log
import com.android.everytalk.config.PerformanceConfig
import com.android.everytalk.util.debug.PerformanceMonitor
import kotlinx.coroutines.*

/**
 * StreamingBuffer - 自适应节流内容累积器，用于流式显示平滑输出
 * 
 * 实现自适应节流策略：
 * - 初始以 60fps（16ms）高频刷新确保首屏响应
 * - 当累积字符超过阈值后，根据流速动态调整刷新频率
 * - 避免高速流式时过度重组导致的 UI 卡顿
 */
class StreamingBuffer(
    private val messageId: String,
    private var updateInterval: Long = PerformanceConfig.STREAMING_BUFFER_UPDATE_INTERVAL_MS,
    private val batchThreshold: Int = PerformanceConfig.STREAMING_BUFFER_BATCH_THRESHOLD,
    private val onUpdate: (String) -> Unit,
    private val coroutineScope: CoroutineScope,
    private val enableAdaptiveThrottling: Boolean = true,
    private val enableBatchMerging: Boolean = true // 启用批量合并策略
) {
    private val TAG = "StreamingBuffer"
    
    private val buffer = StringBuilder()
    private val accumulatedContent = StringBuilder()
    private var lastUpdateTime = 0L
    private var pendingFlushJob: Job? = null
    private var totalCharsProcessed = 0
    private var flushCount = 0
    
    private var lastAdaptiveAdjustTime = 0L
    private var chunksSinceLastAdjust = 0
    private var charsSinceLastAdjust = 0
    
    // 批量合并策略：高速流时动态调整最小批量大小
    private var dynamicBatchThreshold = batchThreshold
    private var consecutiveHighSpeedFlushes = 0
    private companion object {
        const val HIGH_SPEED_CHARS_PER_SECOND = 1500
        const val MAX_DYNAMIC_BATCH_THRESHOLD = 50
        const val BATCH_THRESHOLD_INCREMENT = 5
    }
    
    fun append(chunk: String) {
        if (chunk.isEmpty()) return
        
        synchronized(buffer) {
            buffer.append(chunk)
            totalCharsProcessed += chunk.length
            chunksSinceLastAdjust++
            charsSinceLastAdjust += chunk.length
            
            val currentTime = System.currentTimeMillis()
            val timeSinceLastUpdate = currentTime - lastUpdateTime
            val currentBufferSize = buffer.length
            
            if (enableAdaptiveThrottling) {
                adjustIntervalIfNeeded(currentTime)
            }
            
            // 使用动态批量阈值（批量合并策略）
            val effectiveThreshold = if (enableBatchMerging) dynamicBatchThreshold else batchThreshold
            if (currentBufferSize >= effectiveThreshold || timeSinceLastUpdate >= updateInterval) {
                pendingFlushJob?.cancel()
                pendingFlushJob = null
                performFlush(currentTime)
            } else {
                if (pendingFlushJob == null) {
                    scheduleDelayedFlush()
                }
            }
        }
    }
    
    private fun adjustIntervalIfNeeded(currentTime: Long) {
        if (totalCharsProcessed < PerformanceConfig.STREAMING_BUFFER_ADAPTIVE_CHAR_THRESHOLD) return
        
        val timeSinceLastAdjust = currentTime - lastAdaptiveAdjustTime
        if (timeSinceLastAdjust < 500L) return
        
        val charsPerSecond = if (timeSinceLastAdjust > 0) {
            (charsSinceLastAdjust * 1000L / timeSinceLastAdjust).toInt()
        } else 0
        
        val newInterval = when {
            charsPerSecond > 2000 -> (updateInterval + PerformanceConfig.STREAMING_BUFFER_ADAPTIVE_STEP_MS)
                .coerceAtMost(PerformanceConfig.STREAMING_BUFFER_MAX_INTERVAL_MS)
            charsPerSecond < 500 -> (updateInterval - PerformanceConfig.STREAMING_BUFFER_ADAPTIVE_STEP_MS)
                .coerceAtLeast(PerformanceConfig.STREAMING_BUFFER_MIN_INTERVAL_MS)
            else -> updateInterval
        }
        
        if (newInterval != updateInterval) {
            Log.d(TAG, "[$messageId] Adaptive interval: ${updateInterval}ms -> ${newInterval}ms (${charsPerSecond} chars/s)")
            updateInterval = newInterval
        }
        
        // 批量合并策略：高速流时增加批量阈值，减少UI重组次数
        if (enableBatchMerging) {
            if (charsPerSecond > HIGH_SPEED_CHARS_PER_SECOND) {
                consecutiveHighSpeedFlushes++
                if (consecutiveHighSpeedFlushes >= 3) {
                    val newThreshold = (dynamicBatchThreshold + BATCH_THRESHOLD_INCREMENT)
                        .coerceAtMost(MAX_DYNAMIC_BATCH_THRESHOLD)
                    if (newThreshold != dynamicBatchThreshold) {
                        Log.d(TAG, "[$messageId] Batch threshold: $dynamicBatchThreshold -> $newThreshold chars")
                        dynamicBatchThreshold = newThreshold
                    }
                }
            } else {
                consecutiveHighSpeedFlushes = 0
                // 低速时逐渐恢复到基础阈值
                if (dynamicBatchThreshold > batchThreshold) {
                    dynamicBatchThreshold = (dynamicBatchThreshold - 1).coerceAtLeast(batchThreshold)
                }
            }
        }
        
        lastAdaptiveAdjustTime = currentTime
        chunksSinceLastAdjust = 0
        charsSinceLastAdjust = 0
    }
    
    private fun scheduleDelayedFlush() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastUpdate = currentTime - lastUpdateTime
        val delayUntilNextUpdate = (updateInterval - timeSinceLastUpdate).coerceAtLeast(0)
        
        pendingFlushJob = coroutineScope.launch {
            delay(delayUntilNextUpdate)
            // 使用单独的同步块，避免在 delay 期间持有锁
            val shouldFlush = synchronized(buffer) {
                buffer.isNotEmpty()
            }
            if (shouldFlush) {
                synchronized(buffer) {
                    if (buffer.isNotEmpty()) {
                        performFlush(System.currentTimeMillis())
                    }
                }
            }
        }
    }
    
    private fun performFlush(currentTime: Long) {
        if (buffer.isEmpty()) return
        
        val incrementalContent = buffer.toString()
        accumulatedContent.append(incrementalContent)
        val fullContent = accumulatedContent.toString()
        
        buffer.clear()
        lastUpdateTime = currentTime
        flushCount++
        
        PerformanceMonitor.recordBufferFlush(messageId, incrementalContent.length, fullContent.length)
        
        if (flushCount % PerformanceConfig.STREAMING_BUFFER_LOG_SAMPLE_INTERVAL == 0) {
            val avgCharsPerFlush = if (flushCount > 0) totalCharsProcessed / flushCount else 0
            Log.d(TAG, "[$messageId] Flush #$flushCount: interval=${updateInterval}ms, avg=$avgCharsPerFlush chars/flush")
        }
        
        try {
            onUpdate(fullContent)
        } catch (e: Exception) {
            Log.e(TAG, "[$messageId] onUpdate callback ERROR", e)
        }
    }
    
    fun flush() {
        synchronized(buffer) {
            pendingFlushJob?.cancel()
            pendingFlushJob = null
            
            if (buffer.isNotEmpty()) {
                performFlush(System.currentTimeMillis())
            }
        }
    }
    
    fun getCurrentContent(): String {
        synchronized(buffer) {
            return accumulatedContent.toString() + buffer.toString()
        }
    }
    
    fun getCurrentLength(): Int {
        synchronized(buffer) {
            return buffer.length
        }
    }
    
    fun clear() {
        synchronized(buffer) {
            pendingFlushJob?.cancel()
            pendingFlushJob = null
            
            val avgCharsPerFlush = if (flushCount > 0) totalCharsProcessed / flushCount else 0
            Log.d(TAG, "[$messageId] Cleared. Stats: totalChars=$totalCharsProcessed, flushes=$flushCount, avg=$avgCharsPerFlush")
            
            buffer.clear()
            accumulatedContent.clear()
            lastUpdateTime = 0L
            totalCharsProcessed = 0
            flushCount = 0
            updateInterval = PerformanceConfig.STREAMING_BUFFER_UPDATE_INTERVAL_MS
            lastAdaptiveAdjustTime = 0L
            chunksSinceLastAdjust = 0
            charsSinceLastAdjust = 0
            // 重置批量合并状态
            dynamicBatchThreshold = batchThreshold
            consecutiveHighSpeedFlushes = 0
        }
    }
    
    fun getStats(): Map<String, Any> {
        synchronized(buffer) {
            return mapOf(
                "messageId" to messageId,
                "currentBufferSize" to buffer.length,
                "accumulatedSize" to accumulatedContent.length,
                "totalCharsProcessed" to totalCharsProcessed,
                "flushCount" to flushCount,
                "currentInterval" to updateInterval,
                "dynamicBatchThreshold" to dynamicBatchThreshold,
                "hasPendingFlush" to (pendingFlushJob?.isActive == true),
                "timeSinceLastUpdate" to (System.currentTimeMillis() - lastUpdateTime)
            )
        }
    }
    
    fun hasPendingContent(): Boolean {
        synchronized(buffer) {
            return buffer.isNotEmpty()
        }
    }
}